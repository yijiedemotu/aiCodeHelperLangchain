# ai-code-helper：LangChain4j 升级、优化与实测验证报告

> 环境：Spring Boot 3.5.7 / Java 21 / LangChain4j 1.20.0（beta 模块 1.20.0-beta30）
> 模型：阿里云百炼 DashScope（qwen-max + text-embedding-v4）
> 验证方式：打包成 fat jar 后真实启动，用 HTTP 实测全部接口

---

## 一、这次到底改了什么

### 1.1 依赖升级（`pom.xml`）

| 项目 | 升级前 | 升级后 |
|---|---|---|
| `langchain4j.version` | 1.1.0 | **1.20.0** |
| `langchain4j.beta.version` | 1.1.0-beta7 | **1.20.0-beta30** |
| MCP 客户端 | `spring-ai-mcp-client-webflux-spring-boot-starter`（**Spring AI 的 SDK**） | `langchain4j-mcp`（**LangChain4j 自己的 SDK**） |
| 新增 | — | `langchain4j-agentic`、`langchain4j-guardrails` |

> **修掉的第一个坑**：原 `pom.xml` 引的是 Spring AI 的 MCP 客户端，而代码里 `import io.modelcontextprotocol.client.McpClient` 与 LangChain4j 的 API 混用，两套 SDK 类型不通，编译直接失败。

### 1.2 新增文件

| 文件 | 作用 | 对应的 LangChain4j 能力 |
|---|---|---|
| `config/AiHelperProperties.java` | 把散落各处的硬编码参数收拢成 `ai-helper.*` 配置 | Spring Boot `@ConfigurationProperties` |
| `ai/memory/FileChatMemoryStore.java` | 会话记忆落盘（重启不丢），含路径穿越防护 | `ChatMemoryStore` |
| `ai/memory/SanitizingChatMemoryStore.java` | **本次实测发现缺陷后新增**：写入前剔除 RAG 检索脚手架 | 装饰器模式包装 `ChatMemoryStore` |
| `ai/guardrail/InputLengthGuardrail.java` | 输入长度护栏，超长直接拒绝 | `InputGuardrail`（1.9.0+） |
| `ai/guardrail/SensitiveWordOutputGuardrail.java` | 输出屏蔽词掩码替换 | `OutputGuardrail` |
| `ai/GuardedAssistant.java` | 带护栏的无状态同步问答服务 | `AiServices` + `inputGuardrails/outputGuardrails` |
| `ai/ReportAssistant.java` | **本次实测发现缺陷后新增**：无状态结构化输出服务 | `AiServices`（不配记忆） |
| `ai/agentic/StudyRoutePlanner.java` 等 3 个 | 多 Agent 工作流的三段式子 Agent | `@Agent` 注解（`langchain4j-agentic`） |
| `ai/agentic/AgenticWorkflowConfig.java` | 把 3 个 Agent 组装成串行工作流 | `AgenticServices.sequenceBuilder()` |

### 1.3 加了详细中文注释的既有文件

`AiController`、`AiCodeHelperServiceFactory`、`AiCodeHelperService`、`AiCodeHelper`、
`RagConfig`、`McpConfig`、`InterviewQuestionTool`、`AiCodeHelperApplication`、`CorsConfig`、
`application.yml`。

---

## 二、实测发现的三个真实缺陷（已修复）

这三个都不是「风格问题」，而是会在生产环境造成实际损失的问题。

### 缺陷 1：跨用户记忆串号（隐私泄露）⚠️ 严重

**现象**：`chat-data/memory/default.json` 里混进了来自不同接口、不同用户的请求：

```
chat-data/memory/default.json   (15 KB)
├─ USER  "你好"                              ← /ai/chat-sync
├─ USER  "用一句话介绍 Java 的垃圾回收机制"     ← /ai/chat-sync
└─ USER  "我想学 Java 后端开发，请给我 3 条建议" ← /ai/report
```

启动日志也直接印证——`/report` 请求携带的上下文消息数在逐步累积：**2 → 4 → 6 → 8 条**。

**根因**：LangChain4j 的规则是「没有 `@MemoryId` 的方法一律落到名为 `default` 的那一份记忆」，
而这份记忆是**进程级、所有用户共用**的。原代码的 `chatForReport(String)` 和
`GuardedAssistant.chat(String)` 都没有 `@MemoryId`，却都挂在配了记忆的 AiService 上。

后果不只是「上下文有点乱」：**用户 A 问过的内容会作为历史出现在用户 B 的请求里，
模型完全可能把它复述出来**。单机单人测试完全发现不了，上线多用户才暴露。

**修法**：记忆挂在 AiService 级别、无法按方法开关，所以只能做**接口隔离**：

```
AiCodeHelperService   → 有状态：只有 chatStream（带 @MemoryId，每个会话独立记忆）
GuardedAssistant      → 无状态：不配记忆
ReportAssistant       → 无状态：不配记忆（新接口，承接原 chatForReport）
```

**验证证据**：把 `default.json` 重置为空 `[]` 后，分别调用 `/chat-sync` 与 `/report`（均 200），
该文件**仍为 `[]`（2 字节）**，完全没有被写入。
修复后 `/report` 请求的上下文消息数稳定为 **2 条**（系统提示 + 用户消息）。

### 缺陷 2：RAG 检索内容污染会话历史（成本放大）

**现象**：问一句「请介绍一下 ZGC 垃圾回收器」，记忆文件里存下的 USER 消息却是：

```
请介绍一下 ZGC 垃圾回收器，200 字以内。

Answer using the following information:
Java 编程学习路线.md
- ZGC：[https://juejin.cn/...]（新一代垃圾回收器）
...（整段知识库原文）
```

**根因**：`DefaultContentInjector` 把检索结果拼到了 UserMessage 之后，
而记忆层把「发给模型的消息」原样保存了下来。

**三层损失**：
1. 记忆文件体积失控——单轮就有 2.6 KB，且 `FileChatMemoryStore` 每轮全量读写；
2. **token 成本逐轮放大**——这段文档会在后续每一轮作为历史重新发给模型，而检索是每轮都做的，上下文滚雪球；
3. 语义污染——模型会看到 `Answer using the following information:` 这种指令性文字被当成对话内容模仿。

**修法**：新增 `SanitizingChatMemoryStore`，在**写入路径**上剔除注入内容（装饰器模式，
文件存储与内存存储都能复用）。

**验证证据**：
- 落盘日志：`已净化会话记忆中的 RAG 注入内容：memoryId=99003，剔除 1857 字符（1881 → 24）`
- 修复后 `99003.json` 的 USER 消息就是用户原话 `请介绍一下 ZGC 垃圾回收器，200 字以内。`（24 字符）
- 同一测试的**记忆文件从 2650 字节降到 1590 字节**
- 而模型的回答**依旧引用了知识库里的 ZGC 资料与链接**——检索照样喂给模型，只是不再进历史

### 缺陷 3：一个「静默失效」的配置

**现象**：`RagConfig` 里精心构造的 `retrievalAugmentor` Bean **从来没有被任何人使用**，
只是在 Spring 容器里空转。后果是 `ai-helper.rag.compress-query=true` 这个开关
**完全不生效，且不报任何错**。

**根因**：`AiCodeHelperServiceFactory` 用的是 `.contentRetriever(...)`，
而查询改写能力只在 `RetrievalAugmentor` 上。两者是「零件」与「整机」的关系。

**修法**：有状态服务改用完整的 `.retrievalAugmentor(...)`（有历史，查询压缩才有意义）；
无状态服务仍用轻量的 `.contentRetriever(...)`。

---

## 三、全部能力的实测结果

所有请求均为**真实调用大模型**（会产生费用），服务以 `java -jar` 方式启动于 8081 端口。

### 3.1 原有能力回归

| 接口 | 结果 | 关键数据 |
|---|---|---|
| `GET /api/ai/chat`（SSE 流式） | ✅ 200 | 48~51 个数据块，首字 ~9ms，总耗时 6.1s |
| 多轮记忆（同一 memoryId） | ✅ | 第 1 轮「我叫小明…」→ 第 2 轮准确答出「你叫小明，你最喜欢的编程语言是Java」 |
| RAG 检索 | ✅ | 回答引用了知识库中的 ZGC 资料与链接 |
| 向量库快照复用 | ✅ | 恢复 154 条片段、跳过重复 embedding，**启动耗时从 10.7s 降到 2.25s** |
| 工具调用 | ✅ | 启动日志可见 `interviewQuestionSearch` 工具已注入请求 |

### 3.2 新增能力

| 接口 / 能力 | 结果 | 关键数据 |
|---|---|---|
| `GET /api/ai/chat-sync`（护栏） | ✅ 200 / 1.3s | `{"success":true,"answer":"收到"}` |
| 输入长度护栏（拦截） | ✅ | 2500 字 → 拦截，**212ms 返回且未调用模型**，提示语清晰 |
| 输入长度护栏（放行） | ✅ | 1900 字 → 正常回答（2.7s） |
| 输出屏蔽词护栏 | ✅ | 配置屏蔽词「的」后，回答中全部变为 `**`，零残留 |
| `GET /api/ai/report`（结构化输出） | ✅ 200 / 4.4s | 直接返回 `{"name":..., "suggestionList":[...]}` |
| `GET /api/ai/study-plan`（多 Agent 工作流） | ✅ 200 / 46.8s | 3 次模型调用串行，产出同时含学习路线与面试题的完整方案 |

### 3.3 一个值得记录的测试经验

用 3000 个**中文**字符测试输入护栏时得到 HTTP 400 而不是护栏拦截。
查启动日志发现真正的报错是：

```
java.lang.IllegalArgumentException: Request header is too large
```

中文经 URL 编码后每字占 9 字节，3000 字 = 27 KB，**先被 Tomcat 的 8 KB 请求头上限挡在了门外**，
根本没到应用层。改用 2500 个 ASCII 字符后，护栏逻辑才被真正触发。

> 这提醒我们：GET 传长文本本身就不合适，应改用 POST + 请求体。

---

## 四、后续还可以做的优化

按「投入产出比」从高到低：

1. **换成 POST + JSON 请求体** —— 现在长文本走 GET query，会撞 Tomcat 的请求头上限（8 KB）。
2. **向量库换成持久化实现** —— `InMemoryEmbeddingStore` 的数据在 JVM 堆里，
   现在靠序列化快照兜底（且每次启动要整体加载 2.1 MB JSON）。
   数据量上去后应换 `langchain4j-community-redis` 或 PGVector。
3. **记忆存储换成 Redis** —— 现在的文件实现「同一 memoryId 并发写会互相覆盖」，
   多实例部署时也会因为各自本地文件而出现记忆分裂。
4. **护栏扩展到流式接口** —— 目前流式接口没有输出护栏（逐块推送下无法拦截），
   若合规要求高，可改为「先缓冲后校验再推送」（牺牲首字延迟换安全）。
5. **结构化输出加 `Result<T>` 包装** —— 可以拿到 token 消耗、命中的检索片段、
   执行的工具等元信息，便于做成本监控与效果分析。
6. **`chat-sync` 加限流** —— 现在只有长度护栏，缺「单位时间内请求次数」限制，
   仍可被刷量。
7. **接入 MCP** —— `McpConfig` 已改造完成但默认关闭（`ai-helper.mcp.enabled=false`），
   配置一个 MCP Server 地址即可让模型使用远程工具（如联网搜索），无需改代码。

---

## 五、如何运行

```bash
# 1. 打包（跳过测试）
cd ai-code-helper
set JAVA_HOME=C:\Users\Phili\.jdks\ms-21.0.9
mvn -DskipTests clean package

# 2. 启动（显式指定端口，避免被注入的环境变量覆盖）
java -jar target/ai-code-helper-0.0.1-SNAPSHOT.jar --server.port=8081
```

密钥放在 `src/main/resources/application-local.yml`（已被 `.gitignore` 忽略），
由 `application.yml` 中的 `spring.profiles.active=local` 自动加载。

四个接口（`server.servlet.context-path=/api`）：

```
GET /api/ai/chat?memoryId=1&message=xxx        流式对话（SSE）
GET /api/ai/chat-sync?message=xxx              带护栏的同步问答
GET /api/ai/report?message=xxx                 结构化输出
GET /api/ai/study-plan?topic=xxx               多 Agent 学习方案
```
