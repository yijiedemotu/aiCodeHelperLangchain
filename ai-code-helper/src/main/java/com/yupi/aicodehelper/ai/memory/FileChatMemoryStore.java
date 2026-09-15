package com.yupi.aicodehelper.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于本地文件的会话记忆存储（把聊天记录落盘）。
 *
 * <h3>要解决什么问题？</h3>
 * 原项目用 {@code MessageWindowChatMemory.withMaxMessages(10)}，它默认把消息
 * 存在 {@code InMemoryChatMemoryStore} 里——从名字就能看出，<b>数据在内存，服务一重启就没了</b>。
 * 用户在页面上聊了半小时，运维重启一次服务，模型立刻「你是谁我不认识」。
 *
 * <h3>LangChain4j 的记忆分层设计（理解这个才能改对）</h3>
 * 这是很多初学者会混淆的地方，记忆机制被拆成了两层：
 * <pre>
 *   ChatMemory（策略层）              ChatMemoryStore（存储层）
 *   ────────────────────             ──────────────────────
 *   决定「怎么管理」消息：             决定「消息存哪儿」：
 *   · MessageWindowChatMemory           · InMemoryChatMemoryStore（默认，内存）
 *     只保留最近 N 条                    · 本类：FileChatMemoryStore（文件）
 *   · TokenWindowChatMemory               · 第三方：Redis / MySQL / MongoDB 实现
 *     按 token 数而不是条数裁剪
 * </pre>
 * 两者通过 {@code MessageWindowChatMemory.builder().chatMemoryStore(store)} 组合。
 * 所以实现持久化，<b>只需要实现 ChatMemoryStore 这三个方法</b>，
 * 记忆裁剪策略（窗口大小）完全不用动。
 *
 * <h3>为什么用 LangChain4j 自带的序列化工具，而不用自己写 Jackson？</h3>
 * {@link ChatMessage} 是一个带多实现的接口：UserMessage、AiMessage、
 * SystemMessage、ToolExecutionResultMessage……直接交给 Jackson 序列化会丢失类型信息，
 * 反序列化时不知道该还原成哪个子类（经典的 JSON 多态反序列化问题）。
 * LangChain4j 提供了 {@link ChatMessageSerializer} / {@link ChatMessageDeserializer}
 * 专门处理这件事，内部已包含类型标记，直接调用即可。
 *
 * <h3>线程安全说明</h3>
 * {@code ChatMemoryStore} 的实现会被多个会话并发调用（每个 HTTP 请求一条线程）。
 * 本类每次操作都是独立的「读文件 / 写文件」，未共享可变状态，因此是线程安全的；
 * 但要注意<b>同一个 memoryId 的并发写会互相覆盖</b>。生产环境应换成
 * Redis 这类支持原子操作的存储，或对同一 memoryId 加锁。
 */
@Slf4j
public class FileChatMemoryStore implements ChatMemoryStore {

    /** 记忆文件存放的根目录 */
    private final Path baseDir;

    /**
     * @param dir 记忆文件存放目录，不存在会自动创建
     */
    public FileChatMemoryStore(String dir) {
        this.baseDir = Paths.get(dir);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            // 这里选择「启动即失败」而不是「降级为内存存储」：
            // 配置了持久化却静默不生效，是比启动失败更难排查的问题
            throw new IllegalStateException("创建会话记忆目录失败: " + baseDir.toAbsolutePath(), e);
        }
        log.info("会话记忆持久化已启用，存储目录: {}", baseDir.toAbsolutePath());
    }

    /**
     * 读取某个会话的全部历史消息。
     *
     * <p>ChatMemory 在每轮对话开始前都会调这个方法，把历史拼进提示词。
     *
     * @param memoryId 会话标识（就是 Controller 里的 memoryId）
     * @return 历史消息；文件不存在或读取失败时返回空列表（而不是抛异常）
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        Path file = resolveFile(memoryId);
        if (!Files.exists(file)) {
            // 新会话，没有历史是正常情况，直接返回空列表
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
        } catch (Exception e) {
            // 关键设计：读取失败绝不能让整个对话挂掉。
            // 最坏情况是「这次对话丢了历史上下文」，而不是「用户收到 500 错误」。
            // 同时必须打日志，否则会变成静默故障。
            log.error("读取会话记忆失败，memoryId={}，本次按无历史处理", memoryId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 覆盖写入某个会话的全部消息。
     *
     * <p>注意方法名是 update（全量覆盖）而不是 append（追加）：
     * ChatMemory 负责裁剪窗口，它把「裁剪后的完整列表」交给存储层，
     * 存储层只管原样保存，不需要关心该保留哪几条。这是职责分离的体现。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        Path file = resolveFile(memoryId);
        try {
            Files.createDirectories(baseDir);
            Files.writeString(file, ChatMessageSerializer.messagesToJson(messages), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 同理：写失败只记日志，不让对话中断。
            // 用户这一次的对话记忆不会被保存，但不影响本次回答
            log.error("写入会话记忆失败，memoryId={}", memoryId, e);
        }
    }

    /**
     * 删除某个会话的全部记忆。
     *
     * <p>对应业务上的「清空对话」「退出登录后销毁会话」。
     */
    @Override
    public void deleteMessages(Object memoryId) {
        try {
            Files.deleteIfExists(resolveFile(memoryId));
        } catch (IOException e) {
            log.error("删除会话记忆失败，memoryId={}", memoryId, e);
        }
    }

    /**
     * 把 memoryId 映射为具体的文件路径。
     */
    private Path resolveFile(Object memoryId) {
        return baseDir.resolve(sanitize(String.valueOf(memoryId)) + ".json");
    }

    /**
     * 文件名安全处理。
     *
     * <p>这一步不能省，属于<b>路径穿越（Path Traversal）防护</b>：
     * memoryId 来自外部请求参数，如果直接拼进路径，
     * 攻击者传入 {@code ../../application.yml} 就可能读到甚至覆盖敏感文件。
     * 这里只保留字母、数字、下划线和短横线，其余字符统一替换为下划线。
     */
    private String sanitize(String memoryId) {
        return memoryId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
