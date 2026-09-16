package com.yupi.aicodehelper.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 「手写版」对话实现 —— 这是理解 AI Service 之前必须看懂的一课。
 *
 * <h3>这个类为什么存在？</h3>
 * 本项目的正式对话走 {@link AiCodeHelperService}（AI Service 声明式写法），
 * 几行注解就搞定了记忆、RAG、工具调用。但那种写法把细节全藏起来了，
 * 不利于理解底层到底发生了什么。本类保留<b>最原始的一层 API</b>，
 * 手工完成「构建消息 → 调用模型 → 取出文本」的全过程，
 * 目的是让人看清 AI Service 在背后替你做了哪些事。
 *
 * <h3>对比：两条路线的差异</h3>
 * <table border="1">
 *   <caption>手工调用 vs AI Service</caption>
 *   <tr><th></th><th>本类（手工）</th><th>AiCodeHelperService（声明式）</th></tr>
 *   <tr><td>代码量</td><td>每加一个能力都要改调用逻辑</td><td>加个注解或配置项即可</td></tr>
 *   <tr><td>会话记忆</td><td>要自己维护 List&lt;ChatMessage&gt; 并保证不超上下文</td>
 *       <td>{@code .chatMemoryProvider(...)} 一行</td></tr>
 *   <tr><td>RAG</td><td>要自己检索、自己拼提示词</td><td>{@code .contentRetriever(...)} 一行</td></tr>
 *   <tr><td>工具调用</td><td colspan="1">要自己解析模型的工具请求、反射执行、回传结果</td>
 *       <td>{@code .tools(...)} 一行</td></tr>
 *   <tr><td>结构化输出</td><td>要自己把 JSON 解析成对象</td><td>改方法返回类型即可</td></tr>
 *   <tr><td>适合场景</td><td>需要极致控制、调试框架行为、理解原理</td>
 *       <td>绝大多数业务场景</td></tr>
 * </table>
 * <p><b>结论</b>：业务代码一律用 AI Service；本类这类手工写法主要用于
 * 「排查框架问题」和「学习原理」——当 AI Service 的结果不符合预期时，
 * 你可以用这种方式绕开所有封装，验证是不是模型本身的问题。
 *
 * <h3>关于 ChatModel 与 StreamingChatModel</h3>
 * <ul>
 *   <li>{@link ChatModel}——同步模型，调一次等一次，返回完整结果；</li>
 *   <li>{@code StreamingChatModel}——流式模型，边生成边回调。
 *       两者是不同接口，不能互相替代（见 {@code AiCodeHelperServiceFactory}
 *       里为什么同时注入两个模型）。</li>
 * </ul>
 */
@Service // 标记为 Spring 组件，交给容器管理生命周期（不写这个注解，@Resource 就注不进去）
@Slf4j
public class AiCodeHelper {

    /**
     * 同步对话模型。
     *
     * <p>字段名 {@code qwenChatModel} 很关键：{@code @Resource} 默认按<b>名称</b>匹配，
     * 它会去找名为 "qwenChatModel" 的 Bean。这个名字由 DashScope Starter
     * 自动装配时指定，写错会报 {@code NoSuchBeanDefinitionException}。
     *
     * <p>（对比：{@code @Autowired} 默认按<b>类型</b>匹配，与字段名无关——
     * 这是两个注解最容易搞混的区别。容器里同类型只有一个 Bean 时两者效果一样，
     * 一旦有多个就需要 {@code @Qualifier} 或改用 @Resource 按名匹配。）
     */
    @Resource
    private ChatModel qwenChatModel;

    /**
     * 系统预设消息（System Prompt）。
     *
     * <h4>System / User / Assistant 三种消息的角色分工</h4>
     * <ul>
     *   <li><b>System</b>——设定模型的身份、语气、边界。用户看不到，
     *       但优先级最高，是「人设」的来源；</li>
     *   <li><b>User</b>——用户说的话；</li>
     *   <li><b>Assistant</b>（代码中的 AiMessage）——模型说过的话。
     *       把它们按顺序组成列表发给模型，模型就能「记得」之前的对话，
     *       这就是对话记忆的最底层原理。</li>
     * </ul>
     *
     * <h4>为什么用文本块（Text Block）而不是字符串拼接？</h4>
     * Java 15+ 的 {@code """} 文本块可以保留换行和缩进，写多行提示词可读性远好于
     * {@code "第一行\n" + "第二行\n"}。提示词本质上是有格式要求的文档，
     * 用文本块能和最终送给模型的形态保持一致。
     *
     * <p>注意：这个常量与 {@code src/main/resources/system-prompt.txt} 内容重复。
     * 实际项目中应只保留一处（推荐用 txt 文件，便于非技术人员修改），
     * 这里保留两份是为了让本类「不依赖外部资源、能独立运行」，方便单独调试。
     */
    private static final String SYSTEM_MESSAGE = """
        你是编程领域的小助手，帮助用户解答编程学习和求职面试相关的问题，并给出建议。重点关注 4 个方向：
        1. 规划清晰的编程学习路线
        2. 提供项目学习建议
        3. 给出程序员求职全流程指南（比如简历优化、投递技巧）
        4. 分享高频面试题和面试技巧
        请用简洁易懂的语言回答，助力用户高效学习与求职。
        """;

    /**
     * 最基础的对话方法：显式构建 System + User 两条消息后调用模型。
     *
     * <p>执行步骤对应 AI Service 内部做的事情（只是这里由我们手工完成）：
     * <pre>
     *   ① 构建 SystemMessage（人设）
     *   ② 构建 UserMessage（用户输入）
     *   ③ 调 chat() 发送请求，拿回 ChatResponse
     *   ④ 从 ChatResponse 中取出 AiMessage，再取纯文本
     * </pre>
     *
     * <p><b>注意这个方法没有会话记忆</b>——每次调用都是全新的一问一答，
     * 模型不记得上一轮说了什么。要实现记忆，就得自己维护
     * {@code List<ChatMessage>} 并在每次请求时全量传入（同时还得分神处理
     * 「列表太长超出上下文」的问题）。对比 {@link AiCodeHelperService} 里
     * 一个 {@code @MemoryId} 注解就搞定，能直观感受到框架的价值。
     *
     * <h4>为什么调用 chat(...) 时要显式取 aiMessage().text()？</h4>
     * 因为 ChatResponse 是<b>包装对象</b>，除了回答内容，还携带了 token 用量、
     * 结束原因（正常结束 / 长度截断 / 内容过滤）、工具调用请求等元信息。
     * 只取 text() 就丢掉了这些信息——生产环境中记录 token 用量对成本核算
     * 至关重要（见下方注释里可扩展的位置）。
     *
     * @param message 用户消息
     * @return 模型回复的纯文本
     */
    public String chat(String message) {
        // ① 构建系统预设消息：让模型知道「你是谁、该以什么风格回答」
        SystemMessage systemMessage = SystemMessage.from(SYSTEM_MESSAGE);
        // ② 构建用户消息
        UserMessage userMessage = UserMessage.from(message);

        // ③ 发起请求。注意入参是「多个消息」，按顺序构成一次完整对话
        ChatResponse chatResponse = qwenChatModel.chat(systemMessage, userMessage);

        // ④ 从响应中取出 AI 消息
        AiMessage aiMessage = chatResponse.aiMessage();
        log.info("ai回复：{}", aiMessage.toString());

        // 【可扩展点】这里可以从 chatResponse.tokenUsage() 读取输入/输出 token 数，
        // 累计起来做成本监控。生产环境强烈建议记录，否则账单会是个惊喜

        return aiMessage.text();
    }

    /**
     * 接受 UserMessage 对象的对话方法（为多模态预留的入口）。
     *
     * <h3>为什么单独提供这个方法？—— 因为它支持多模态</h3>
     * {@code chat(String)} 只能传纯文本。而 {@link UserMessage} 可以承载
     * 多种内容（{@code Content}）：
     * <pre>{@code
     *   UserMessage.from(
     *       TextContent.from("这张图里有什么？"),
     *       ImageContent.from(imageUrl)     // 或 base64
     *   )
     * }</pre>
     * 也就是说，一旦方法签名收的是 UserMessage 而不是 String，
     * 调用方就能自由组合「文字 + 图片」这类混合内容，从而支持视觉理解类需求。
     *
     * <p>原代码在方法上方写了「// 多模态」的注释，指的正是这个意思——
     * 它不是一个普通的重载，而是为多模态能力预留的扩展入口。
     * 需要提醒的是：<b>多模态还需模型本身支持</b>（qwen-max 支持图片输入，
     * 但不同版本能力不同），仅改代码是不够的。
     *
     * <p>另一个细节：这个方法<b>没有传 SystemMessage</b>，
     * 意味着模型没有人设约束，回答风格会回归默认。
     * 多模态调用时若仍需要固定人设，应改用
     * {@code UserMessage} 构建带 System 消息的消息列表。
     *
     * @param userMessage 用户消息对象（可为纯文本或多模态组合）
     * @return 模型回复的纯文本
     */
    public String chatWithMessage(UserMessage userMessage) {
        ChatResponse chatResponse = qwenChatModel.chat(userMessage);
        AiMessage aiMessage = chatResponse.aiMessage();
        // 顺带指出原代码的一个小问题：日志用字符串拼接（"AI 输出：" + xxx）
        // 而不是占位符（"AI 输出：{}", xxx）。
        // 字符串拼接会「无条件」执行 toString() 并创建新字符串，
        // 即使日志级别未开启也照做，属于无谓开销；占位符形式则可延迟到真正输出时才求值
        log.info("AI 输出：{}", aiMessage.toString());
        return aiMessage.text();
    }
}
