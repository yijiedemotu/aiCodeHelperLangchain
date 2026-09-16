package com.yupi.aicodehelper.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.aicodehelper.ai.AiCodeHelperService;
import com.yupi.aicodehelper.ai.GuardedAssistant;
import com.yupi.aicodehelper.ai.ReportAssistant;
import com.yupi.aicodehelper.ai.guardrail.InputGuardService;
import com.yupi.aicodehelper.ai.guardrail.InputRejectedException;
import com.yupi.aicodehelper.ai.guardrail.InputTooLongException;
import com.yupi.aicodehelper.controller.dto.ChatRequest;
import com.yupi.aicodehelper.controller.dto.TextRequest;
import com.yupi.aicodehelper.controller.dto.TopicRequest;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.guardrail.GuardrailException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 能力对外的 HTTP 入口。
 *
 * <h3>为什么返回 Flux（响应式流）？</h3>
 * Spring MVC 传统上返回普通对象，响应体一次性写回。而大模型生成一段回答要若干秒，
 * 如果等生成完再返回，用户要盯着空屏等很久。
 * <p>Spring MVC 5+ 支持「响应式返回值」：当方法返回 {@code Flux} 时，
 * 框架不会把整个流缓存下来，而是<b>每收到一个元素就立刻 flush 到 HTTP 响应</b>，
 * 直到流结束才关闭连接。配合前端的 {@code EventSource}，
 * 就实现了「打字机效果」的 SSE（Server-Sent Events）流式输出。
 * <p>注意：这里的「响应式」更多是关于<b>返回值处理方式</b>，
 * Tomcat 线程模型本身仍是阻塞的（本项目跑在 Tomcat 上，不是 Netty）。
 * 真正的全异步响应式需要 WebFlux，对本场景并非必需。
 *
 * <h3>接口一览（每个能力都有 GET 与 POST 两种形态）</h3>
 * <table border="1">
 *   <caption>四个接口的定位差异</caption>
 *   <tr><th>接口</th><th>返回形式</th><th>适用场景</th></tr>
 *   <tr><td>/ai/chat</td><td>SSE 流式</td>
 *       <td>日常聊天，追求「秒出字」的体验</td></tr>
 *   <tr><td>/ai/chat-sync</td><td>同步文本</td>
 *       <td>需要内容安全管控的场景（受输入/输出护栏保护）</td></tr>
 *   <tr><td>/ai/report</td><td>结构化对象</td>
 *       <td>需要程序化处理结果，而不是展示给人看</td></tr>
 *   <tr><td>/ai/study-plan</td><td>多 Agent 工作流结果</td>
 *       <td>复杂任务，需要多个步骤接力完成</td></tr>
 * </table>
 *
 * <h3>为什么同时保留 GET 与 POST？</h3>
 * <b>POST 是推荐用法</b>：实测发现长文本走 GET 会撞 Tomcat 默认的
 * 8KB 请求头上限（中文 URL 编码后每字 9 字节，约 900 字就 400）。
 * POST + JSON 请求体没有这个限制，且配合 {@code fetch + AbortController}
 * 还能实现「停止生成」。
 * <b>GET 保留仅作演示与调试</b>：它可以直接粘进浏览器地址栏、
 * 也能被原生 {@code EventSource} 消费，适合快速验证后端是否连通。
 * <p>完整访问路径需要加上 {@code server.servlet.context-path}，即
 * {@code http://localhost:8081/api/ai/...}
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@Tag(name = "AI 能力接口", description = "流式对话、护栏问答、结构化输出、多 Agent 学习方案")
public class AiController {

    @Resource
    private AiCodeHelperService aiCodeHelperService;

    /**
     * 带护栏的同步问答服务。
     *
     * <p>它和上面的主服务是两个不同的 Bean，差别就在「有没有挂护栏」。
     * 把这件事在 Controller 层显式区分开，调用方能一眼看出「这个接口是有安全校验的」。
     */
    @Resource
    private GuardedAssistant guardedAssistant;

    /**
     * 结构化输出服务（无状态）。
     *
     * <p>它原先只是 {@code AiCodeHelperService} 上的一个方法，现已独立成接口。
     * 原因是实测发现它没有 {@code @MemoryId}，却和有状态的流式服务共用
     * 一个名为 "default" 的记忆桶，造成跨用户上下文互相污染。
     * 拆出来之后这个接口不配任何记忆，每次调用都是干净的。
     * 详见 {@link ReportAssistant} 的类注释。
     */
    @Resource
    private ReportAssistant reportAssistant;

    /**
     * 多 Agent 工作流（学习方案生成）。
     * 类型是 UntypedAgent，调用方式是 {@code invoke(Map<String, Object>)}。
     */
    @Resource
    private UntypedAgent studyPlanWorkflow;

    /**
     * 输入校验闸门（长度 + 提示词注入）。
     *
     * <h4>为什么两条路径都需要它，而不是交给 AiService 的护栏？</h4>
     * 起初只有流式接口用它，同步接口依赖 AiService 上的 InputGuardrail。
     * 实测证明后者是错的：<b>AiService 级别的输入护栏量到的是 RAG 注入之后的文本</b>，
     * 于是正常问题会被误判——
     * <pre>
     *   长度护栏：输入 "Java"（4 字符）报「当前 2162 字符」被拒
     *   注入护栏：输入 "Java"、"什么是 JVM" 被判为提示词注入
     * </pre>
     * 两者病根相同：把系统自己检索拼入的内容算在了用户头上。
     * 因此同步与流式现在都统一走本闸门，校验的是用户<b>原始输入</b>。
     *
     * <p>流式接口另有必须前置的理由：它的响应头在第一个数据块发出时
     * 就固定为 200，中途失败无法再表达成「参数错误」。
     * 完整分析见 {@link InputGuardService} 与
     * {@link com.yupi.aicodehelper.ai.AiCodeHelperServiceFactory#guardedAssistant()}。
     */
    @Resource
    private InputGuardService inputGuardService;

    /**
     * JSON 序列化器，用于把结构化流式事件（TokenStream 的回调）转成 SSE 的数据帧。
     *
     * <h4>为什么流式事件不直接拼字符串，而要走 JSON？</h4>
     * 因为 SSE 的数据帧格式是 {@code data:<内容>\n\n}——<b>换行就是帧边界</b>。
     * 模型输出的文本里一旦出现换行（几乎必然出现，代码块更是多行），
     * 直接拼进去会把一条消息劈成多条、破坏 SSE 协议。
     *
     * <p>交给 Jackson 序列化后，字符串里的换行会变成转义形式 {@code \n}，
     * 永远只占一行，帧边界就稳了。这是「不要自己拼 JSON」的一个具体理由，
     * 而不只是风格问题。
     *
     * <p>这个 Bean 由 Spring Boot 的 Jackson 自动配置提供，
     * 与 Spring MVC 序列化响应体用的是同一个实例——
     * 因此日期格式、命名策略等配置天然一致，不会出现
     * 「响应体里是一种格式、流式事件里是另一种」的割裂。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 流式对话（GET + SSE）。
     *
     * <p>仅作演示与调试用（可直接粘进浏览器地址栏，或被原生 EventSource 消费）。
     * 生产请用下方的 POST 版——见 {@link ChatRequest} 的类注释。
     *
     * @param memoryId 会话标识。前端存在 localStorage 里，保证同一用户的多轮对话共享上下文。
     *                 注意它只用于定位记忆，不会作为消息内容发给模型。
     * @param message  用户问题
     * @return SSE 事件流，每个事件携带一小段生成的文本
     */
    @GetMapping("/chat")
    @Operation(summary = "流式对话（GET，仅调试用）",
            description = """
                    以 SSE 逐字返回模型回答。**生产请用 POST 版**：长文本走 GET 会撞 \
                    Tomcat 默认 8KB 请求头上限（返回 400 Request header is too large）， \
                    且原生 EventSource 无法主动中止。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 事件流，每个事件承载一小段文本"),
            @ApiResponse(responseCode = "400", description = "参数缺失或类型错误（如 memoryId 不是整数）")
    })
    public Flux<ServerSentEvent<String>> chat(
            @Parameter(description = "会话标识，用于隔离不同会话的记忆", example = "123456")
            @RequestParam int memoryId,
            @Parameter(description = "用户问题", example = "什么是 Java 的垃圾回收？")
            @RequestParam String message) {
        return streamChat(memoryId, message);
    }

    /**
     * 流式对话（POST + SSE）—— 生产推荐用法。
     *
     * <p>与 GET 版返回完全相同的事件流，唯一的区别是入参从 URL query 换成 JSON 请求体。
     * 这一处改变解决了两个实际问题：
     * <ol>
     *   <li>长文本不再受 Tomcat 8KB 请求头上限约束；</li>
     *   <li>前端可用 {@code fetch + AbortController} 消费，从而获得「停止生成」能力。</li>
     * </ol>
     *
     * <h4>⚠ SSE 接口为什么必须显式声明 produces？</h4>
     * 因为加了 {@code @Valid} 之后，这个方法的参数会被 Spring 的校验器处理，
     * 而返回值是 {@code Flux}——Spring MVC 需要一条明确线索才知道该用
     * 「流式」的消息转换器而不是「一次性序列化 JSON」的那个。
     * 显式写上 {@code produces = TEXT_EVENT_STREAM_VALUE} 既消除了歧义，
     * 也让接口文档里能正确显示响应类型。
     *
     * @param request 含 memoryId 与 message 的请求体
     * @return SSE 事件流
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话（POST，推荐）",
            description = """
                    以 SSE 逐字返回模型回答，请求体传参不受 URL 长度限制。 \
                    前端用 fetch + ReadableStream 消费即可获得「停止生成」能力。 \
                    输入长度上限由 ai-helper.guardrail.max-input-length 控制， \
                    超限时不会调用模型，而是直接在流内推送一条系统提示。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 事件流",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            examples = @ExampleObject(value = "data:你好\n\ndata:，我是 AI 编程小助手\n\n"))),
            @ApiResponse(responseCode = "400", description = "请求体校验失败（message 为空等）")
    })
    public Flux<ServerSentEvent<String>> chatPost(
            @Valid @RequestBody ChatRequest request) {
        // memoryId 可能为 null（前端忘记传），此时给一个默认会话，避免 NPE。
        // 用 defaultMemoryId 兜底比直接报错更友好——流式接口一旦报错，前端拿到的
        // 是空响应而不是可读提示。
        int memoryId = request.memoryId() == null ? 0 : request.memoryId();
        return streamChat(memoryId, request.message());
    }

    /**
     * GET 与 POST 共用的流式处理管线。
     *
     * <p>抽出来的原因很简单：两套入口的业务逻辑完全一致，
     * 只有「参数怎么进来」不同。把真正干活的代码收敛到一处，
     * 将来改流式行为（比如加过滤器、改错误提示文案）只需动一个地方。
     *
     * @param memoryId 会话标识
     * @param message  用户问题
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> streamChat(int memoryId, String message) {
        log.info("收到流式对话请求，memoryId={}，消息长度={}", memoryId, message.length());

        /*
         * 输入校验前置（流式接口特有的一道闸门）。
         *
         * 必须在调用 aiCodeHelperService 之前执行，原因有两条：
         *   ① 流式响应头一旦发出就固定为 200，之后再失败无法表达成「参数错误」；
         *   ② RAG 检索在调用模型前就会发生（要调 embedding 接口，按 token 计费），
         *      提前拦下可以让这些开销完全为零。
         *
         * 校验失败时【不抛出】，而是把它变成一条 SSE 数据帧。
         * 这个选择需要解释：本接口的响应类型已经是 text/event-stream，
         * 若此时抛出异常走全局异常处理器，返回的会是 JSON 错误体——
         * 前端按 SSE 解析只会得到空内容，用户看到一片空白。
         * 保持「响应体永远是 SSE 帧」这一契约，是流式接口的设计原则：
         * 错误也走同一条流，前端只需处理一种格式。
         */
        try {
            inputGuardService.validate(message);
        } catch (InputTooLongException | InputRejectedException e) {
            // 两类拒绝（超长 / 疑似注入）在流式接口里用同样的方式表达：
            // 一条 [系统提示] 帧。前端据此渲染成系统消息，而不是模型回答。
            // 合并捕获是因为对调用方而言「被拦下了 + 原因」这一信息结构完全一致，
            // 区别只体现在文案里，没必要写两个分支
            log.warn("流式对话输入校验未通过，未调用模型。memoryId={}，长度={}，原因={}",
                    memoryId, message.length(), e.getMessage());
            return Flux.just(ServerSentEvent.<String>builder()
                    .data("\n\n[系统提示] " + e.getMessage())
                    .build());
        }

        return aiCodeHelperService.chatStream(memoryId, message)
                // 把每个文本分块包装成 SSE 事件。
                // 事件格式为 "data:<内容>\n\n"，前端靠这个格式解析
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build())
                /**
                 * 关键的错误兜底。
                 *
                 * <p>流式接口有个特殊难点：<b>响应头在第一个分块发出时就已经提交了</b>，
                 * 此时 HTTP 状态码已定死为 200。如果中途出错，不能再改成 500，
                 * 也无法回滚。所以只能在流内部「补发一条说明性消息」，
                 * 让前端把它渲染成一句提示。
                 *
                 * <p>不加这个 onErrorResume 会怎样？框架会把异常直接抛给容器，
                 * 连接被强制断开，前端只会看到「连接意外关闭」，
                 * 用户完全不知道发生了什么——这是流式接口最常见的体验缺陷。
                 */
                .onErrorResume(error -> {
                    log.error("流式对话发生异常，memoryId={}", memoryId, error);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .data("\n\n[系统提示] 本次回答生成中断，请稍后重试。")
                            .build());
                });
    }

    /**
     * 带护栏的同步问答。
     *
     * <p>为什么这个接口需要 try-catch，而流式接口用 onErrorResume？
     * 因为它是同步返回的：护栏失败会抛异常，异常在「响应尚未开始写出」时抛出，
     * 此时还能正常地返回一个带说明的响应体（而不是把 500 错误页丢给用户）。
     * 这是同步接口相比流式接口的一个优势——错误处理更从容。
     *
     * @param message 用户问题
     * @return 成功时返回回答；被护栏拦截时返回拦截原因
     */
    @GetMapping("/chat-sync")
    @Operation(summary = "护栏问答（GET，仅调试用）", description = "同步返回，受输入/输出双向护栏保护")
    public Map<String, Object> chatSync(
            @Parameter(description = "用户问题", example = "什么是 Java 的垃圾回收？")
            @RequestParam String message) {
        return runChatSync(message);
    }

    /**
     * 带护栏的同步问答（POST）—— 推荐用法，原因同 {@link ChatRequest} 的类注释。
     */
    @PostMapping("/chat-sync")
    @Operation(summary = "护栏问答（POST，推荐）",
            description = """
                    同步返回的受控问答。输入侧拦截超长输入与提示词注入， \
                    输出侧对屏蔽词做掩码替换（而不是拒绝回答）。 \
                    被护栏拦截时返回 HTTP 200 + `blocked=true`， \
                    这是**业务层面的正常拒绝**，不是服务故障。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "问答结果"),
            @ApiResponse(responseCode = "400", description = "message 为空")
    })
    public Map<String, Object> chatSyncPost(
            @Valid @RequestBody TextRequest request) {
        return runChatSync(request.message());
    }

    /**
     * 护栏问答的公共逻辑（GET 与 POST 共用）。
     *
     * <h3>为什么这里的错误字段叫 blocked/reason，而不是 error/message？</h3>
     * 因为它表达的是<b>业务层面的正常拒绝</b>，而 {@code GlobalExceptionHandler}
     * 那套 {@code error/message} 表达的是<b>协议层面的错误</b>。两者刻意区分：
     * <pre>
     *   护栏拦截 → HTTP 200 + {success:false, blocked:true, reason:...}
     *              「系统工作正常，只是这个问题我不回答」——不该被监控当成故障
     *   参数错误 → HTTP 400 + {success:false, error:..., message:...}
     *              「你的请求本身不合法」——需要调用方修正
     * </pre>
     * 不过为了让调用方少写分支，这里额外补了一个 {@code error} 错误码字段，
     * 与异常处理器保持同一个字段名。这样前端可以统一按
     * {@code success → error 是否存在 → 再取 reason/message} 的顺序处理，
     * 不必因为「这是哪一类错误」而写两套逻辑。
     */
    private Map<String, Object> runChatSync(String message) {
        /*
         * 输入校验（与流式接口共用同一道闸门）：长度 + 提示词注入。
         *
         * ⚠ 这两项校验【必须在这里】做，不能下放到 AiService 的护栏上。
         * 实测踩到的坑：AiService 级别挂的 InputGuardrail 量到的不是用户原文，
         * 而是 RAG 检索结果注入之后的文本。于是：
         *   · 长度护栏把 "Java"（4 字符）报成「2162 字符」而拒绝；
         *   · 注入护栏被检索到的技术文档里的结构化片段触发，
         *     把 "Java"、"什么是 JVM" 判成攻击话术。
         * 两者病根相同：把系统自己拼进去的内容算在了用户头上。
         * 完整记录见 AiCodeHelperServiceFactory#guardedAssistant 与
         * InputGuardService 的类注释。
         *
         * 放在入口处的附带收益：校验发生在检索之前，
         * 连 embedding 调用（按 token 计费）都省掉了。
         */
        inputGuardService.validate(message);

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String answer = guardedAssistant.chat(message);
            result.put("success", true);
            result.put("answer", answer);
        } catch (GuardrailException e) {
            // 走到这里说明是【输出护栏】失败（输入校验已前置到入口，见方法开头）。
            // 输出护栏是「模型已经答完、返回前被处理」，属于业务正常流程，
            // 不该记 error 级别日志，也不该返回 500。
            //
            // 注意 reason 直接取 e.getMessage()：输出护栏抛出的消息本身就带框架前缀，
            // 这里保留它是刻意的——输出侧异常应当被当作「系统行为」记录与排查，
            // 与入口处那些「用户输入问题」的干净文案不同。
            // 若将来输出护栏也需要面向用户展示，应当在护栏内部就产出干净文案，
            // 而不是在这里做字符串清洗（原因见 InputRejectedException 的注释）。
            log.warn("输出护栏拦截：{}", e.getMessage());
            result.put("success", false);
            result.put("blocked", true);
            result.put("error", "BLOCKED_BY_GUARDRAIL");
            result.put("reason", e.getMessage());
        } catch (Exception e) {
            // 其他异常才是真正的故障（模型服务不可用、网络超时等）
            log.error("同步对话发生未预期异常", e);
            result.put("success", false);
            result.put("blocked", false);
            result.put("error", "INTERNAL_ERROR");
            result.put("reason", "服务暂时不可用，请稍后重试。");
        }
        return result;
    }

    /**
     * 结构化输出示例：让模型直接返回一个 Java 对象。
     *
     * <p>返回的 JSON 形如：
     * <pre>
     * {
     *   "name": "邵冠铭",
     *   "suggestionList": ["先掌握 Java 基础语法", "再做一个小项目", "..."]
     * }
     * </pre>
     * 注意这里没有写任何 JSON 解析代码——框架把返回类型
     * {@code ReportAssistant.Report} 转成 JSON Schema 发给模型，
     * 模型按 Schema 输出，框架再反序列化。这比自己写正则抠 JSON 可靠得多。
     *
     * <p><b>类名变更说明</b>：返回类型原先是 {@code AiCodeHelperService.Report}，
     * 现已随方法一起迁移到 {@link ReportAssistant}。这不只是搬家——
     * 迁移的根本目的是让这个接口<b>不挂记忆</b>，从而消除跨用户串号。
     *
     * @param message 用户的学习需求
     * @return 结构化的学习建议对象
     */
    @GetMapping("/report")
    @Operation(summary = "结构化输出（GET，仅调试用）")
    public ReportAssistant.Report report(
            @Parameter(description = "用户的学习需求", example = "我想学 Java 后端开发")
            @RequestParam String message) {
        log.info("收到结构化输出请求，消息长度={}", message.length());
        return reportAssistant.chatForReport(message);
    }

    /**
     * 结构化输出（POST）—— 推荐用法。
     */
    @PostMapping("/report")
    @Operation(summary = "结构化输出（POST，推荐）",
            description = """
                    让模型直接返回符合 Java record 结构的 JSON， \
                    由框架完成 JSON Schema 约束与反序列化，无需手写解析代码。 \
                    注意本接口**不挂载会话记忆**，每次调用都是独立的（刻意的设计， \
                    用于消除跨用户记忆串号）。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "结构化的学习建议对象"),
            @ApiResponse(responseCode = "400", description = "message 为空")
    })
    public ReportAssistant.Report reportPost(
            @Valid @RequestBody TextRequest request) {
        String message = request.message();
        log.info("收到结构化输出请求（POST），消息长度={}", message.length());
        return reportAssistant.chatForReport(message);
    }

    /**
     * 多 Agent 工作流：生成完整学习方案。
     *
     * <p>内部会串行触发 3 次大模型调用（规划路线 → 推荐面试题 → 整合建议），
     * 所以这个接口的响应时间明显长于普通问答（约 3 倍）。
     * 前端调用时应显示「正在生成中」的加载态，并适当放宽超时设置。
     *
     * <p>关于入参 Map 的键名：必须是 {@code "topic"}，
     * 因为工作流中第一个 Agent 的方法参数就叫 {@code topic}，
     * 框架靠参数名做绑定。写成别的键名会抛
     * {@code MissingArgumentException}——这是 Agentic 模式最容易踩的坑。
     *
     * @param topic 学习方向，例如「Java 后端开发」
     * @return 包含最终方案与各阶段中间产物的结果
     */
    @GetMapping("/study-plan")
    @Operation(summary = "多 Agent 学习方案（GET，仅调试用）")
    public Map<String, Object> studyPlan(
            @Parameter(description = "学习方向", example = "Java 后端开发")
            @RequestParam String topic) {
        return runStudyPlan(topic);
    }

    /**
     * 多 Agent 工作流（POST）—— 推荐用法。
     */
    @PostMapping("/study-plan")
    @Operation(summary = "多 Agent 学习方案（POST，推荐）",
            description = """
                    串行编排 3 个 Agent（规划路线 → 推荐面试题 → 整合建议）， \
                    内部发生 3 次模型调用，**实测耗时约 45 秒**。 \
                    前端必须显示加载态，并把超时放宽到 60 秒以上 \
                    （`aiHelper/src/api/chat.js` 中设为 300 秒）。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "学习方案结果，含各阶段中间产物与耗时"),
            @ApiResponse(responseCode = "400", description = "topic 为空")
    })
    public Map<String, Object> studyPlanPost(
            @Valid @RequestBody TopicRequest request) {
        return runStudyPlan(request.topic());
    }

    /**
     * 学习方案工作流的公共逻辑（GET 与 POST 共用）。
     */
    private Map<String, Object> runStudyPlan(String topic) {
        log.info("收到学习方案生成请求，topic={}", topic);

        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            // 输入 Map 的 key 必须与第一个 Agent 的参数名一致
            Object plan = studyPlanWorkflow.invoke(Map.of("topic", topic));
            result.put("success", true);
            result.put("topic", topic);
            result.put("plan", plan);
            result.put("elapsedMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("学习方案工作流执行失败，topic={}", topic, e);
            result.put("success", false);
            result.put("topic", topic);
            result.put("reason", "方案生成失败：" + e.getMessage());
            result.put("elapsedMs", System.currentTimeMillis() - start);
        }
        return result;
    }

    // ==========================================================================
    //  以下是「LangChain4j 1.20.0 新返回类型」对应的两个接口
    //  --------------------------------------------------------------------------
    //  上面四个接口的返回类型都是 String / Flux<String> / record —— 都是
    //  「只要结果」的形态。下面两个接口演示另外两种形态：
    //      /chat-with-meta     Result<String>  —— 结果 + 全套元信息（同步）
    //      /chat-stream-events TokenStream     —— 结果 + 过程事件（流式）
    // ==========================================================================

    /**
     * 带元信息的问答（GET，仅调试用）。
     *
     * <p>返回的不只是回答，还有 token 用量、命中的知识库片段、工具调用记录。
     * 详见 {@link AiCodeHelperService#chatWithMeta} 的注释。
     */
    @GetMapping("/chat-with-meta")
    @Operation(summary = "带元信息的问答（GET，仅调试用）",
            description = "同步返回回答 + token 用量 + RAG 检索来源 + 工具调用记录")
    public Map<String, Object> chatWithMeta(
            @Parameter(description = "会话标识", example = "123456") @RequestParam int memoryId,
            @Parameter(description = "用户问题", example = "什么是 Java 的垃圾回收？")
            @RequestParam String message) {
        return runChatWithMeta(memoryId, message);
    }

    /**
     * 带元信息的问答（POST）—— 推荐用法。
     *
     * <h3>这个接口为什么值得存在？</h3>
     * 它回答的是三个运维/产品层面的问题，而这三个问题在只看回答文本时是无法回答的：
     * <pre>
     *   ① 这次问答花了多少 token？          → tokenUsage
     *   ② 模型到底检索到了哪些资料？        → sources
     *   ③ 模型真的调用了工具，还是凭空回答？ → toolExecutions
     * </pre>
     * 前两个直接关系到<b>成本与效果</b>：没有它们，优化提示词和检索参数就只能靠感觉。
     */
    @PostMapping("/chat-with-meta")
    @Operation(summary = "带元信息的问答（POST，推荐）",
            description = """
                    返回结构包含 answer、tokenUsage、sources、toolExecutions、elapsedMs。 \
                    适合做成本监控、RAG 效果评估与引用溯源展示。 \
                    注意这是**同步**接口，要等模型生成完才返回；追求首字速度请用 /chat。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "回答及其元信息"),
            @ApiResponse(responseCode = "400", description = "message 为空")
    })
    public Map<String, Object> chatWithMetaPost(
            @Valid @RequestBody ChatRequest request) {
        int memoryId = request.memoryId() == null ? 0 : request.memoryId();
        return runChatWithMeta(memoryId, request.message());
    }

    /**
     * 元信息问答的公共逻辑（GET 与 POST 共用）。
     *
     * <p>整段代码的核心只有一行——{@code aiCodeHelperService.chatWithMeta(...)}。
     * 剩下全是在把 {@code Result<T>} 里的元信息「翻译」成前端好消费的结构。
     * 注意<b>没有任何 JSON 解析、没有正则抠取</b>：这些信息是框架直接给的。
     */
    private Map<String, Object> runChatWithMeta(int memoryId, String message) {
        // 与其它接口一致：输入校验前置，被拒时连 embedding 都不会调用
        inputGuardService.validate(message);

        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Result<String> answer = aiCodeHelperService.chatWithMeta(memoryId, message);

            result.put("success", true);
            // content() 就是模型回答的纯文本——即便包了一层 Result，取正文也就这一行
            result.put("answer", answer.content());
            result.put("finishReason", answer.finishReason() == null ? null : answer.finishReason().name());
            result.put("tokenUsage", describeTokenUsage(answer.tokenUsage()));
            result.put("sources", describeSources(answer.sources()));
            result.put("toolExecutions", describeToolExecutions(answer.toolExecutions()));
            result.put("elapsedMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("元信息问答执行失败，memoryId={}", memoryId, e);
            result.put("success", false);
            result.put("error", "INTERNAL_ERROR");
            result.put("reason", "服务暂时不可用，请稍后重试。");
            result.put("elapsedMs", System.currentTimeMillis() - start);
        }
        return result;
    }

    /**
     * 结构化流式对话（SSE 推送带类型的事件）—— 对应 {@link TokenStream}。
     *
     * <h3>它与 {@code /chat} 的本质区别：推的是「事件」而不是「文本」</h3>
     * {@code /chat} 只有一条数据通道（文本分块），而模型在生成过程中其实发生了很多事：
     * 检索知识库、调用工具、多轮往返。这些过程信息在 {@code Flux<String>} 里全部丢失。
     * 本接口把它们变成<b>可区分的事件</b>推给前端：
     * <pre>
     *   event: retrieved   data: {"type":"retrieved","count":5,"sources":[...]}
     *   event: tool        data: {"type":"tool","tool":"interviewQuestionSearch",...}
     *   event: token       data: {"type":"token","text":"Java 的"}
     *   event: done        data: {"type":"done","tokens":{...},"finishReason":"STOP"}
     * </pre>
     * 前端据此就能显示「正在检索知识库…」「正在查询面试题…」这类过程提示。
     * <b>用户能忍受慢，但不能忍受不知道系统在干什么</b>——这是流式体验的关键一步。
     *
     * <h3>为什么只有 POST，没有 GET 版？</h3>
     * 本项目其它接口都提供 GET 版仅作调试用，这个接口刻意不提供：
     * 它的请求体（用户提问）同样可能很长，走 query 会撞 Tomcat 的 8KB 请求头上限；
     * 而 GET 版唯一的好处（能直接粘进浏览器地址栏）对于一个需要解析
     * 结构化事件流的接口来说意义不大——浏览器只会把原始帧显示出来。
     * 与其提供一个容易踩坑的入口，不如只留正确的那一个。
     *
     * <h3>⚠ 一个必须说明的能力缺口：无法中途取消</h3>
     * {@link TokenStream} 目前没有暴露「停止生成」的入口。
     * 如果前端断开连接，服务端的模型调用<b>仍会继续跑完</b>（token 照常计费）。
     * 需要「用户点停止就真的停下」的场景，请用 {@code /chat}（Flux 能被 Reactor
     * 感知下游取消）。这是两种返回类型之间真实存在的取舍，不是实现缺陷。
     */
    @PostMapping(value = "/chat-stream-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "结构化流式对话（SSE + 事件类型）",
            description = """
                    与 /chat 同样是 SSE，但推送的是带类型的事件： \
                    `retrieved`（检索到的片段）、`tool`（工具调用）、`token`（逐字文本）、 \
                    `done`（含 token 用量）、`error`、`blocked`（输入被拦）。 \
                    适合做「正在检索知识库…」这类过程可视化。 \
                    ⚠ 该接口目前不支持中途取消（TokenStream 未暴露取消入口）。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "结构化事件流",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            examples = @ExampleObject(value =
                                    "event:token\ndata:{\"type\":\"token\",\"text\":\"Java 的\"}\n\n"
                                            + "event:done\ndata:{\"type\":\"done\",\"finishReason\":\"STOP\"}\n\n"))),
            @ApiResponse(responseCode = "400", description = "请求体校验失败（message 为空）")
    })
    public Flux<ServerSentEvent<String>> chatStreamEvents(
            @Valid @RequestBody ChatRequest request) {
        int memoryId = request.memoryId() == null ? 0 : request.memoryId();
        String message = request.message();

        log.info("收到结构化流式对话请求，memoryId={}，消息长度={}", memoryId, message.length());

        // 输入校验前置，理由与 /chat 完全相同（响应头一旦发出即为 200，无法再表达参数错误）
        try {
            inputGuardService.validate(message);
        } catch (InputTooLongException | InputRejectedException e) {
            log.warn("结构化流式对话输入校验未通过，未调用模型。memoryId={}，原因={}", memoryId, e.getMessage());
            return Flux.just(sseEvent("blocked", payload("blocked", "message", e.getMessage())));
        }

        /*
         * Flux.create 把「回调式 API」桥接成「响应式流」。
         *
         * 为什么不能像 /chat 那样直接返回 Flux？因为 TokenStream 是回调模型：
         * 它不产生流，而是要求你先注册若干个 Consumer，再调 start() 触发执行。
         * Flux.create 正好提供这个「我来喂数据」的出口：回调里 sink.next(...)，
         * 结束时 sink.complete()，于是下游（Spring MVC 的 SSE 写入器）
         * 就能像消费普通 Flux 一样消费它。
         */
        return Flux.create(sink -> {
            TokenStream tokenStream = aiCodeHelperService.chatStreamEvents(memoryId, message);

            tokenStream
                    // ① 逐字输出：与 /chat 的体验一致
                    .onPartialResponse(text ->
                            sink.next(sseEvent("token", payload("token", "text", text))))

                    // ② 检索结果：让前端能展示「依据了哪些资料」
                    .onRetrieved(contents ->
                            sink.next(sseEvent("retrieved", retrievedPayload(contents))))

                    // ③ 工具调用：让前端能展示「正在查询面试题…」
                    .onToolExecuted(execution ->
                            sink.next(sseEvent("tool", toolPayload(execution))))

                    // ④ 完整响应：token 用量、结束原因在这里才有
                    .onCompleteResponse(response -> {
                        sink.next(sseEvent("done", completionPayload(response)));
                        sink.complete();
                    })

                    // ⑤ 异常：同样走事件流，保持「响应体永远是 SSE 帧」这一契约
                    .onError(error -> {
                        log.error("结构化流式对话发生异常，memoryId={}", memoryId, error);
                        sink.next(sseEvent("error",
                                payload("error", "message", "本次回答生成中断，请稍后重试。")));
                        sink.complete();
                    })

                    /*
                     * ⭐ start() 是必须的，而且必须在注册完所有回调之后调用。
                     *
                     * 漏掉它的表现是：接口瞬间返回一个空的 SSE 流，
                     * 不报错、不超时、什么都没有——极难排查，因为它「看起来成功了」。
                     * 这是使用 TokenStream 最常见的坑，所以单独写一行并配注释，
                     * 而不是把它链在中间。
                     */
                    .start();
        });
    }

    // ==========================================================================
    //  以下是把 LangChain4j 的元信息对象转成「前端好消费的 Map」的辅助方法。
    //  集中放在一起，避免上面两个接口的业务逻辑被格式化代码淹没。
    // ==========================================================================

    /** token 用量 → Map */
    private Map<String, Object> describeTokenUsage(TokenUsage usage) {
        if (usage == null) {
            // 不是所有模型/响应都会带用量，返回 null 让调用方知道「这项没有数据」，
            // 而不是伪造一组 0 —— 假的 0 会被当成「这次调用免费」而误导成本统计
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("input", usage.inputTokenCount());
        map.put("output", usage.outputTokenCount());
        map.put("total", usage.totalTokenCount());
        return map;
    }

    /**
     * RAG 检索来源 → List&lt;Map&gt;。
     *
     * <p>这里刻意只返回「文件名 + 片段预览 + 相似度」，而不是整段原文：
     * 原文可能有几千字，全塞进响应体既浪费带宽，也会把前端界面冲垮。
     * 需要原文时再单独提供一个按 id 取片段的接口，不要一股脑返回。
     */
    private List<Map<String, Object>> describeSources(List<dev.langchain4j.rag.content.Content> sources) {
        // 注意这里用的是全限定名：dev.langchain4j 的 Content
        // 与本文件顶部 import 的 io.swagger.v3.oas.annotations.media.Content 重名。
        // Java 不支持 import 别名，只能一个 import、另一个写全名
        // ——RagConfig 里 jakarta.annotation.Resource 与 Spring 的 Resource 是同一个坑。
        List<Map<String, Object>> list = new ArrayList<>();
        if (sources == null) {
            return list;
        }
        for (dev.langchain4j.rag.content.Content content : sources) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (content.textSegment() != null) {
                // FILE_NAME 是 RagConfig 里建索引时写进元数据的，用于引用溯源
                item.put("fileName", content.textSegment().metadata().getString(Document.FILE_NAME));
                item.put("preview", truncate(content.textSegment().text(), 200));
            }
            // 相似度分数由检索器填在 Content 的元信息里，用于判断「这条到底靠不靠谱」
            item.put("score", content.metadata() == null
                    ? null : content.metadata().get(ContentMetadata.SCORE));
            list.add(item);
        }
        return list;
    }

    /** 工具调用记录 → List&lt;Map&gt; */
    private List<Map<String, Object>> describeToolExecutions(List<ToolExecution> executions) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (executions == null) {
            return list;
        }
        for (ToolExecution execution : executions) {
            list.add(toolPayload(execution));
        }
        return list;
    }

    /** 单次工具调用 → Map（两个接口共用） */
    private Map<String, Object> toolPayload(ToolExecution execution) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "tool");
        if (execution == null) {
            return map;
        }
        map.put("tool", execution.request() == null ? null : execution.request().name());
        map.put("arguments", execution.request() == null
                ? null : truncate(execution.request().arguments(), 200));
        map.put("resultPreview", truncate(execution.result(), 200));
        // duration() 可能为 null（部分框架版本不保证填），判空后取值
        map.put("durationMs", execution.duration() == null ? null : execution.duration().toMillis());
        map.put("failed", execution.hasFailed());
        return map;
    }

    /** 检索结果 → Map（流式事件用） */
    private Map<String, Object> retrievedPayload(List<dev.langchain4j.rag.content.Content> contents) {
        Map<String, Object> map = payload("retrieved",
                "count", contents == null ? 0 : contents.size());
        map.put("sources", describeSources(contents));
        return map;
    }

    /** 完整响应 → Map（流式事件用） */
    private Map<String, Object> completionPayload(ChatResponse response) {
        Map<String, Object> map = payload("done", "model",
                response == null ? null : response.modelName());
        map.put("tokenUsage", response == null ? null : describeTokenUsage(response.tokenUsage()));
        map.put("finishReason",
                response == null || response.finishReason() == null
                        ? null : response.finishReason().name());
        return map;
    }

    /**
     * 构造一个「带 type 字段」的事件负载。
     *
     * <p>用 {@code LinkedHashMap} 而不是 {@code Map.of(...)}，原因很实际：
     * {@code Map.of} <b>不接受 null 值</b>（会抛 NullPointerException），
     * 而工具参数、相似度分数这些字段天然可能为 null。
     * 用 Map.of 写起来短，但会在数据恰好为空时炸掉——这类「平时没事、
     * 边界就崩」的写法在监控/元信息接口里尤其危险，因为它们本来就是用来
     * 排查异常情况的。
     */
    private Map<String, Object> payload(String type, String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put(key, value);
        return map;
    }

    /**
     * 把负载序列化成一条 SSE 事件。
     *
     * <p>同时设置 {@code event:} 名与 {@code data:} 中的 {@code type} 字段，是刻意的冗余：
     * 原生 {@code EventSource} 用 {@code addEventListener("token", ...)} 按事件名监听；
     * 而用 {@code fetch + ReadableStream} 手工解析的客户端只能拿到 data，
     * 需要从 JSON 里读 type。两种消费方式都能用，前端可以按自己的技术栈选择。
     */
    private ServerSentEvent<String> sseEvent(String eventName, Map<String, Object> payloadMap) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            // 序列化失败不能让整条流断掉：降级为一条可读的错误事件
            log.error("流式事件序列化失败，event={}", eventName, e);
            json = "{\"type\":\"error\",\"message\":\"事件序列化失败\"}";
        }
        return ServerSentEvent.<String>builder()
                .event(eventName)
                .data(json)
                .build();
    }

    /**
     * 截断过长文本，避免单个元信息字段把响应撑爆。
     *
     * <p>工具一次可能返回 20 道题、检索片段可能上千字。元信息接口的目的是
     * 「让你看清发生了什么」，而不是「搬运全部数据」——预览够用即可。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= maxLength ? flat : flat.substring(0, maxLength) + "…";
    }
}
