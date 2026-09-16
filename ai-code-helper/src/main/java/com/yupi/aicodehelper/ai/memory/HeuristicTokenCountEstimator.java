package com.yupi.aicodehelper.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;

/**
 * 轻量 token 估算器 —— 让 {@code TokenWindowChatMemory} 能够「按 token 裁剪历史」。
 *
 * <h2>为什么需要它？</h2>
 * 本项目原先用 {@code MessageWindowChatMemory.maxMessages(10)}，即「最多留 10 条消息」。
 * 这个策略有个隐蔽的缺陷：<b>「条数」完全约束不了「长度」</b>。
 * <pre>
 *   10 条短寒暄        ≈   800 token   —— 毫无压力
 *   10 条含代码的回答   ≈ 15000 token   —— 可能直接顶爆上下文窗口
 * </pre>
 * 而模型上下文窗口是按 <b>token</b> 计的。一旦超出：
 * <ul>
 *   <li>轻则系统提示词、RAG 检索结果被挤掉，回答质量断崖下跌；</li>
 *   <li>重则上游直接返回 400（超出最大输入长度），用户看到的是报错而不是回答。</li>
 * </ul>
 * 换成 {@code TokenWindowChatMemory} 后，窗口约束从「条数」变成「token 数」，
 * 这才是与模型真实限制对齐的度量。
 *
 * <h2>为什么不直接用官方精确的 {@code QwenTokenCountEstimator}？</h2>
 * DashScope 模块确实提供了 {@code QwenTokenCountEstimator}
 * （{@code dev.langchain4j.community.model.dashscope.QwenTokenCountEstimator}），
 * 它基于真实分词表，结果精确。但它需要传入 {@code apiKey} 与 {@code modelName} 构造，
 * 于是本类的使用者（记忆装配）就必须依赖密钥配置 ——
 * 而「裁剪窗口」这件事在<b>没有密钥、纯本地跑测试</b>时也应该能工作。
 *
 * <p>因此这里实现一个<b>不依赖任何外部资源的字符类启发式估算</b>：
 * <table border="1">
 *   <caption>本估算器的经验系数（来源于对中文/英文文本的通用观察）</caption>
 *   <tr><th>字符类别</th><th>经验值</th><th>说明</th></tr>
 *   <tr><td>中日韩表意文字</td><td>1 字 ≈ 1 token</td>
 *       <td>中文分词后基本是「一字一 token」，这是最主要的部分</td></tr>
 *   <tr><td>ASCII 可见字符</td><td>4 字符 ≈ 1 token</td>
 *       <td>英文单词平均 4~5 个字母被切成一个 token</td></tr>
 *   <tr><td>其他（emoji、罕见符号）</td><td>1 字符 ≈ 1 token</td>
 *       <td>通常被拆成多个字节级 token，按 1 估是保守的</td></tr>
 * </table>
 *
 * <h2>估算不准会有问题吗？—— 不会，这是有意的取舍</h2>
 * 估算器唯一的用途是<b>决定裁剪掉多少条历史</b>，而不是计费。
 * 它偏保守（宁可多裁一点）的后果仅仅是「少记住半轮对话」；
 * 而它偏乐观的后果是「可能超出窗口」——那才是真问题。
 * 所以本实现刻意在可疑处向上取整（{@code Math.ceil}），即<b>宁可高估</b>。
 *
 * <p>如果将来需要精确计费口径，把本类替换成
 * {@code QwenTokenCountEstimator.builder().apiKey(...).modelName("qwen-max").build()}
 * 即可，{@code TokenWindowChatMemory} 那边的代码一个字都不用改
 * ——这就是面向接口（{@link TokenCountEstimator}）编程的好处。
 *
 * <h2>线程安全</h2>
 * 本类无可变状态，纯函数式，可安全地被所有会话共享。
 */
public class HeuristicTokenCountEstimator implements TokenCountEstimator {

    /**
     * 每条消息的固定开销（token）。
     *
     * <p>模型看到的不只是正文。每条消息在底层还要附带角色标记
     * （{@code <|im_start|>user} / {@code <|im_end|>} 这类特殊 token）。
     * 如果只统计正文字数，短消息密集的会话会被系统性低估。
     * 取 4 是一个常见的经验值。
     */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    /** ASCII 字符平均多少个构成一个 token */
    private static final double ASCII_CHARS_PER_TOKEN = 4.0;

    @Override
    public int estimateTokenCountInText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int cjkCount = 0;    // 中日韩文字：约 1 字 1 token
        int asciiCount = 0;  // ASCII：约 4 字符 1 token
        int otherCount = 0;  // 其他：按 1 字符 1 token 保守估算

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 128) {
                asciiCount++;
            } else if (isCjk(c)) {
                cjkCount++;
            } else {
                otherCount++;
            }
        }

        // 向上取整而不是四舍五入：宁可高估（多裁几条历史），也不要低估（撑爆上下文）
        int asciiTokens = (int) Math.ceil(asciiCount / ASCII_CHARS_PER_TOKEN);
        return cjkCount + asciiTokens + otherCount;
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        return estimateTokenCountInText(extractText(message)) + PER_MESSAGE_OVERHEAD;
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimateTokenCountInMessage(message);
        }
        return total;
    }

    /**
     * 从各种消息类型中取出可统计的文本。
     *
     * <h4>为什么必须区分消息类型，而不能统一 {@code toString()}？</h4>
     * {@code ChatMessage} 是一个带多实现的接口（UserMessage / AiMessage /
     * SystemMessage / ToolExecutionResultMessage / 未来的多模态消息）。
     * 直接 {@code toString()} 会把类名、字段名、JSON 结构一起算进去，
     * 让估算值虚高好几倍，窗口会被裁得过分激进。
     *
     * <p>对多模态消息（文字 + 图片），这里只能统计到文本部分，
     * 图片的 token 开销（通常按分辨率折算，一张图数百到上千 token）无法从
     * 文本侧推断。这意味着<b>含图片的会话会被低估</b>——
     * 使用多模态能力时应改用能感知图片的精确估算器，或把 maxTokens 调小一些。
     */
    private String extractText(ChatMessage message) {
        // Java 21 的模式匹配 instanceof：一次完成「类型判断 + 强转 + 绑定变量」
        if (message instanceof UserMessage userMessage) {
            // 多模态消息没有 singleText()，取 contents 的字符串形式作为近似
            return userMessage.hasSingleText() ? userMessage.singleText() : String.valueOf(userMessage.contents());
        }
        if (message instanceof AiMessage aiMessage) {
            // 纯工具调用的 AiMessage 可能没有文本，text() 会返回 null
            return aiMessage.text() == null ? "" : aiMessage.text();
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        }
        if (message instanceof ToolExecutionResultMessage toolMessage) {
            return toolMessage.text();
        }
        // 兜底：将来框架新增消息类型时，至少不会抛异常，只是估算偏粗略
        return message.toString();
    }

    /**
     * 判断是否为「一字一 token」的表意文字（含中文标点与全角符号）。
     *
     * <p>把全角标点（{@code U+3000}~{@code U+303F}、{@code U+FF00}~{@code U+FFEF}）
     * 也归入此类是刻意的：中文文本里的「，。！？」同样各占一个 token，
     * 若按 4 字符 1 token 去算，中文段落的估算会明显偏低。
     */
    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // CJK 统一表意文字（常用汉字）
                || (c >= 0x3400 && c <= 0x4DBF)  // CJK 扩展 A（生僻字）
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点（、。「」等）
                || (c >= 0xFF00 && c <= 0xFFEF)  // 全角字符（，。！？等）
                || (c >= 0x3040 && c <= 0x30FF)  // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF); // 韩文谚文
    }
}
