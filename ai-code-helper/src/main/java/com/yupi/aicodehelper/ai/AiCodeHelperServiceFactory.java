package com.yupi.aicodehelper.ai;

import com.yupi.aicodehelper.ai.guardrail.SensitiveWordOutputGuardrail;
import com.yupi.aicodehelper.ai.memory.FileChatMemoryStore;
import com.yupi.aicodehelper.ai.memory.HeuristicTokenCountEstimator;
import com.yupi.aicodehelper.ai.memory.SanitizingChatMemoryStore;
import com.yupi.aicodehelper.ai.tools.InterviewQuestionTool;
import com.yupi.aicodehelper.config.AiHelperProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.observability.api.listener.AiServiceErrorListener;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.observability.api.listener.OutputGuardrailExecutedListener;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
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
 *   <li><b>⚑ 把「长度护栏」从 AiService 层挪到 HTTP 入口（实测发现）</b>：
 *       AiService 级别的 InputGuardrail 量到的是 <b>RAG 注入之后</b>的文本，
 *       导致输入 "Java"（4 字符）被报成「2162 字符」而拒绝——
 *       凡是能检索到知识库的问题都会被误杀，恰好打击 RAG 最该发挥作用的场景。
 *       现改由 {@code InputGuardService} 在 Controller 层、检索之前校验用户原文，
 *       阈值仍复用同一个 {@link com.yupi.aicodehelper.ai.guardrail.InputLengthGuardrail}
 *       单例。完整分析见 {@link #guardedAssistant()} 的注释。</li>
 *   <li><b>可观测性</b>：记录每次请求的消息条数与工具调用，这是排查线上问题的基础。</li>
 *   <li><b>工具调用轮次上限</b>：防止模型陷入「反复调工具」的死循环，无谓烧钱。</li>
 *   <li><b>⚑ 修掉跨用户记忆串号（实测发现）</b>：原先 {@code chatForReport} 与
 *       {@code GuardedAssistant.chat} 都没有 {@code @MemoryId}，却都挂在带记忆的
 *       AiService 上，于是所有用户、所有接口的请求共用同一个 {@code "default"}
 *       记忆桶 —— 用户 A 的提问会出现在用户 B 的上下文里，是真实的隐私泄露。
 *       修法是<b>按「有状态 / 无状态」拆分服务</b>：只有 {@link AiCodeHelperService}
 *       配记忆，{@link GuardedAssistant} 与 {@link ReportAssistant} 一律无状态。
 *       证据与推理见 {@link ReportAssistant} 的类注释。</li>
 *   <li><b>⚑ 修掉 RAG 内容污染会话历史（实测发现）</b>：检索到的文档原文被框架
 *       原样写进记忆，单轮 2.6 KB，还会在后续每轮作为历史重复发送，成本逐轮放大。
 *       现在通过 {@link SanitizingChatMemoryStore} 在写入路径上净化。</li>
 *   <li><b>⚑ 修掉一个「静默失效」的配置</b>：原先只用了 {@code .contentRetriever(...)}，
 *       导致 {@code RagConfig} 里构造的 {@code RetrievalAugmentor} Bean
 *       从未被使用，{@code ai-helper.rag.compress-query=true} 完全不生效却毫无报错。
 *       现在有状态服务改用完整的 {@code RetrievalAugmentor}。</li>
 * </ol>
 *
 * <h3>接入 LangChain4j 1.20.0 新能力（本轮新增）</h3>
 * <ol>
 *   <li><b>官方事件总线可观测性</b>：注册四个 {@code AiServiceListener}
 *       （token 用量 / 异常 / 工具调用 / 输出护栏），见 {@code baseBuilder} 第⑥段
 *       与 {@code ai/observability/AiObservabilityConfig}。
 *       这是 1.20.0 才有的规范做法，比此前「手动打点 + chatRequestTransformer」
 *       完整得多：能拿到 memoryId、方法名、真实 token 数与结束原因。</li>
 *   <li><b>官方「不把检索内容写进历史」开关</b>：
 *       {@code .storeRetrievedContentInChatMemory(false)}。此前靠自定义装饰器
 *       匹配框架内部提示词模板来实现，属于脆弱方案；现在主干走公开 API。</li>
 *   <li><b>工具异常补偿</b>：{@code .compensateOnToolErrors(true)}，
 *       工具抛异常不再让整轮对话失败，而是让模型降级作答。</li>
 *   <li><b>幻觉工具名兜底</b>：{@code .hallucinatedToolNameStrategy(...)}，
 *       模型请求一个不存在的工具时，回一句可读的纠正提示，而不是抛异常。</li>
 *   <li><b>记忆窗口策略可切换</b>：{@code ai-helper.memory.strategy}
 *       支持按条数（message）或按 token（token）裁剪，
 *       后者用 {@code TokenWindowChatMemory} 与 {@link HeuristicTokenCountEstimator}。</li>
 *   <li><b>接口层新增两种返回类型</b>：{@code Result<String>}（带 token/检索来源/工具记录）
 *       与 {@code TokenStream}（结构化流式事件），见 {@link AiCodeHelperService}。</li>
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

    /** RAG 检索器，由 {@code RagConfig} 提供。无状态服务用它做「按需检索」 */
    @Resource
    private ContentRetriever contentRetriever;

    /**
     * RAG 完整流水线（检索 + 可选的查询改写），由 {@code RagConfig} 提供。
     *
     * <h4>为什么同时注入 ContentRetriever 和 RetrievalAugmentor？</h4>
     * 这两者的关系是「零件」与「整机」：
     * {@code ContentRetriever} 只是流水线中间的一环（负责检索），
     * 而 {@code RetrievalAugmentor} 是整条流水线的总入口。
     *
     * <h4>⚠ 这里顺手修掉了一个「静默失效」的配置</h4>
     * 原先的代码只用了 {@code .contentRetriever(...)}，
     * 于是 {@code RagConfig} 里那个精心构造的 {@code retrievalAugmentor} Bean
     * <b>被创建出来后从来没有被任何人使用</b>——它只是在 Spring 容器里空转。
     * 后果是 {@code ai-helper.rag.compress-query=true} 这个开关<b>完全不生效</b>，
     * 而且不会报任何错。这类「配置写了但没接线」的缺陷最难发现，
     * 因为从配置文件看不出问题，日志也一片正常。
     * <p>现在改成：需要多轮上下文的有状态服务用完整的 {@code RetrievalAugmentor}
     * （查询压缩才能生效），无状态服务仍用轻量的 {@code ContentRetriever}。
     */
    @Resource
    private RetrievalAugmentor retrievalAugmentor;

    /** 自定义配置项 */
    @Resource
    private AiHelperProperties properties;

    /*
     * ==========================================================================
     *  可观测性：LangChain4j 1.20.0 官方事件总线（AiServiceListener）
     *  --------------------------------------------------------------------------
     *  这四个监听器由 ai/observability/AiObservabilityConfig 生产，
     *  这里只负责把它们注册到每个 AI Service 上。
     *
     *  ⚠ 为什么注入的是「具体接口类型」而不是一个 List？
     *  因为注册方法 registerListeners(...) 的参数是 AiServiceListener<?>...，
     *  而泛型通配符在 Spring 按类型注入集合时容易产生歧义
     *  （AiServiceResponseReceivedListener 既是 AiServiceListener，
     *   也是它自己的类型）。逐个显式字段注入最直白、最不容易出意外，
     *  且编译期就能发现问题——对教学项目来说，可读性也比省几行更重要。
     *
     *  这些监听器都只写日志、且内部吞掉了自身异常（见其类注释），
     *  因此注册它们不会改变任何业务行为，可以放心全量挂载。
     * ==========================================================================
     */

    /** token 用量与耗时（订阅 AiServiceResponseReceivedEvent） */
    @Resource
    private AiServiceResponseReceivedListener tokenUsageListener;

    /** 调用异常，带 memoryId 等业务上下文（订阅 AiServiceErrorEvent） */
    @Resource
    private AiServiceErrorListener aiServiceErrorListener;

    /** 工具调用明细（订阅 ToolExecutedEvent） */
    @Resource
    private ToolExecutedEventListener toolExecutedEventListener;

    /** 输出护栏命中情况（订阅 OutputGuardrailExecutedEvent） */
    @Resource
    private OutputGuardrailExecutedListener outputGuardrailExecutedListener;

    /*
     * 说明：这里原本还注入了一个 InputLengthGuardrail 单例，用于给
     * GuardedAssistant 挂长度护栏。现已移除，原因是实测发现
     * AiService 级别的输入护栏量到的是「RAG 注入之后」的文本，
     * 会把命中知识库的正常问题误判为超长（详见 guardedAssistant() 的注释）。
     * 长度校验统一移到 Controller 层，由 InputGuardService 在检索之前完成。
     */

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
        // 先拿到挂好通用能力（模型/工具/可观测）的构建器，再叠加「本服务特有」的三样东西。
        // 这种「公共部分抽方法、差异部分各自补」的写法，避免了多个 Bean 之间大量重复配置
        return baseBuilder(AiCodeHelperService.class)
                // ① 会话记忆 —— 唯一需要记忆的服务。
                //    chatStream 带 @MemoryId，每个会话一份独立记忆，不会串号
                .chatMemoryProvider(chatMemoryProvider())
                // ② 完整的检索增强流水线（而不是裸的 ContentRetriever）。
                //    差别在于：只有用 RetrievalAugmentor，
                //    ai-helper.rag.compress-query=true 时挂上去的
                //    CompressingQueryTransformer 才会真正参与工作。
                //    这里有状态、有历史，正是查询压缩能发挥作用的场景
                .retrievalAugmentor(retrievalAugmentor)
                // ③ 流式模型 —— 支撑 SSE 逐字返回
                .streamingChatModel(qwenStreamingChatModel)

                // ④ 官方开关：不要把 RAG 检索到的内容写进会话历史
                /*
                 * ⚑ 这一行是「RAG 污染会话历史」这个问题【官方】的解法。
                 *
                 * 背景：DefaultContentInjector 把检索到的文档原文拼进 UserMessage
                 * 之后，记忆层会把「发给模型的完整消息」原样保存。后果有三层：
                 *   ① 记忆文件体积失控（实测单轮 2.6 KB，绝大部分是文档原文）；
                 *   ② token 成本逐轮放大——这段文档会在后续每一轮作为历史重发，
                 *      而检索是每轮都做的，上下文像滚雪球；
                 *   ③ 语义污染——模型看到 "Answer using the following information:"
                 *      这种指令性文字被当成对话内容，多轮后开始模仿。
                 *
                 * 本项目此前用自定义的 SanitizingChatMemoryStore 装饰器在写入路径上
                 * 截掉这段内容。那个方案能用，但它依赖框架【内部提示词模板】的字面量
                 * （"\n\nAnswer using the following information:"），
                 * 模板一变就静默失效——属于「靠约定而不是靠契约」的脆弱实现。
                 *
                 * 而这个开关是公开 API：框架在决定「要不要把这批 Content 存进记忆」
                 * 时直接读它，不涉及任何字符串匹配。
                 *
                 * ⚠ 默认值是 true（可从 AiServiceContext 的构造函数字节码确认：
                 *    iconst_1 → putfield storeRetrievedContentInChatMemory），
                 *    也就是说「默认就会污染历史」，必须显式关掉。
                 *
                 * 现在两层都在：官方开关负责主干，SanitizingChatMemoryStore 作为
                 * 兜底（万一将来换了自定义 ContentInjector、标记串变了，
                 * 或者框架在别的路径上也写记忆，它仍能拦住）。
                 * 兜底层是「只做减法」的——匹配不上最多是不生效，绝不会误删用户原话。
                 */
                .storeRetrievedContentInChatMemory(false)
                .build();
    }

    /**
     * 带护栏的同步问答服务（<b>无状态</b>）。
     *
     * <p>单独做成一个 Bean，而不是把护栏加到主服务上，是刻意的设计：
     * <b>护栏会有副作用</b>——输入护栏失败会抛异常直接中断请求，
     * 如果挂在流式接口上，用户会在流已经开始推送之后突然收到错误。
     * 把它隔离到独立的同步接口，行为边界清晰，便于测试和降级。
     *
     * <p><b>⚑ 关键：这里刻意不调用 {@code chatMemoryProvider()}</b>。
     * 一次性问答不需要上下文，不配记忆就从根本上消除了「default 记忆桶
     * 被所有用户共用」的串号风险（该缺陷的实测证据见
     * {@link ReportAssistant} 的类注释）。
     * <p>同理，这里用轻量的 {@code ContentRetriever} 而不是
     * {@code RetrievalAugmentor}：查询压缩依赖对话历史，
     * 无状态服务没有历史可用，做不做改写结果一样。检索能力一点不少。
     */
    @Bean
    public GuardedAssistant guardedAssistant() {
        /*
         * ⚠ 这里【一个输入护栏都不放】，这是两次实测踩坑之后的结论。
         *
         * AiService 级别的 InputGuardrail 校验的不是「用户原始输入」，
         * 而是 RetrievalAugmentor 把检索结果注入<b>之后</b>的消息。
         * 也就是说，量到的是「用户输入 + 系统自己检索到的文档原文」。
         * 把系统拼进去的内容算在用户头上，必然产生大量无法解释的误拒。
         * 这类缺陷在本项目出现了<b>两次</b>，症状不同、病根相同：
         *
         * 【第一次·长度护栏】
         *     输入 "Java"（4 字符）   → 报「当前 2162 字符」被拒
         *     输入 "java"（4 字符）   → 报「当前 3444 字符」被拒
         *     输入 "面试题"（3 字符）  → 报「当前 4149 字符」被拒
         *   规律：凡是能检索到知识库的问题都会被误杀——
         *   恰好把 RAG 最该发挥作用的场景变成了重灾区。
         *
         * 【第二次·注入护栏】
         *   把长度校验挪走后，注入护栏留在原处，于是：
         *     输入 "Java"       → 被「提示词注入」规则拦截
         *     输入 "什么是 JVM"  → 被「提示词注入」规则拦截
         *   原因相同：检索进来的技术文档里有结构化片段
         *   （分隔线、示例代码里的 system:/user: 行、JSON 片段等），
         *   被注入规则当成了攻击话术。用户一个字都没打错，却被告知「你在攻击我」。
         *
         * 【结论】一切针对「用户输入」的校验都必须在检索之前、于 HTTP 入口完成。
         *   现在全部由 ai.guardrail.InputGuardService 承担，
         *   同步（/chat-sync）与流式（/chat）两条路径共用同一套校验，见该类注释。
         *   护栏能力没有减少，只是位置正确了。
         *
         * 本 Bean 因此退化为「纯净的检索增强问答」：模型 + 工具 + 检索。
         */

        // 输出护栏保留在 AiService 层 —— 它检验的是【模型产出】，
        // 不存在「量错对象」的问题，放在这里正合适。
        // 输出护栏：屏蔽词替换（不拒绝回答，只做掩码，兼顾合规与体验）
        List<OutputGuardrail> outputGuardrails = new ArrayList<>();
        if (properties.getGuardrail().isSensitiveWordFilter()) {
            outputGuardrails.add(
                    new SensitiveWordOutputGuardrail(properties.getGuardrail().getBannedWords()));
        }

        log.info("同步问答服务已装配：输出护栏 {} 个；输入校验由 InputGuardService 在入口统一负责",
                outputGuardrails.size());

        return baseBuilder(GuardedAssistant.class)
                // 无记忆（有意的，见方法注释）
                .contentRetriever(contentRetriever)
                .outputGuardrails(outputGuardrails)
                .build();
    }

    /**
     * 结构化输出服务（<b>无状态</b>）。
     *
     * <p>它替代了原先挂在 {@link AiCodeHelperService} 上的
     * {@code chatForReport} 方法，目的是把无状态能力从有状态接口里剥离出来。
     * 详细的原因分析与实测证据见 {@link ReportAssistant} 的类注释。
     *
     * <p>另外注意：{@link ReportAssistant} 这个接口本身几乎全是注释、
     * 零业务代码——这正是 LangChain4j 声明式风格的特点：
     * <b>接口定义契约，工厂组装能力</b>，两边都不需要写流程代码。
     */
    @Bean
    public ReportAssistant reportAssistant() {
        return baseBuilder(ReportAssistant.class)
                // 无记忆：结构化输出是一次性任务，不需要上下文，也就没有串号风险
                .contentRetriever(contentRetriever)
                .build();
    }

    /**
     * 构建器的<b>公共</b>装配逻辑（被上面三个 Bean 复用）。
     *
     * <h4>为什么叫 base（基座）而不是 common（通用）？</h4>
     * 因为它刻意只放「三个服务都需要、且没有争议」的能力：
     * 模型、工具、可观测性。
     * <p><b>记忆与检索被有意排除在外</b>，交回给每个 Bean 自己决定，原因是：
     * <ul>
     *   <li><b>记忆不是「通用能力」</b>——它是「有状态服务」的专属特征。
     *       把它塞进公共基座，会强迫无状态服务（护栏问答、结构化输出）
     *       也拿到一个共享的 default 记忆桶，直接导致跨用户串号。
     *       这是本项目实测踩到的真实坑，详见 {@link ReportAssistant}。</li>
     *   <li><b>检索的接法有两种</b>——有状态服务应该用完整的
     *       {@code RetrievalAugmentor}（支持查询压缩），
     *       无状态服务用轻量的 {@code ContentRetriever} 即可。
     *       两者只能二选一（同时设置会冲突），所以不适合放进公共基座。</li>
     * </ul>
     * 这个改动看似只是「把两行挪出去」，但它把「一个服务是不是有状态的」
     * 从隐式变成了<b>显式</b>——读每个 Bean 的代码就能立刻看出答案。
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
    private <T> AiServices<T> baseBuilder(Class<T> clazz) {
        AiServices<T> builder = AiServices.builder(clazz)
                // ---------- ① 模型 ----------
                .chatModel(qwenChatModel)

                // ---------- ② 工具调用 ----------
                // 传入的是工具类的实例，框架会扫描其中的 @Tool 方法并生成工具清单
                .tools(new InterviewQuestionTool())

                // 工具调用轮次上限：模型可能反复「调工具→不满意→再调」，
                // 不设上限就是一个烧钱的无底洞。5 轮足以覆盖正常场景
                .maxToolCallingRoundTrips(5)

                // ---------- ③ 可观测性 ----------
                // 每次请求打印实际送给模型的消息条数。
                // 这个数字非常有用：如果它意外地大，说明记忆或 RAG 往上下文里
                // 塞了过多内容，是 token 成本失控的常见根因
                .chatRequestTransformer(this::logRequest)

                // 记录每次工具调用的结果，便于回答「模型到底有没有真的调用工具」
                .afterToolExecution(execution ->
                        log.info("工具执行完毕: {}", execution))

                // ---------- ④ 工具异常补偿（LangChain4j 新版能力） ----------
                /*
                 * 默认情况下，工具方法抛异常会让整轮对话直接失败——
                 * 用户收到一个错误，而不是「模型少用了点信息」。
                 *
                 * 开启补偿后，框架会把异常转换成一个「工具执行失败」的结果回传给模型，
                 * 模型有机会基于现有信息降级作答（例如「暂时查不到实时面试题，
                 * 我先讲讲我了解的」）。同时会发出 ToolCompensatedEvent，
                 * 便于监控「工具失败率」这个关键指标。
                 *
                 * 这与 InterviewQuestionTool 内部「失败时返回说明字符串而不是抛异常」
                 * 是同一思路的两道防线：工具自己兜一层，框架再兜一层。
                 */
                .compensateOnToolErrors(true)

                // ---------- ⑤ 模型「幻觉工具名」的兜底 ----------
                /*
                 * 一个真实存在、但很少被提及的故障：模型有时会请求调用一个
                 * 【根本不存在的工具】，比如把 interviewQuestionSearch 记成
                 * searchInterviewQuestions，或凭空编一个 webSearch。
                 *
                 * 默认行为是抛异常 → 整轮失败。而对模型来说，它只是「记错了名字」，
                 * 完全可以在被告知「没有这个工具」之后改用正确的方式回答。
                 * 这个策略就是把「报错」变成「一句可读的纠正提示」。
                 */
                .hallucinatedToolNameStrategy(request -> {
                    log.warn("模型请求了一个不存在的工具，已回退为提示：tool={}", request.name());
                    return ToolExecutionResultMessage.from(request,
                            "工具 '" + request.name() + "' 不存在。请只使用系统提供的工具列表中的工具，"
                                    + "或直接用你已有的知识回答用户。");
                })

                // ---------- ⑥ 可观测性：注册官方事件监听器 ----------
                /*
                 * LangChain4j 1.20.0 的 AiServiceListener 事件总线。
                 * 事件总线是【每个 AiService 各自持有一份】的，
                 * 所以要在构建每个服务时都注册一次——这也正是把它放在
                 * 公共基座 baseBuilder 里的原因：三个服务一次配齐，不会漏。
                 *
                 * 订阅的四类事件与用途：
                 *   AiServiceResponseReceivedEvent → token 用量、耗时、是否被截断
                 *   AiServiceErrorEvent            → 失败调用 + memoryId 上下文
                 *   ToolExecutedEvent              → 工具是否被真正调用、结果多长
                 *   OutputGuardrailExecutedEvent   → 护栏命中统计
                 *
                 * 详细说明见 ai/observability/AiObservabilityConfig 的类注释。
                 */
                .registerListeners(
                        tokenUsageListener,
                        aiServiceErrorListener,
                        toolExecutedEventListener,
                        outputGuardrailExecutedListener);

        // ---------- ④ 可选：MCP 外部工具 ----------
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
     * <h4>⚑ 两种存储都被 {@link SanitizingChatMemoryStore} 包了一层</h4>
     * 这不是多余的包装。实测发现：<b>RAG 检索到的文档原文会被原样写进会话历史</b>，
     * 导致记忆文件单轮就膨胀到 2.6 KB，而且下一轮这段文档又会作为历史
     * 重新发给模型，token 成本逐轮放大。
     * 净化装饰器在「写入」环节就把检索脚手架剔掉，
     * 所以<b>不论是文件存储还是内存存储，历史里都只剩用户真正说过的话</b>。
     * 完整的成因分析与证据见 {@link SanitizingChatMemoryStore} 的类注释。
     *
     * @return 每个 memoryId 一份独立记忆的记忆提供者
     */
    private ChatMemoryProvider chatMemoryProvider() {
        int maxMessages = properties.getMemory().getMaxMessages();
        int maxTokens = properties.getMemory().getMaxTokens();
        String strategy = properties.getMemory().getStrategy();
        boolean tokenStrategy = "token".equalsIgnoreCase(strategy);

        // ---------- 第一步：确定「存到哪儿」（存储层） ----------
        // 两种存储都要套净化装饰器，所以先把它建好，后面两种裁剪策略共用同一个 store
        ChatMemoryStore store;
        if (properties.getMemory().isPersistEnabled()) {
            // 持久化模式：记忆写入本地文件，服务重启后仍能恢复。
            // 外面再套一层净化装饰器（装饰器模式：加能力不改原类）
            store = new SanitizingChatMemoryStore(
                    new FileChatMemoryStore(properties.getMemory().getStoreDir()));
            log.info("会话记忆存储：文件持久化 + 检索内容净化，目录 {}", properties.getMemory().getStoreDir());
        } else {
            // 默认模式：记忆存内存，重启即丢失，适合开发调试。
            // 注意这里显式给出了 InMemoryChatMemoryStore ——
            // 就是为了能把它包进净化装饰器。若省略 store，MessageWindowChatMemory
            // 会自己 new 一个内部存储，我们就失去了「在写入路径上插手」的机会
            store = new SanitizingChatMemoryStore(new InMemoryChatMemoryStore());
            log.info("会话记忆存储：内存 + 检索内容净化（重启后记忆将丢失）");
        }

        // ---------- 第二步：确定「保留多少」（策略层） ----------
        if (tokenStrategy) {
            /*
             * 按 token 裁剪：与模型的真实限制对齐。
             *
             * 为什么「条数」不够用：10 条短寒暄约 800 token，
             * 10 条含代码的长回答可能 15000 token——同样是「10 条」，
             * 长度差了近 20 倍。模型上下文窗口是按 token 算的，
             * 用条数去约束 token，等于没有约束。
             *
             * 估算器用本项目自带的启发式实现（不依赖密钥、纯本地、可离线跑测试）；
             * 需要精确计费口径时换成官方 QwenTokenCountEstimator 即可，
             * 这里一行都不用改——见 HeuristicTokenCountEstimator 的类注释。
             */
            TokenCountEstimator estimator = new HeuristicTokenCountEstimator();
            log.info("会话记忆窗口：按 token 裁剪，上限 {} token，估算器 {}",
                    maxTokens, estimator.getClass().getSimpleName());
            return memoryId -> TokenWindowChatMemory.builder()
                    .id(memoryId)
                    // maxTokens 的第一个参数是 Integer，这里自动装箱
                    .maxTokens(maxTokens, estimator)
                    .chatMemoryStore(store)
                    .build();
        }

        // 默认：按条数裁剪（与升级前行为一致，避免「一升级行为就变」）
        if (!"message".equalsIgnoreCase(strategy)) {
            // 配置写错了不该静默退回：告诉使用者「你配的值没被认识」，
            // 否则他会以为策略生效了，实际跑的是另一套
            log.warn("未知的记忆窗口策略 '{}'（可选 message / token），已回退为按条数裁剪", strategy);
        }
        log.info("会话记忆窗口：按条数裁剪，上限 {} 条消息", maxMessages);
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(store)
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
