package com.yupi.aicodehelper.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
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
 *   <tr><td>{@code TokenStream}</td>
 *       <td>流式，但推送的是<b>结构化事件</b>而非纯文本：
 *           逐字输出、检索到的片段、工具调用过程、完整响应各走各的回调</td></tr>
 * </table>
 *
 * <h3>关于 @MemoryId —— 本项目最容易踩的坑</h3>
 * 只有标注了 {@code @MemoryId} 的参数才会参与「多会话隔离」。
 * 框架拿这个值当作 key，去 {@code ChatMemoryProvider} 里取对应会话的记忆。
 * <ul>
 *   <li><b>标了</b>：每个 memoryId 一份独立记忆，互不干扰；</li>
 *   <li><b>没标</b>：一律落到名为 {@code "default"} 的那一份记忆上——
 *       而这个 default 桶是<b>进程级的、所有用户共用的</b>。</li>
 * </ul>
 * 换句话说：<b>在一个挂了记忆的 AiService 里，任何没有 {@code @MemoryId} 的方法
 * 都是一个「公共聊天室」</b>。原项目就踩了这个坑——
 * {@code chat(String)} 与 {@code chatForReport(String)} 都没有 {@code @MemoryId}，
 * 导致不同用户、不同接口的请求被塞进同一段对话。
 * <p>本项目已按「有状态 / 无状态」把服务切开：
 * <ul>
 *   <li>{@link AiCodeHelperService#chatStream} —— 带 {@code @MemoryId}，
 *       每个会话独立记忆（唯一真正需要记忆的方法）；</li>
 *   <li>{@link GuardedAssistant#chat}、{@link ReportAssistant#chatForReport}
 *       —— 无状态接口，压根不配记忆，从根上杜绝串号。</li>
 * </ul>
 * 剩下的 {@link #chat(String)} 共享 default 桶，但它<b>仅供测试与本地调试</b>，
 * 没有对外暴露的 HTTP 入口；如需上生产，请参照 {@link ReportAssistant} 的方式
 * 拆到独立接口，或补上 {@code @MemoryId} 参数。
 */
public interface AiCodeHelperService {

    /**
     * 简单同步问答。
     *
     * <p><b>⚠ 注意它没有 {@code @MemoryId}：</b>调用它会读写那份共用的
     * {@code "default"} 记忆。因此这个方法<b>仅限测试与本地调试</b>——
     * 单元测试里靠它验证「多轮上下文确实生效」（见
     * {@code AiCodeHelperServiceTest#chatWithMemory}），
     * 但绝不能把它暴露成面向多用户的 HTTP 接口，否则就是公共聊天室。
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

    /*
     * ------------------------------------------------------------------------
     *  这里原本还有一个 chatForReport 方法（结构化输出）和嵌套的 Report record，
     *  实测发现它们造成了「跨用户记忆串号」，已迁移到独立接口 ReportAssistant。
     *
     *  原因简述：记忆是挂在 AiService 级别的，一个接口一挂就是全接口生效。
     *  而 chatForReport 没有 @MemoryId 参数，于是它和同样没有 @MemoryId 的
     *  GuardedAssistant.chat 一起，共用了一个名为 "default" 的记忆桶——
     *  所有用户、所有接口的请求都被塞进同一段对话，构成真实的隐私泄露。
     *
     *  完整分析见 ReportAssistant 的类注释；记忆分层的原理见
     *  AiCodeHelperServiceFactory#chatMemoryProvider。
     *
     *  留下的 ChatMemoryProvider 只服务下面的 chatStream：它带 @MemoryId，
     *  每个会话一份独立记忆，这才是符合预期的用法。
     * ------------------------------------------------------------------------
     */

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

    /**
     * 带元信息的同步问答（返回 {@link Result}）。
     *
     * <h3>它和 {@link #chatStream} 的关系：同一件事，两种「颗粒度」</h3>
     * 两者都是「带会话记忆的问答」，区别只在于<b>返回什么</b>：
     * <pre>
     *   chatStream   返回 Flux&lt;String&gt;  —— 只有回答文本，追求「快出字」
     *   chatWithMeta 返回 Result&lt;String&gt; —— 回答 + 一整套元信息，追求「可观测」
     * </pre>
     * 流式接口的元信息是被<b>丢弃</b>的：框架一边推字一边把 token 用量、
     * 检索命中、工具调用记在内部的响应对象里，而 {@code Flux<String>} 这个返回类型
     * 只允许我们把纯文本交出去。想看这些信息，就必须换一个能承载它们的返回类型。
     *
     * <h3>Result&lt;T&gt; 里到底有什么？</h3>
     * <table border="1">
     *   <caption>Result 的各个字段及其用途</caption>
     *   <tr><th>方法</th><th>内容</th><th>实际用途</th></tr>
     *   <tr><td>{@code content()}</td><td>回答本体（这里的 T 是 String）</td>
     *       <td>正常返回给用户</td></tr>
     *   <tr><td>{@code tokenUsage()}</td><td>输入 / 输出 / 总 token 数</td>
     *       <td><b>成本核算</b>——按 token 计费，这是唯一的账单来源</td></tr>
     *   <tr><td>{@code sources()}</td><td>本次实际命中的 RAG 片段（Content 列表）</td>
     *       <td><b>RAG 效果评估</b>——能看见「到底检索到了什么」，
     *           并据此给用户做引用溯源（显示「依据：xxx.md」）</td></tr>
     *   <tr><td>{@code finishReason()}</td><td>STOP / LENGTH 等结束原因</td>
     *       <td>识别「回答被长度截断」这类静默故障</td></tr>
     *   <tr><td>{@code toolExecutions()}</td><td>执行过的工具及其结果</td>
     *       <td>验证模型是否真的调用了工具，而不是凭记忆编造</td></tr>
     * </table>
     * <p>这四项信息在升级前是<b>完全拿不到</b>的——原项目里 {@code AiCodeHelper}
     * 有一个「【可扩展点】可以从 chatResponse.tokenUsage() 读 token 数」的注释，
     * {@code Result<T>} 就是那个扩展点的<b>声明式写法</b>：不改一行流程代码，
     * 只把返回类型从 {@code String} 换成 {@code Result<String>}。
     *
     * <h3>为什么它也是有状态的？</h3>
     * 带 {@code @MemoryId}，与 {@code chatStream} 共用同一套会话记忆——
     * 用户在流式接口里聊的内容，切到元信息接口追问时上下文是连续的。
     * 这正是它必须定义在 {@link AiCodeHelperService}（有状态服务）里、
     * 而不能放进无状态的 {@link ReportAssistant} 的原因。
     *
     * <p><b>代价说明</b>：它是<b>同步</b>的，用户必须等模型生成完才能拿到结果，
     * 首字延迟体验不如流式。所以它是「给运维和调试用的口子」，
     * 而不是替代流式接口的日常通道。
     *
     * @param memoryId    会话标识，用于隔离不同会话的记忆
     * @param userMessage 用户问题
     * @return 回答内容 + token 用量 + 检索来源 + 工具调用记录
     */
    @SystemMessage(fromResource = "system-prompt.txt")
    Result<String> chatWithMeta(@MemoryId int memoryId, @UserMessage String userMessage);

    /**
     * 结构化流式对话（返回 {@link TokenStream}）。
     *
     * <h3>它和 {@link #chatStream}（Flux）差在哪？</h3>
     * 两者都是流式，但<b>信息密度完全不同</b>：
     * <pre>
     *   Flux&lt;String&gt;   —— 一条只装文本的管道。
     *                     「正在检索知识库」「正在查面试鸭」这些过程
     *                     在框架内部发生，到不了调用方。
     *
     *   TokenStream   —— 一条「带类型的多路事件」管道，可以分别订阅：
     *                     onPartialResponse   逐字文本
     *                     onPartialThinking   思考过程（推理模型才有）
     *                     onRetrieved         本次检索到的片段  ⭐
     *                     onToolExecuted      工具执行结果      ⭐
     *                     onCompleteResponse  完整响应（含 token 用量）
     *                     onError             异常
     * </pre>
     * 对一个面向用户的 AI 产品，这个差别直接决定体验：用 {@code Flux} 时，
     * 用户提问后只能看着光标闪烁；用 {@code TokenStream} 时，
     * 界面可以显示「正在检索知识库…」「正在查询面试题…」，
     * 让等待变得可解释——<b>用户能忍受慢，但不能忍受不知道在干什么</b>。
     *
     * <h3>使用方式与传统回调 API 的区别</h3>
     * 它是<b>链式注册回调 + 显式启动</b>的：
     * <pre>{@code
     *   tokenStream
     *       .onPartialResponse(text -> ...)   // 注册，此时还没开始
     *       .onCompleteResponse(resp -> ...)
     *       .onError(err -> ...)
     *       .start();                          // ⭐ 这一行才真正发起调用
     * }</pre>
     * <b>{@code start()} 不能漏</b>，也不能在注册回调之前调用——
     * 漏掉它表现为「接口毫无反应、不报错」，是使用 TokenStream 最常见的坑。
     *
     * <p>另一个诚实的提醒：与 Flux 相比，{@code TokenStream} 目前
     * <b>没有暴露取消/中止的入口</b>。若需要「用户点停止就断开」的能力，
     * 用 {@code chatStream}（Flux 可由 Reactor 感知下游取消）更合适。
     * 两者并存不是冗余，而是各自覆盖不同的取舍。
     *
     * @param memoryId    会话标识，用于隔离不同会话的记忆
     * @param userMessage 用户问题
     * @return 尚未启动的流式管道，注册完回调后必须调用 {@code start()}
     */
    @SystemMessage(fromResource = "system-prompt.txt")
    TokenStream chatStreamEvents(@MemoryId int memoryId, @UserMessage String userMessage);
}
