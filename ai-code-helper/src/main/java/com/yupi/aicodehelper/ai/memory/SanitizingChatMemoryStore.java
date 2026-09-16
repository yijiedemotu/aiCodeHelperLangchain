package com.yupi.aicodehelper.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆存储装饰器：在消息落库前剔掉「RAG 检索脚手架」。
 *
 * <h2>它修的是什么问题？</h2>
 * 这是本项目在实测中发现的<b>一个隐蔽但代价很高的缺陷</b>，先看证据。
 * 用流式接口问一句「请介绍一下 ZGC 垃圾回收器」（memoryId=99002），
 * 然后打开落盘的记忆文件 {@code chat-data/memory/99002.json}，内容是：
 * <pre>
 * [{"text":"你是编程领域的小助手……","type":"SYSTEM"},
 *  {"contents":[{"text":"请介绍一下 ZGC 垃圾回收器，200 字以内。\n\n
 *                Answer using the following information:\n
 *                Java 编程学习路线.md\n- ZGC：[https://juejin.cn/...]……","type":"TEXT"}],
 *   "type":"USER"},
 *  {"text":"ZGC（Z Garbage Collector）是 Java 平台的一种……","type":"AI"}]
 * </pre>
 * 注意那条 USER 消息——它<b>根本不是用户说的</b>。用户只说了第一句，
 * 后面 {@code Answer using the following information:} 及整段知识库文档，
 * 是 {@code DefaultContentInjector}（RAG 的「内容注入器」）在发送前拼上去的。
 *
 * <h2>为什么这会成为问题？</h2>
 * LangChain4j 把「发给模型的消息」原样交给了记忆层保存。于是：
 * <ol>
 *   <li><b>记忆文件体积失控</b>——实测单轮对话的记忆文件就有 2.6 KB，
 *       其中绝大部分是检索到的文档原文。聊几十轮就是几百 KB，
 *       而 {@code FileChatMemoryStore} 每轮都要全量读写一次。</li>
 *   <li><b>token 成本被放大</b>——下一轮对话时，这整段文档会作为<b>历史消息</b>
 *       再次发给模型。而且检索是每轮都做的，于是同一段文档被反复叠加进上下文，
 *       上下文像滚雪球一样变大，而计费是按输入 token 算的。</li>
 *   <li><b>语义被污染</b>——模型会看到「自己」或「用户」曾经说过
 *       {@code Answer using the following information:} 这种指令性文字。
 *       多轮之后，模型可能把它当成一种对话范式去模仿，输出变得古怪。</li>
 * </ol>
 * 从设计上讲，<b>检索到的资料属于「临时上下文」，不属于「对话历史」</b>。
 * 历史里应该只留用户真正说过的话。这个类就是在存储边界上把这条界线划清楚。
 *
 * <h2>为什么用「装饰器」而不是改 FileChatMemoryStore？</h2>
 * 因为这个问题与「存到哪儿」无关：不论是存文件还是存内存，
 * 检索脚手架都不该进历史。做成装饰器后：
 * <pre>
 *   new SanitizingChatMemoryStore(new FileChatMemoryStore(dir))     // 持久化 + 净化
 *   new SanitizingChatMemoryStore(new InMemoryChatMemoryStore())    // 内存   + 净化
 * </pre>
 * 两种存储都能复用同一份净化逻辑，也符合<b>单一职责</b>——
 * {@code FileChatMemoryStore} 只管「怎么存」，本类只管「存什么」。
 *
 * <h2>为什么净化要放在「写入」而不是「读取」时做？</h2>
 * 放在写入侧，脏数据根本进不了存储，读取路径零开销；
 * 若放在读取侧，每轮对话都要把所有历史消息重新扫一遍做字符串替换，纯属浪费。
 * 更重要的是：写入侧净化后，<b>内存型存储在同一个进程内也不会被污染</b>——
 * 因为 {@code MessageWindowChatMemory} 每轮都会从 store 重新读出消息。
 *
 * <h2>边界与风险（诚实说明）</h2>
 * <ul>
 *   <li>本类依赖 {@code DefaultContentInjector} 的提示词模板。该模板来自
 *       LangChain4j 内部，理论上可能随版本变化。因此标记串做成了
 *       构造参数（{@link #DEFAULT_INJECTION_MARKER} 为默认值），
 *       一旦模板改变，只需换一个标记串，无需改动其他代码。
 *       同时这段代码是<b>只做减法、不做加法</b>的：万一标记串没匹配上，
 *       最坏结果只是「没净化成功」（退回原来的行为），
 *       绝不会把用户真实说的话误删——截断点在标记串之后，标记串之前原样保留。</li>
 *   <li>如果你自定义了 {@code ContentInjector}，请把它的注入标记串传给构造方法。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * 本类无可变状态，所有字段都是 final，且完全委托给底层存储，
 * 因此线程安全性等同于被包装的存储。
 */
@Slf4j
public class SanitizingChatMemoryStore implements ChatMemoryStore {

    /**
     * 默认要剔除的注入标记。
     *
     * <p>取自 {@code DefaultContentInjector} 的默认提示词模板，注入后的形态为：
     * <pre>
     * 用户原话
     *
     * Answer using the following information:
     * 命中片段1
     * 命中片段2
     * </pre>
     * 所以「标记串及其之后的所有内容」就是需要剔除的部分。
     */
    public static final String DEFAULT_INJECTION_MARKER = "\n\nAnswer using the following information:";

    /** 被包装的真实存储（文件 / 内存 / Redis……） */
    private final ChatMemoryStore delegate;

    /** 注入标记串，其后的内容会被剔除 */
    private final String injectionMarker;

    /**
     * 使用默认注入标记构造。
     *
     * @param delegate 真实存储
     */
    public SanitizingChatMemoryStore(ChatMemoryStore delegate) {
        this(delegate, DEFAULT_INJECTION_MARKER);
    }

    /**
     * @param delegate        真实存储
     * @param injectionMarker 自定义的注入标记串
     */
    public SanitizingChatMemoryStore(ChatMemoryStore delegate, String injectionMarker) {
        this.delegate = delegate;
        this.injectionMarker = injectionMarker;
    }

    /**
     * 读取历史消息 —— 无需处理，直接委托。
     *
     * <p>因为脏数据在写入时就已经被清理干净了，读取路径上不做任何加工，
     * 保证每轮对话的读取开销恒定。
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return delegate.getMessages(memoryId);
    }

    /**
     * 写入历史消息 —— 先净化，再委托。
     *
     * <p>注意这里必须保持 {@code ChatMemory} 交给我们的<b>消息顺序与条数</b>不变：
     * 我们只是把某条用户消息的文本截短，不增不减任何一条消息。
     * 一旦改变条数，{@code MessageWindowChatMemory} 的窗口计算就会错位。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        delegate.updateMessages(memoryId, stripRetrievalArtifacts(memoryId, messages));
    }

    /** 删除会话 —— 直接委托 */
    @Override
    public void deleteMessages(Object memoryId) {
        delegate.deleteMessages(memoryId);
    }

    /**
     * 剔除用户消息里由 RAG 注入的内容。
     *
     * @param memoryId 仅用于打日志，便于定位是哪个会话触发了净化
     * @param messages 原始消息列表
     * @return 净化后的消息列表
     */
    private List<ChatMessage> stripRetrievalArtifacts(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        List<ChatMessage> cleaned = new ArrayList<>(messages.size());
        boolean changed = false;

        for (ChatMessage message : messages) {
            // 只有「用户消息」才可能被 RAG 注入。AI 消息、系统消息、工具消息都原样保留
            if (!(message instanceof UserMessage userMessage)) {
                cleaned.add(message);
                continue;
            }

            // hasSingleText() 为 false 表示这是多模态消息（文字 + 图片等）。
            // 这种情况下 singleText() 会抛异常，且多模态消息本就不走文本注入，直接放行
            if (!userMessage.hasSingleText()) {
                cleaned.add(message);
                continue;
            }

            String text = userMessage.singleText();
            if (text == null) {
                cleaned.add(message);
                continue;
            }

            // lastIndexOf 而不是 indexOf：万一用户自己打出了同样的字串，
            // 我们要剔除的是框架拼在末尾的那一段，取最后一个匹配最稳妥
            int markerIndex = text.lastIndexOf(injectionMarker);
            if (markerIndex < 0) {
                // 没有注入痕迹（例如本轮检索没命中任何片段），原样保留
                cleaned.add(message);
                continue;
            }

            String original = text.substring(0, markerIndex);
            // 用 UserMessage.from(String) 重建消息，而不是修改原对象——
            // LangChain4j 的消息对象视为不可变，重建是唯一安全的做法
            cleaned.add(UserMessage.from(original));
            changed = true;

            log.debug("已净化会话记忆中的 RAG 注入内容：memoryId={}，剔除 {} 字符（{} → {}）",
                    memoryId, text.length() - original.length(), text.length(), original.length());
        }

        if (changed) {
            log.info("会话记忆净化完成：memoryId={}，已移除检索注入内容，避免历史被文档原文污染", memoryId);
        }
        return cleaned;
    }
}
