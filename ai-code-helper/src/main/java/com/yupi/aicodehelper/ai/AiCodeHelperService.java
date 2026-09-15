package com.yupi.aicodehelper.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 编程小助手的核心服务接口。
 *
 * <h3>重要：这是一个没有任何实现类的接口</h3>
 * 它没有 {@code XxxImpl}。实现对象由 {@link AiCodeHelperServiceFactory} 在
 * Spring 启动时用 {@code AiServices.builder(...).build()} 动态生成。
 * 生成的代理对象会根据方法上的注解，自动完成「拼提示词 → 调模型 → 解析结果」
 * 的全过程。所以在这里，<b>注解就是代码逻辑</b>，看懂注解等于看懂实现。
 *
 * <h3>方法返回值决定了「输出如何解析」</h3>
 * 这是 LangChain4j 一个很优雅的设计：你不用写任何解析代码，
 * 框架根据<b>返回类型</b>决定怎么处理模型的返回文本。
 * <table border="1">
 *   <caption>返回类型与解析策略</caption>
 *   <tr><th>返回类型</th><th>框架行为</th></tr>
 *   <tr><td>{@code String}</td><td>直接把模型回答的纯文本返回</td></tr>
 *   <tr><td>{@code record / POJO}</td>
 *       <td>让模型按该结构的字段输出 JSON，再反序列化成对象（结构化输出）</td></tr>
 *   <tr><td>{@code Flux<String>}</td>
 *       <td>流式返回，模型每生成一小段就推送一次（需要配 StreamingChatModel）</td></tr>
 *   <tr><td>{@code Result<T>}</td>
 *       <td>带元信息的包装，能拿到 token 消耗、用到的检索内容、执行的工具等</td></tr>
 * </table>
 *
 * <h3>关于 @MemoryId</h3>
 * 只有标注了 {@code @MemoryId} 的参数才会参与「多会话隔离」。
 * 框架拿这个值当作 key，去 {@code ChatMemoryProvider} 里取对应会话的记忆。
 * <ul>
 *   <li>标了：每个 memoryId 一份独立记忆，互不干扰；</li>
 *   <li>没标：共用同一份记忆（多用户场景下会串号！）。</li>
 * </ul>
 * 原代码的 {@code chat(String)} 没有 {@code @MemoryId}，但同时配置了
 * {@code chatMemory}，所以它用的是那份共享记忆——单机测试没问题，
 * 多人使用就会互相污染上下文。这是原项目一个隐藏的设计缺陷。
 */
public interface AiCodeHelperService {

    /**
     * 简单同步问答（无会话记忆隔离）。
     *
     * <p>适合一次性的独立提问，比如测试类中的调用、或不需要上下文的场景。
     *
     * <p>方法只有一个 String 参数且标注了 {@code @UserMessage}，
     * 框架会把它作为用户消息发送。这里显式写上注解是为了让意图清晰——
     * 不写也能工作（框架会猜），但可读性差很多。
     *
     * @param userMessage 用户问题
     * @return 模型回复文本
     */
    @SystemMessage(fromResource = "system-prompt.txt")
    String chat(@UserMessage String userMessage);

    /**
     * 结构化输出：让模型直接返回一个 Java 对象。
     *
     * <h4>这比返回 String 再自己解析好在哪里？</h4>
     * 传统做法是「让模型输出 JSON → 自己用 Jackson 解析」，但模型经常不听话：
     * 会在 JSON 外面包上 ```json 代码块、会加解释性文字、字段名大小写不一致。
     * 结构化输出由框架统一处理：把返回类型转成 JSON Schema 一并发给模型
     * （现代模型服务商原生支持结构化输出约束），再按 Schema 反序列化，
     * 可靠性远高于自己写正则去抠。
     *
     * <h4>为什么用 record 而不是普通类？</h4>
     * Java 16+ 的 record 只声明字段就能用，天然不可变、自带
     * equals/hashCode/toString/getter，非常适合做「数据载体」（DTO）。
     * 用 Lombok 的 {@code @Data} 也可以，但 record 更简洁且语义更准确——
     * 模型返回的结果本就不该被修改。
     *
     * <h4>字段类型选择的影响</h4>
     * {@code List<String> suggestionList} 告诉框架这里是一个字符串数组，
     * 会体现在生成的 JSON Schema 里，引导模型输出数组而不是一段拼接的文本。
     * <b>返回类型写得越精确，模型输出越规整</b>，这是结构化输出的实用技巧。
     *
     * @param userMessage 用户的学习需求描述
     * @return 包含称呼与建议列表的结构化对象
     */
    @SystemMessage(fromResource = "system-prompt.txt")
    Report chatForReport(@UserMessage String userMessage);

    /**
     * 学习建议的结构化载体。
     *
     * <p>定义在接口内部（嵌套 record）是有意为之：它和这个接口强相关，
     * 只有这里用得到，放在一起便于阅读，也不会污染包结构。
     * 外部通过 {@code AiCodeHelperService.Report} 引用。
     *
     * @param name           对用户的称呼
     * @param suggestionList 学习建议条目列表
     */
    record Report(String name, List<String> suggestionList) {
    }

    /**
     * 流式对话（SSE 逐字返回），支持多会话记忆。
     *
     * <h4>流式为什么重要？</h4>
     * 大模型生成一段 500 字的回答可能要 5~15 秒。非流式方案下用户盯着空屏等十几秒，
     * 会以为系统卡死了；流式方案下第一个字通常 1 秒内就出现，体感天差地别。
     * 这就是 {@link dev.langchain4j.model.chat.StreamingChatModel} 存在的意义。
     *
     * <h4>为什么用 Flux 而不是回调？</h4>
     * StreamingChatModel 的底层 API 是回调式的（onNext / onComplete / onError）。
     * {@code langchain4j-reactor} 把它适配成了 Reactor 的 {@code Flux}，
     * 这样就能直接作为 Spring MVC 的返回值类型，框架会自动处理
     * 「边生成边 flush 到 HTTP 响应」的细节，代码干净得多。
     * 如果不用 Flux，就得自己起线程 + 持有 HttpServletResponse 手动 flush，麻烦且易错。
     *
     * <h4>@MemoryId 的作用</h4>
     * {@code memoryId} 是会话标识（前端存在 localStorage 里）。
     * 框架据此为每个会话维护独立的 {@code MessageWindowChatMemory}，
     * 实现「你聊你的、我聊我的」。传不同的 memoryId 就是不同的会话。
     *
     * <p><b>注意一个参数位置上的细节</b>：{@code @MemoryId} 标注的参数
     * 不会作为用户消息内容发送给模型，它只是用来定位记忆的 key。
     * 真正发给模型的是 {@code @UserMessage} 标注的那个参数。
     *
     * @param memoryId    会话标识，用于隔离不同用户的记忆
     * @param userMessage 用户问题
     * @return 逐段推送的回答内容流
     */
    @SystemMessage(fromResource = "system-prompt.txt")
    Flux<String> chatStream(@MemoryId int memoryId, @UserMessage String userMessage);
}
