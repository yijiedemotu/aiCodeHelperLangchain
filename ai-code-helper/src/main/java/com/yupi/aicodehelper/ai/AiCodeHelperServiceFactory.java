package com.yupi.aicodehelper.ai;

import com.yupi.aicodehelper.ai.guardrail.InputLengthGuardrail;
import com.yupi.aicodehelper.ai.guardrail.SensitiveWordOutputGuardrail;
import com.yupi.aicodehelper.ai.memory.FileChatMemoryStore;
import com.yupi.aicodehelper.ai.tools.InterviewQuestionTool;
import com.yupi.aicodehelper.config.AiHelperProperties;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrails.PatternBasedPromptInjectionGuardrail;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Service 的装配工厂 —— 整个后端最核心的一个类。
 *
 * <h3>它到底在做什么？</h3>
 * {@link AiCodeHelperService} 是一个<b>没有实现的空接口</b>。这个类负责把这个接口
 * 「变成」一个可用的对象：{@code AiServices.builder(接口).xxx().build()} 返回的对象，
 * 是 LangChain4j 在运行时用 <b>Java 动态代理</b>（{@code Proxy.newProxyInstance}）
 * 生成的实现类。代理对象拦截你的方法调用，替你做掉这些脏活：
 * <pre>
 *   ① 把 String 参数包装成 UserMessage
 *   ② 从记忆中取出历史消息，和本次消息拼成完整对话
 *   ③ 如果有 RAG，先把知识库检索结果塞进提示词
 *   ④ 把工具清单一起发给模型（模型可能要求调用工具，代理负责执行并回传结果）
 *   ⑤ 调用 ChatModel 发 HTTP 请求到大模型
 *   ⑥ 把返回的 AiMessage 转回 String（或按方法返回类型做结构化解析）
 *   ⑦ 把本轮对话写入记忆
 * </pre>
 * 所以这个类里几乎看不到业务逻辑，全是「配置」——这正是 LangChain4j 的设计哲学：
 * <b>声明式地描述你要什么，而不是手写怎么实现</b>。
 *
 * <h3>相对原版做了什么改动（每一项都对应一个真实问题）</h3>
 * <ol>
 *   <li><b>修复编译错误</b>：原文件残留了 {@code import io.modelcontextprotocol.client.McpClient}，
 *       这是 Spring AI 的包。pom 中该依赖已移除，此导入导致编译失败。
 *       本类改用 LangChain4j 自己的 {@link McpToolProvider}。</li>
 *   <li><b>参数外部化</b>：记忆窗口、是否持久化等改从 {@link AiHelperProperties} 读取，
 *       不再硬编码。</li>
 *   <li><b>记忆可持久化</b>：新增 {@link FileChatMemoryStore} 分支，解决「重启即失忆」。</li>
 *   <li><b>接入护栏（Guardrails）</b>：对同步问答接口做输入/输出双向校验。</li>
 *   <li><b>可观测性</b>：记录每次请求的消息条数与工具调用，这是排查线上问题的基础。</li>
 *   <li><b>工具调用轮次上限</b>：防止模型陷入「反复调工具」的死循环，无谓烧钱。</li>
 * </ol>
 */
@Slf4j
@Configuration
public class AiCodeHelperServiceFactory {

    /**
     * 同步对话模型（通义千问 qwen-max）。
     *
     * <p>它由 {@code langchain4j-community-dashscope-spring-boot-starter} 依据
     * application.yml 里的 {@code langchain4j.community.dashscope.chat-model.*}
     * 自动创建并注册到 Spring 容器，我们只负责取用。
     * <p>这里吐槽一下原代码的注释：{@code @Resource} 并不是「使 AiCodeHelper 类能够
     * 使用 ChatModel 的功能」，它的作用仅仅是<b>按名称把容器里的 Bean 赋值给这个字段</b>。
     * 默认按字段名匹配，字段叫 {@code qwenChatModel} 就会去找名为 qwenChatModel 的 Bean。
     */
    @Resource
    private ChatModel qwenChatModel;

    /** 流式对话模型（同样是 starter 自动装配）。用于 SSE 逐字返回 */
    @Resource
    private StreamingChatModel qwenStreamingChatModel;

    /** RAG 检索器，由 {@code RagConfig} 提供 */
    @Resource
    private ContentRetriever contentRetriever;

    /** 自定义配置项 */
    @Resource
    private AiHelperProperties properties;

    /**
     * 用 ObjectProvider 而不是直接 @Resource 注入 MCP 工具提供者。
     *
     * <p>原因：{@code McpConfig} 上的 {@code @ConditionalOnProperty} 会让这个 Bean
     * 在默认配置（未开启 MCP）下<b>根本不存在</b>。此时若直接 {@code @Resource}
     * 注入，Spring 启动就会报「找不到 Bean」而失败。
     * ObjectProvider 允许「有就用，没有就跳过」，这是处理可选依赖的标准做法。
     */
    @Resource
    private ObjectProvider<McpToolProvider> mcpToolProviderProvider;

    /**
     * 主对话服务（支持流式 SSE）。
     *
     * <p>注意这里<b>没有</b>挂载输出护栏，原因见
     * {@link SensitiveWordOutputGuardrail} 的类注释：流式输出是逐块推送的，
     * 等输出护栏拿到完整回复时，内容早已到达浏览器，再做替换没有意义。
     * 流式场景靠输入护栏把关，同步场景才用输出护栏——这是有意的分工，不是遗漏。
     */
    @Bean
    public AiCodeHelperService aiCodeHelperService() {
        // 先拿到挂好通用能力（模型/记忆/RAG/工具/可观测）的构建器，再叠加流式模型。
        // 这种「公共部分抽方法、差异部分各自补」的写法，避免了两个 Bean 之间大量重复配置
        return commonBuilder(AiCodeHelperService.class)
                .streamingChatModel(qwenStreamingChatModel)
                .build();
    }

    /**
     * 带护栏的同步问答服务。
     *
     * <p>单独做成一个 Bean，而不是把护栏加到主服务上，是刻意的设计：
     * <b>护栏会有副作用</b>——输入护栏失败会抛异常直接中断请求，
     * 如果挂在流式接口上，用户会在流已经开始推送之后突然收到错误。
     * 把它隔离到独立的同步接口，行为边界清晰，便于测试和降级。
     */
    @Bean
    public GuardedAssistant guardedAssistant() {
        List<InputGuardrail> inputGuardrails = new ArrayList<>();

        // 护栏一：长度限制。防止超长输入烧 token、挤爆上下文
        inputGuardrails.add(new InputLengthGuardrail(properties.getGuardrail().getMaxInputLength()));

        // 护栏二：提示词注入检测（LangChain4j 1.9.0 新增的内置护栏）
        // 它用一组正则识别「忽略以上所有指令」「你现在扮演」等典型注入话术
        if (properties.getGuardrail().isPromptInjectionCheck()) {
            // 无参构造使用框架内置的默认正则集合；也可传入自定义 List<Pattern>
            inputGuardrails.add(new PatternBasedPromptInjectionGuardrail());
        }

        // 输出护栏：屏蔽词替换（不拒绝回答，只做掩码，兼顾合规与体验）
        List<OutputGuardrail> outputGuardrails = new ArrayList<>();
        if (properties.getGuardrail().isSensitiveWordFilter()) {
            outputGuardrails.add(
                    new SensitiveWordOutputGuardrail(properties.getGuardrail().getBannedWords()));
        }

        log.info("护栏已启用：输入护栏 {} 个，输出护栏 {} 个",
                inputGuardrails.size(), outputGuardrails.size());

        return commonBuilder(GuardedAssistant.class)
                .inputGuardrails(inputGuardrails)
                .outputGuardrails(outputGuardrails)
                .build();
    }

    /**
     * 构建器的公共装配逻辑（被上面两个 Bean 复用）。
     *
     * <h4>为什么用泛型方法 + 链式返回，而不是 void 方法里逐个 set？</h4>
     * 因为 {@code AiServices} 的配置方法语义上是「返回构建器自身」，
     * 用链式返回值串联最稳妥——即便将来某个方法改成返回新对象（不可变构建器风格），
     * 这段代码也不会出错。
     *
     * @param clazz AI Service 接口的 Class
     * @param <T>   接口类型
     * @return 已挂载通用能力的构建器，调用方再补自己的差异配置并 build()
     */
    private <T> AiServices<T> commonBuilder(Class<T> clazz) {
        AiServices<T> builder = AiServices.builder(clazz)
                // ---------- ① 模型 ----------
                .chatModel(qwenChatModel)

                // ---------- ② 会话记忆 ----------
                // chatMemoryProvider 是「每个会话一份记忆」的关键：
                // 它接收 memoryId（方法上由 @MemoryId 标注的参数），返回一份独立的 ChatMemory。
                // 如果不配它而只配 chatMemory，那所有用户会共用同一份记忆——
                // 张三聊完，李四进来会发现模型「认识」一个陌生人，这是严重的串号事故。
                .chatMemoryProvider(chatMemoryProvider())

                // ---------- ③ RAG 检索增强 ----------
                // 挂上它之后，每次提问都会先用问题去向量库检索，把命中的资料片段
                // 自动拼进提示词。业务代码完全无感知
                .contentRetriever(contentRetriever)

                // ---------- ④ 工具调用 ----------
                // 传入的是工具类的实例，框架会扫描其中的 @Tool 方法并生成工具清单
                .tools(new InterviewQuestionTool())

                // 工具调用轮次上限：模型可能反复「调工具→不满意→再调」，
                // 不设上限就是一个烧钱的无底洞。5 轮足以覆盖正常场景
                .maxToolCallingRoundTrips(5)

                // ---------- ⑤ 可观测性 ----------
                // 每次请求打印实际送给模型的消息条数。
                // 这个数字非常有用：如果它意外地大，说明记忆或 RAG 往上下文里
                // 塞了过多内容，是 token 成本失控的常见根因
                .chatRequestTransformer(this::logRequest)

                // 记录每次工具调用的结果，便于回答「模型到底有没有真的调用工具」
                .afterToolExecution(execution ->
                        log.info("工具执行完毕: {}", execution));

        // ---------- ⑥ 可选：MCP 外部工具 ----------
        // 只有在 ai-helper.mcp.enabled=true 时容器里才有这个 Bean
        McpToolProvider mcpToolProvider = mcpToolProviderProvider.getIfAvailable();
        if (mcpToolProvider != null) {
            // toolProvider 与 tools() 的区别：
            //   tools(...)       —— 本地 Java 对象上的 @Tool 方法
            //   toolProvider(...) —— 动态提供工具清单（MCP 就是典型场景，
            //                        工具列表在运行时从远程服务获取，编译期根本不知道有哪些）
            builder.toolProvider(mcpToolProvider);
            log.info("已接入 MCP 工具提供者，模型可使用远程 MCP Server 提供的工具");
        }

        return builder;
    }

    /**
     * 构造会话记忆提供者。
     *
     * <p>这里体现了 LangChain4j 记忆机制的<b>分层设计</b>：
     * <pre>
     *   MessageWindowChatMemory  = 策略层，决定「保留哪几条」
     *   ChatMemoryStore          = 存储层，决定「存到哪里」
     * </pre>
     * 所以我们只需要替换存储层（内存 → 文件），窗口裁剪逻辑完全复用框架实现。
     *
     * @return 每个 memoryId 一份独立记忆的记忆提供者
     */
    private ChatMemoryProvider chatMemoryProvider() {
        int maxMessages = properties.getMemory().getMaxMessages();

        if (properties.getMemory().isPersistEnabled()) {
            // 持久化模式：记忆写入本地文件，服务重启后仍能恢复
            ChatMemoryStore fileStore = new FileChatMemoryStore(properties.getMemory().getStoreDir());
            log.info("会话记忆模式：文件持久化，窗口 {} 条消息，目录 {}",
                    maxMessages, properties.getMemory().getStoreDir());
            return memoryId -> MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(maxMessages)
                    .chatMemoryStore(fileStore)
                    .build();
        }

        // 默认模式：记忆存内存，重启即丢失，适合开发调试
        log.info("会话记忆模式：内存（重启后记忆将丢失），窗口 {} 条消息", maxMessages);
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .build();
    }

    /**
     * 请求日志钩子。
     *
     * <p>{@code chatRequestTransformer} 会在「消息已组装完成、即将发往大模型」的
     * 那一刻被调用，是观测真实上下文的唯一位置。
     * <p>注意这里只做只读观察，必须<b>原样返回</b>传入的 ChatRequest——
     * 一旦返回 null 或改了内容，发出去的请求就变了。
     */
    private ChatRequest logRequest(ChatRequest request) {
        int messageCount = request.messages() == null ? 0 : request.messages().size();
        log.info("即将请求大模型：上下文消息 {} 条，模型参数={}", messageCount, request.parameters());
        return request;
    }
}
