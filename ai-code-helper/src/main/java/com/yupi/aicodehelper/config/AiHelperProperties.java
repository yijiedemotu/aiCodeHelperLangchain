package com.yupi.aicodehelper.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * AI 助手的统一配置类（配置外部化）。
 *
 * <h3>为什么要有这个类？</h3>
 * 原项目把「文档目录」「检索条数」「相似度阈值」这类业务参数硬编码在 Java 代码里，
 * 例如 {@code FileSystemDocumentLoader.loadDocuments("src/main/resources/docs")}
 * 和 {@code .maxResults(5).minScore(0.75)}。这样带来三个问题：
 * <ol>
 *   <li><b>改参数要重新编译</b>——调整一次检索条数就得走一遍完整的编译打包流程；</li>
 *   <li><b>无法区分环境</b>——开发环境想多检索几条方便调试，生产环境想省 token，
 *       同一份代码做不到；</li>
 *   <li><b>参数散落各处</b>——想盘点「这个系统有哪些可调项」得翻遍所有类。</li>
 * </ol>
 *
 * <h3>Spring Boot 的解决方案：@ConfigurationProperties</h3>
 * 把一组相关配置绑定到一个普通 Java 对象上，application.yml 里怎么写，
 * 这里就怎么读，且是<b>类型安全</b>的（写错类型启动时就报错，而不是运行到那一行才炸）。
 *
 * <h3>绑定规则（relaxed binding，宽松绑定）</h3>
 * 前缀 {@code ai-helper}，字段 {@code maxMessages}，以下 yml 写法都能绑定成功：
 * <pre>
 * ai-helper:
 *   memory:
 *     max-messages: 10      # 短横线分隔（推荐，Spring Boot 官方风格）
 *     maxMessages: 10       # 驼峰
 *     MAX_MESSAGES: 10      # 大写下划线（环境变量风格）
 * </pre>
 *
 * <h3>生效前提</h3>
 * 本类没有加 {@code @Component}，它是靠启动类上的 {@code @ConfigurationPropertiesScan}
 * 被扫描注册的。两种写法的区别：
 * <ul>
 *   <li>{@code @Component + @ConfigurationProperties}：作为普通 Bean 被组件扫描发现；</li>
 *   <li>只有 {@code @ConfigurationProperties} + 启动类 {@code @ConfigurationPropertiesScan}：
 *       按包扫描，适合把配置类集中放在 config 包下的项目结构（本项目采用这种）。</li>
 * </ul>
 *
 * @see com.yupi.aicodehelper.AiCodeHelperApplication 上的 @ConfigurationPropertiesScan
 */
@Data // Lombok：生成 getter/setter/toString/equals/hashCode。Setter 是必须的，否则无法绑定
@ConfigurationProperties(prefix = "ai-helper")
public class AiHelperProperties {

    /**
     * 会话记忆相关配置。
     * 注意字段初始化写法 {@code = new Memory()}：这样即使 yml 里一个都没配，
     * 也能拿到一份「合理的默认值」，而不是 null 导致空指针。
     */
    private Memory memory = new Memory();

    /** RAG 检索增强相关配置 */
    private Rag rag = new Rag();

    /** 护栏（内容安全）相关配置 */
    private Guardrail guardrail = new Guardrail();

    /** MCP（外部工具协议）相关配置 */
    private Mcp mcp = new Mcp();

    /**
     * 可观测性相关配置 —— 对应 LangChain4j 1.20.0 引入的
     * {@code AiServiceListener} 官方事件总线。装配代码见
     * {@code com.yupi.aicodehelper.ai.observability.AiObservabilityConfig}。
     */
    private Observability observability = new Observability();

    /**
     * 会话记忆配置。
     *
     * <p>这里有一个 AI 应用开发的关键取舍：<b>记忆窗口开多大？</b>
     * 每多带一轮历史，就多消耗一份输入 token（按量计费，且会挤占上下文窗口）。
     * 窗口太小模型会「失忆」，太大则成本和延迟上升，还容易让模型被早期内容带偏。
     * 一般按「最近 N 轮」取值，N=10 是常见起点。
     */
    @Data
    public static class Memory {

        /**
         * 记忆窗口中保留的最大消息条数。
         *
         * <p>注意单位是「条」而不是「轮」：一轮问答 = 1 条 UserMessage + 1 条 AiMessage，
         * 所以 10 条实际只记住最近 5 轮对话。
         */
        private int maxMessages = 10;

        /**
         * 是否把会话记忆持久化到磁盘。
         *
         * <p>设为 false 时使用 LangChain4j 自带的 {@code InMemoryChatMemoryStore}，
         * 重启服务记忆全部丢失；设为 true 时使用本项目实现的
         * {@link com.yupi.aicodehelper.ai.memory.FileChatMemoryStore} 落盘。
         */
        private boolean persistEnabled = false;

        /** 记忆落盘目录（相对路径基于服务的工作目录），仅 persistEnabled=true 时生效 */
        private String storeDir = "chat-data/memory";

        /**
         * 记忆窗口的裁剪策略：{@code message}（按条数）或 {@code token}（按 token 数）。
         *
         * <h4>为什么要提供两种策略？</h4>
         * 两者约束的东西不同，各有适用的场合：
         * <pre>
         *   message —— 最多留 N 条消息
         *     优点：行为直观、零计算开销
         *     缺点：约束不了长度。10 条短寒暄约 800 token，
         *           10 条含代码的长回答可能 15000 token，直接顶爆上下文窗口
         *
         *   token   —— 最多留 N 个 token
         *     优点：与模型的真实限制对齐（上下文窗口就是按 token 算的）
         *     缺点：每条消息都要估算 token，有计算开销（本项目用启发式估算，很轻）
         * </pre>
         *
         * <p>默认 {@code message}，保持与升级前一致的行为，避免「一升级行为就变」；
         * 生产环境建议改成 {@code token}，理由见
         * {@link com.yupi.aicodehelper.ai.memory.HeuristicTokenCountEstimator} 的类注释。
         */
        private String strategy = "message";

        /**
         * {@code strategy=token} 时保留的最大 token 数。
         *
         * <p>取值要留出余量：这个窗口只约束<b>历史消息</b>，
         * 实际发给模型的还有系统提示词、本轮提问、RAG 检索结果和工具清单。
         * qwen-max 的上下文窗口很大，取 4000 是「够用且不浪费」的保守值——
         * 真正该警惕的不是窗口不够，而是历史被塞进太多不再相关的内容。
         */
        private int maxTokens = 4000;
    }

    /**
     * RAG（Retrieval-Augmented Generation，检索增强生成）配置。
     *
     * <p>RAG 的本质：模型的知识来自训练数据，存在「不知道你的私有资料」和
     * 「知识有截止日期」两个硬伤。RAG 的做法是在提问时先从你的资料库里
     * 检索出相关片段，拼进提示词一并送给模型，让模型「开卷答题」。
     */
    @Data
    public static class Rag {

        /**
         * 知识库文档所在位置（Spring Resource 路径）。
         *
         * <p><b>这里修掉了原代码的一个部署级 Bug</b>：原文写作
         * {@code "src/main/resources/docs"}，是文件系统相对路径。
         * 在 IDE 里运行时工作目录恰好是项目根目录，所以能跑通；
         * 一旦打成 jar 包运行，jar 内部不存在 {@code src/main/resources} 这个目录，
         * 启动直接失败。改用 classpath 前缀后，无论 IDE 还是 jar 都能正确读取。
         */
        private String docsPath = "classpath:docs";

        /** 单个文档片段的字符数上限。太大检索不精准，太小会切断语义 */
        private int chunkSize = 1000;

        /** 相邻片段之间的重叠字符数，用于避免恰好把一句话切两半导致语义丢失 */
        private int chunkOverlap = 200;

        /** 每次检索返回的最大片段数。条数越多上下文越全，但 token 消耗线性增长 */
        private int maxResults = 5;

        /**
         * 相似度分数阈值（0~1），低于该分数的片段会被过滤掉。
         *
         * <p>这个阈值很关键：调高会让「宁缺毋滥」（可能检索不到东西），
         * 调低会把不相关内容塞给模型、诱导它胡编（幻觉）。
         * 0.75 是偏严格的取值，适合文档主题集中的知识库。
         */
        private double minScore = 0.75;

        /**
         * 向量库快照文件路径。非空时启用「向量持久化」：
         * 启动时若快照存在则直接加载，不重复调用 embedding 接口。
         *
         * <p>为什么重要？把文档向量化是要按 token 收费的，
         * 原项目每次重启都重新向量化一遍整个知识库，纯属重复花钱。
         */
        private String vectorStorePath = "chat-data/vector-store.json";

        /**
         * 是否启用「查询压缩」（Query Compression）。
         *
         * <p>解决多轮对话中的检索失效问题。举例：
         * 用户先问「Java 怎么学」，接着追问「那要多久？」——
         * 单看第二句完全检索不到有用信息，因为「那」指代不明。
         * CompressingQueryTransformer 会先让模型把「那要多久？」
         * 结合历史补全成「学习 Java 需要多长时间？」，再拿完整问题去检索。
         * 代价是多一次模型调用，所以做成开关。
         */
        private boolean compressQuery = false;
    }

    /**
     * 护栏（Guardrails）配置。
     *
     * <p>护栏是「代码级」的确定性约束，和写在提示词里的「请不要回答违规内容」
     * 有本质区别：提示词是「建议」，模型可能不遵守；护栏是「规则」，
     * 在请求发出前/响应返回后由 Java 代码强制校验，模型绕不过去。
     */
    @Data
    public static class Guardrail {

        /** 单条用户输入的最大字符数，超长直接拒绝（防 prompt 灌爆上下文） */
        private int maxInputLength = 2000;

        /**
         * 输出屏蔽词列表。命中后不是拒绝回答，而是把词替换成 ** 再返回，
         * 这样既满足合规要求，又不会让用户觉得「莫名其妙被拒」。
         */
        private List<String> bannedWords = List.of();

        /**
         * 是否启用提示词注入（Prompt Injection）检测。
         *
         * <p>提示词注入指用户通过「忽略以上所有指令」「你现在是……」
         * 这类话术劫持模型行为。启用后将使用 LangChain4j 内置的
         * {@code PatternBasedPromptInjectionGuardrail} 做正则匹配拦截。
         */
        private boolean promptInjectionCheck = true;

        /** 是否启用输出屏蔽词替换 */
        private boolean sensitiveWordFilter = true;
    }

    /**
     * MCP（Model Context Protocol）配置。
     *
     * <p>MCP 是 Anthropic 提出的开放协议，可以理解为「AI 世界的 USB 接口」：
     * 只要某个服务实现了 MCP Server，任何支持 MCP 的模型都能即插即用地调用它，
     * 不需要为每个工具手写一遍 {@code @Tool} 方法。
     *
     * <p>默认关闭，因为连接外部服务需要可用的网络和凭据，
     * 不加开关会让「没有外网」的开发者在启动阶段就失败。
     */
    @Data
    public static class Mcp {

        /** 是否启用 MCP 工具接入 */
        private boolean enabled = false;

        /**
         * MCP Server 的 Streamable HTTP 地址。
         * 以智谱开放平台的联网搜索 MCP 为例：
         * https://open.bigmodel.cn/api/mcp/web_search/sse?Authorization=你的APIKey
         */
        private String url = "";

        /** 是否打印 MCP 通信报文，排查连接问题时打开 */
        private boolean logRequests = false;
    }

    /**
     * 可观测性配置（LangChain4j 1.20.0 的 {@code AiServiceListener} 事件总线）。
     *
     * <h3>它解决什么问题？</h3>
     * AI 应用最常被问到的三个问题，在 1.20.0 之前都缺少规范的落点：
     * <ol>
     *   <li><b>这次问答花了多少钱？</b>——token 用量只能从 {@code ChatResponse} 里手动抠；</li>
     *   <li><b>哪一次调用失败了、是哪个会话？</b>——异常日志里没有 memoryId 与调用方法名；</li>
     *   <li><b>模型到底有没有真的调用工具？</b>——只能靠读回答内容去猜。</li>
     * </ol>
     * 官方事件总线把这些都变成了「订阅事件」这一件事，见
     * {@code com.yupi.aicodehelper.ai.observability.AiObservabilityConfig}。
     *
     * <p>本组配置项只控制<b>记录什么</b>，不控制是否注册监听器——
     * 监听器本身是纯日志、无副作用，注册了也不会影响业务行为。
     */
    @Data
    public static class Observability {

        /**
         * 是否记录每次模型调用的 token 消耗与耗时。
         *
         * <p>对应事件 {@code AiServiceResponseReceivedEvent}。
         * 这是成本监控的基础数据——没有它，账单只能靠事后惊讶。
         */
        private boolean logTokenUsage = true;

        /**
         * 是否记录工具调用明细（工具名、参数、耗时、结果长度）。
         *
         * <p>对应事件 {@code ToolExecutedEvent}。
         * 排查「模型怎么答得不对」时，第一件事就是确认工具是否被调用、
         * 拿到的结果是不是空的。
         */
        private boolean logToolExecution = true;

        /**
         * 是否记录输出护栏的执行结果。
         *
         * <p>对应事件 {@code OutputGuardrailExecutedEvent}。
         * 护栏命中属于「业务正常拒绝」，需要与系统故障区分开统计。
         */
        private boolean logGuardrail = true;
    }
}
