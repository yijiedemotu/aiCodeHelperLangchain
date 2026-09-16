package com.yupi.aicodehelper.ai.rag;

import com.yupi.aicodehelper.config.AiHelperProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG（检索增强生成）装配配置。
 *
 * <h3>RAG 要解决的问题</h3>
 * 大模型有两个天生短板：<b>不知道你的私有资料</b>，且<b>知识有截止日期</b>。
 * 微调（Fine-tuning）能解决但成本极高、更新慢。RAG 的思路更轻巧：
 * 提问时先从你的资料库里检索出最相关的几段，连同问题一起送给模型，
 * 让模型「开卷答题」。模型本身没变，但答案基于你的资料，准确性和时效性都上来了。
 *
 * <h3>完整流水线（离线索引 + 在线检索）</h3>
 * <pre>
 *  ┌──────────── 离线阶段（应用启动时执行一次） ────────────┐
 *  │ ① 加载文档   docs/*.md                                  │
 *  │ ② 文档切分   按段落切成 1000 字、重叠 200 字的小块       │
 *  │ ③ 向量化     每块调 text-embedding-v4 转成向量           │
 *  │ ④ 存入向量库 InMemoryEmbeddingStore                     │
 *  └─────────────────────────────────────────────────────────┘
 *  ┌──────────── 在线阶段（每次用户提问时执行） ────────────┐
 *  │ ⑤ 把用户问题也转成向量                                  │
 *  │ ⑥ 在向量库里找最相似的 top5（且相似度 ≥ 0.75）          │
 *  │ ⑦ 把命中的片段拼进提示词，一起发给大模型                │
 *  └─────────────────────────────────────────────────────────┘
 *
 * <h3>相比原版修复/增强了什么</h3>
 * <ol>
 *   <li><b>修复打包必炸的路径 Bug</b>（详见 {@link #loadDocuments()}）。</li>
 *   <li><b>向量库持久化</b>：原版每次启动都重新向量化整个知识库，
 *       而向量化是按 token 收费的，纯属重复花钱。现在支持快照复用。</li>
 *   <li><b>查询压缩</b>：解决多轮对话中「那要多久？」这类问题检索不到东西的顽疾。</li>
 *   <li><b>全部参数外部化</b>：块大小、阈值、条数都能在 yml 里调。</li>
 * </ol>
 *
 * <h3>一个 Java 语法小坑（本文件踩过）</h3>
 * 本类需要同时用到两个同名类型：
 * <ul>
 *   <li>{@code jakarta.annotation.Resource}——依赖注入注解；</li>
 *   <li>{@code org.springframework.core.io.Resource}——Spring 的资源抽象。</li>
 * </ul>
 * Java <b>不支持 import 别名</b>（那是 Kotlin/Groovy 的语法），
 * 两者只能 import 一个，另一个在代码里写全限定名。
 * 本类选择 import 注入注解（用得多），在 {@link #loadDocuments()} 中写全限定名。
 */
@Slf4j
@Configuration
public class RagConfig {

    /**
     * 向量化模型（text-embedding-v4，由 DashScope Starter 自动装配）。
     *
     * <p>它的唯一职责是「把文本转成一串浮点数（向量）」。
     * 语义相近的文本，向量在空间中的距离也近——这正是「按意思检索」
     * 能成立、而不用「按关键词匹配」的原因。
     */
    @Resource
    private EmbeddingModel qwenEmbeddingModel;

    /**
     * 对话模型。这里注入它不是为了对话，而是给「查询压缩」用——
     * 压缩查询需要让模型把「那要多久？」改写成一个完整问题，
     * 这本身就是一次文本生成任务。详见 {@link #retrievalAugmentor}。
     */
    @Resource
    private ChatModel qwenChatModel;

    /** 可调参数 */
    @Resource
    private AiHelperProperties properties;

    /**
     * 向量库（这里用基于内存的实现）。
     *
     * <h4>为什么返回具体类型 InMemoryEmbeddingStore 而不是接口 EmbeddingStore？</h4>
     * 因为后面的 Bean 需要调用 {@code isEmpty()} / {@code size()} 来判断
     * 是否需要重新向量化，而在 LangChain4j 中这两个方法只定义在
     * 具体的 InMemoryEmbeddingStore 上（接口 EmbeddingStore 只规定
     * add/search/remove 等标准操作，因为它要兼容 Redis、PGVector 等
     * 共享型存储——那里没有「是否为空」这种廉价可得的语义）。
     *
     * <h4>生产环境应该换成什么？</h4>
     * InMemoryEmbeddingStore 的数据在 JVM 堆内存里，只适合开发和学习。
     * 真实项目应替换为：
     * <pre>
     *   Redis（langchain4j-community-redis）  —— 已有 Redis 时的最省事选择
     *   PostgreSQL + PGVector                 —— 数据量中等、想少维护一个组件
     *   Milvus / Qdrant                       —— 千万级向量、需要专业检索能力
     * </pre>
     * 无论换哪个，只需改动本方法，其余代码一行不用动——这就是面向接口设计的好处。
     *
     * @return 向量库；若配置了快照且文件存在则从快照恢复
     */
    @Bean
    public InMemoryEmbeddingStore<TextSegment> embeddingStore() {
        String snapshotPath = properties.getRag().getVectorStorePath();

        if (StringUtils.hasText(snapshotPath)) {
            Path path = Paths.get(snapshotPath);
            if (Files.exists(path)) {
                try {
                    // fromFile 会把序列化的向量连同原始文本、元数据一起读回来
                    InMemoryEmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromFile(path);
                    log.info("向量库快照加载成功：{}，已恢复 {} 条向量片段", path.toAbsolutePath(), store.size());
                    return store;
                } catch (Exception e) {
                    // 快照损坏（比如序列化格式随版本变化）不能让应用起不来，
                    // 降级为「重新构建向量库」是更稳妥的选择
                    log.warn("向量库快照加载失败，将重新构建。原因: {}", e.getMessage());
                }
            }
        }

        log.info("未找到可用的向量库快照，将从知识库文档重新构建");
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 内容检索器：负责「根据问题找出最相关的资料片段」。
     *
     * <p>这个方法把离线的「索引构建」和在线要用的「检索器」放在一起，
     * 因为二者共享同一个向量库实例，写在一起逻辑最连贯。
     *
     * @param embeddingStore 上一步准备好的向量库
     * @return 供 AI Service 使用的检索器
     */
    @Bean
    public ContentRetriever contentRetriever(InMemoryEmbeddingStore<TextSegment> embeddingStore) {
        AiHelperProperties.Rag ragConfig = properties.getRag();

        if (embeddingStore.isEmpty()) {
            // ---------- 首次启动：构建索引 ----------
            List<Document> documents = loadDocuments();
            if (documents.isEmpty()) {
                // 没有文档不是致命错误：应用仍能正常对话，只是没有知识库加持。
                // 但必须大声告警，否则「为什么 RAG 不生效」会变成难查的悬案
                log.warn("知识库为空！请检查 {} 下是否存在文档，当前 RAG 不会提供任何检索结果",
                        ragConfig.getDocsPath());
            } else {
                ingest(documents, embeddingStore);
                saveSnapshotIfConfigured(embeddingStore);
            }
        } else {
            log.info("向量库已就绪，共 {} 条向量片段，跳过文档向量化（省下重复的 embedding 调用费用）",
                    embeddingStore.size());
        }

        // ---------- 构造检索器 ----------
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(qwenEmbeddingModel)
                // 每次最多返回几段。数值越大上下文越全，但输入 token 也随之线性增长
                .maxResults(ragConfig.getMaxResults())
                // 相似度阈值：低于它的片段直接丢弃。
                // 这是「宁缺毋滥」的开关——宁可检索不到，也不要塞不相关内容诱发幻觉
                .minScore(ragConfig.getMinScore())
                // 检索器名字，会出现在日志和引用溯源信息里，便于区分多个知识源
                .displayName("ai-code-helper-knowledge-base")
                .build();
    }

    /**
     * 检索增强器：在「检索」之外，额外提供查询改写能力。
     *
     * <h4>为什么不直接用 ContentRetriever，要多包一层？</h4>
     * LangChain4j 把 RAG 的在线流程拆成了几个可替换的插件：
     * <pre>
     *   用户问题
     *      │
     *      ▼
     *   QueryTransformer    ← 改写问题（本方法关心的地方）
     *      │
     *      ▼
     *   QueryRouter         ← 决定去哪个知识库查（单库场景用默认的即可）
     *      │
     *      ▼
     *   ContentRetriever    ← 实际检索（即上面那个 Bean）
     *      │
     *      ▼
     *   ContentAggregator   ← 多路结果合并排序（单路时用默认的即可）
     *      │
     *      ▼
     *   ContentInjector     ← 把内容格式化成提示词
     * </pre>
     * {@code ContentRetriever} 只能插在中间那一环，而 {@code RetrievalAugmentor}
     * 是这条完整流水线的总入口。所以只要涉及「查询改写」，
     * 就必须升级到 RetrievalAugmentor。
     *
     * <h4>查询压缩解决的真实问题</h4>
     * 多轮对话里，用户的追问往往是不完整的：
     * <pre>
     *   用户：Java 怎么入门？
     *   模型：（一段回答）
     *   用户：那要多久？          ← 单独看这句，向量检索完全找不到方向
     * </pre>
     * {@link CompressingQueryTransformer} 会先让模型结合历史把
     * 「那要多久？」补全成「学习 Java 入门需要多长时间？」，
     * 再拿这个完整问题去检索，命中率显著提升。
     * 代价是每次检索多一次模型调用（增加延迟和费用），所以做成了可开关的配置。
     *
     * @param contentRetriever 上面配置好的检索器
     * @return 完整的检索增强流水线
     */
    @Bean
    public RetrievalAugmentor retrievalAugmentor(ContentRetriever contentRetriever) {
        DefaultRetrievalAugmentor.DefaultRetrievalAugmentorBuilder builder =
                DefaultRetrievalAugmentor.builder().contentRetriever(contentRetriever);

        if (properties.getRag().isCompressQuery()) {
            builder.queryTransformer(new CompressingQueryTransformer(qwenChatModel));
            log.info("RAG 查询压缩已启用：多轮追问会先结合历史补全问题再检索");
        }

        // 注意：不要在这里同时设置 queryRouter / contentAggregator，
        // 单知识库场景下框架的默认实现已经够用，多加一层只会增加复杂度
        return builder.build();
    }

    /**
     * 把文档切分、向量化并写入向量库。
     *
     * @param documents      待索引的文档
     * @param embeddingStore 目标向量库
     */
    private void ingest(List<Document> documents, InMemoryEmbeddingStore<TextSegment> embeddingStore) {
        AiHelperProperties.Rag ragConfig = properties.getRag();

        // 切分器：按「段落」切，而不是按固定字数硬切。
        // 按段落切能最大程度保住语义完整性——把一个完整的论点切两半
        // 会导致两段都检索不到、也都读不通
        DocumentByParagraphSplitter splitter =
                new DocumentByParagraphSplitter(ragConfig.getChunkSize(), ragConfig.getChunkOverlap());

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                // 给每个片段「加上文档名前缀」，是个很实用的小技巧：
                // 检索时如果只看片段正文，可能分不清这句话出自《学习路线》还是《面试题》；
                // 加上文件名后，模型能判断信息的来源与适用范围，回答更准确
                .textSegmentTransformer(segment -> TextSegment.from(
                        segment.metadata().getString(Document.FILE_NAME) + "\n" + segment.text(),
                        segment.metadata()))
                .embeddingModel(qwenEmbeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        long start = System.currentTimeMillis();
        // ingest 内部会依次完成：切分 → 批量调用 embedding 接口 → 写入向量库。
        // 这是启动阶段最耗时的一步（本项目实测约 9 秒），且会消耗 embedding 额度
        ingestor.ingest(documents);
        log.info("知识库索引构建完成：{} 个文档 → {} 条向量片段，耗时 {} ms",
                documents.size(), embeddingStore.size(), System.currentTimeMillis() - start);
    }

    /**
     * 把向量库序列化到磁盘，供下次启动直接复用。
     *
     * <p>这是纯粹的「省钱优化」：embedding 接口按 token 计费，
     * 原版每次重启都要把整个知识库重新向量化一遍，
     * 开发阶段一天重启几十次，这些调用全是白花的。
     */
    private void saveSnapshotIfConfigured(InMemoryEmbeddingStore<TextSegment> embeddingStore) {
        String snapshotPath = properties.getRag().getVectorStorePath();
        if (!StringUtils.hasText(snapshotPath)) {
            return;
        }
        try {
            Path path = Paths.get(snapshotPath);
            // 父目录可能不存在（首次运行），必须先创建
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            embeddingStore.serializeToFile(path);
            log.info("向量库快照已保存：{}（下次启动将直接复用，不再重复调用 embedding 接口）",
                    path.toAbsolutePath());
        } catch (Exception e) {
            // 存快照失败不影响本次运行，只是下次启动还得重新向量化，记个警告即可
            log.warn("保存向量库快照失败，不影响本次运行: {}", e.getMessage());
        }
    }

    /**
     * 加载知识库文档。
     *
     * <h3>⚠ 这里修掉了原代码一个「IDE 能跑、打成 jar 必炸」的 Bug</h3>
     * 原代码是这样写的：
     * <pre>
     *   FileSystemDocumentLoader.loadDocuments("src/main/resources/docs")
     * </pre>
     * 它是<b>文件系统相对路径</b>，相对于「进程的工作目录」解析：
     * <ul>
     *   <li>IDE 里运行时，工作目录默认是项目根目录，能找到
     *       {@code src/main/resources/docs}，一切正常；</li>
     *   <li>打成 jar 包后，工作目录变成 jar 所在目录，而
     *       <b>jar 内部根本不存在 src/main/resources 这个路径</b>——
     *       classpath 资源和文件系统路径是两套完全不同的寻址体系。
     *       于是启动直接抛异常，服务起不来。</li>
     * </ul>
     * 这个 Bug 的可怕之处在于：开发阶段完全暴露不出来，直到部署到服务器才炸。
     *
     * <h3>修复方案：区分「类路径」与「文件系统」两种来源</h3>
     * <ul>
     *   <li>配置以 {@code classpath:} 开头 → 用 Spring 的资源解析器从 classpath 读取。
     *       不管资源在 {@code target/classes} 目录里还是在 jar 包内部，都能正确读到，
     *       这就是读取打包资源的正确姿势。</li>
     *   <li>否则 → 当作文件系统目录，交给 LangChain4j 的
     *       {@link FileSystemDocumentLoader} 处理。适合「知识库和程序分开部署、
     *       由运维单独维护」的场景——这样改文档不用重新打包。</li>
     * </ul>
     *
     * @return 加载到的文档列表
     */
    private List<Document> loadDocuments() {
        String location = properties.getRag().getDocsPath();
        List<Document> documents = new ArrayList<>();

        if (!StringUtils.hasText(location)) {
            return documents;
        }

        if (location.startsWith("classpath:")) {
            // classpath*: 的 ** 通配符交由 Spring 的资源解析器处理。
            // 拼上 /**/* 以递归匹配子目录中的所有文件
            String pattern = location + "/**/*";
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            try {
                // 这里必须写全限定名：org.springframework.core.io.Resource
                // 与本文件顶部 import 的 jakarta.annotation.Resource 重名
                org.springframework.core.io.Resource[] resources = resolver.getResources(pattern);
                for (org.springframework.core.io.Resource resource : resources) {
                    // 目录本身也会被匹配出来（isReadable 为 false），需要过滤
                    if (!resource.isReadable() || resource.getFilename() == null) {
                        continue;
                    }
                    String text = resource.getContentAsString(StandardCharsets.UTF_8);
                    if (text.isBlank()) {
                        continue;
                    }
                    // 元数据里记录文件名，供切分阶段做「文档名前缀」使用
                    Metadata metadata = new Metadata().put(Document.FILE_NAME, resource.getFilename());
                    documents.add(Document.from(text, metadata));
                }
                log.info("从 classpath 加载知识库文档 {} 个，匹配模式={}", documents.size(), pattern);
            } catch (IOException e) {
                log.error("从 classpath 加载文档失败，模式={}", pattern, e);
            }
        } else {
            // 文件系统目录方式
            try {
                documents = FileSystemDocumentLoader.loadDocuments(location);
                log.info("从文件系统加载知识库文档 {} 个，目录={}", documents.size(), location);
            } catch (Exception e) {
                log.error("从文件系统加载文档失败，目录={}", location, e);
            }
        }

        return documents;
    }
}
