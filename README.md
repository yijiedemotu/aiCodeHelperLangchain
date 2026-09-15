# AI 编程小助手（aiCodeHelper）

一个用于学习 **LangChain4j** 的 AI 编程问答小助手，前后端分离：

- `ai-code-helper/` —— Spring Boot 后端，基于 LangChain4j 的 `AiServices` 声明式装配，接入通义千问（qwen-max）
- `aiHelper/` —— Vue 3 + Vite 前端，SSE 流式渲染对话

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.5.7 / Java 21 / LangChain4j 1.1.0 / 通义千问 qwen-max |
| 前端 | Vue 3.5 / Vite 6 / axios |
| 通信 | Server-Sent Events（`Flux<ServerSentEvent<String>>`） |

## 已实现的 LangChain4j 能力

- **流式对话**：`GET /ai/chat`，SSE 逐字返回
- **多会话记忆**：按 `memoryId` 隔离对话上下文
- **RAG 检索增强**：`ai/rag/RagConfig`，读取 `src/main/resources/docs` 下的 Markdown 知识库
- **工具调用（Function Calling）**：`ai/tools/InterviewQuestionTool`（抓取面试题）
- **MCP 接入**：`ai/mcp/McpConfig`
- **护栏 / 记忆 / 配置**：`ai/guardrail`、`ai/memory`、`config/AiHelperProperties`

## 本地运行

### 1. 后端（需要 JDK 21）

```bash
cd ai-code-helper
# 先在 src/main/resources/ 下新建 application-local.yml 并填入自己的密钥（该文件已被 .gitignore 忽略）
./mvnw clean package -DskipTests
java -jar target/ai-code-helper-0.0.1-SNAPSHOT.jar --server.port=8081
```

后端启动在 `http://localhost:8081`，上下文路径为 `/api`。

密钥通过 `spring.profiles.active=local` 从 `application-local.yml` 读取（该文件已被 `.gitignore` 忽略）：

```yaml
ALIYUN_API_KEY: sk-xxxxxxxx
ZHIPU_API_KEY: xxxxxxxx.xxxxxxxx
```

### 2. 前端

```bash
cd aiHelper
npm install
npm run dev   # http://localhost:5173
```

Vite 已将 `/api` 代理到 `http://localhost:8081`。

## 接口

```
GET /api/ai/chat?memoryId=<会话ID>&message=<提问内容>
```

返回 SSE 流，逐块推送模型输出。
