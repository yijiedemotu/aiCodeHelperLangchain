package com.yupi.aicodehelper.controller;

import com.yupi.aicodehelper.ai.guardrail.InputRejectedException;
import com.yupi.aicodehelper.ai.guardrail.InputTooLongException;
import dev.langchain4j.guardrail.GuardrailException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器 —— 把各类异常统一翻译成「前端能直接展示」的错误响应。
 *
 * <h3>没有它的时候会发生什么？</h3>
 * 默认情况下 Spring Boot 会把异常交给 {@code /error} 兜底端点，
 * 返回的是框架自带的「白板错误页」结构：
 * <pre>
 * {
 *   "timestamp": "2026-09-16T01:23:45.678+08:00",
 *   "status": 500,
 *   "error": "Internal Server Error",
 *   "path": "/api/ai/report"
 * }
 * </pre>
 * 这个结构有三个问题：
 * <ol>
 *   <li><b>没有可读信息</b>——「参数 message 不能为空」这种关键提示完全丢失，
 *       前端只能显示「Internal Server Error」，用户不知道该怎么办；</li>
 *   <li><b>结构不稳定</b>——它是框架实现细节，不同版本字段可能变化，
 *       前端不该依赖它做解析；</li>
 *   <li><b>错误分级缺失</b>——参数错误（用户的锅）和系统故障（我们的锅）
 *       长得一模一样，运维无法据此区分告警。</li>
 * </ol>
 *
 * <h3>本类的核心设计：统一响应结构</h3>
 * 所有错误一律返回同一个形状，前端只需要写<b>一套</b>解析逻辑：
 * <pre>
 * {
 *   "success": false,
 *   "error": "BAD_REQUEST",              // 机器可读的错误码，前端可按它做分支
 *   "message": "message 不能为空",        // 人能读懂的话，可直接展示给用户
 *   "path": "/api/ai/chat-sync",          // 出错的接口，便于排查
 *   "timestamp": 1757951925123            // 毫秒时间戳，便于和日志对齐
 * }
 * </pre>
 * 注意 {@code success} 字段与正常业务响应（如 {@code /chat-sync} 的返回）保持一致——
 * 前端可以在拿到任何响应后先看 {@code success}，不需要区分「这是错误体还是业务体」。
 *
 * <h3>为什么用 @RestControllerAdvice 而不是在每个 Controller 里 try-catch？</h3>
 * 因为异常处理是<b>横切关注点</b>：几乎每个接口都面临同一批异常
 * （参数校验失败、类型不匹配、请求体格式错误）。
 * 写一遍、全局生效，业务代码里就只剩下「正常路径」，
 * 可读性和可维护性都会好很多。这也是 Spring AOP 思想最典型的应用场景之一。
 *
 * <h3>⚠ 一个重要限制：本处理器管不到流式接口的「中途」失败</h3>
 * 对于 {@code /ai/chat} 这类返回 SSE 的接口，响应头在第一个数据块发出时
 * 就已经提交为 200，此后发生的异常无法再改写状态码——
 * 本处理器返回的 JSON 会被拼在已发出的流后面，前端无法解析。
 * 因此流式接口的内部错误由 {@code AiController.streamChat} 里的
 * {@code onErrorResume} 在流内部消化掉。
 * <b>结论：流式接口的异常必须自己处理，不能依赖全局处理器。</b>
 * 这是流式编程与传统 MVC 最本质的差异之一。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 统一的错误响应体。
     *
     * <p>用 record 而不是 Map：字段固定、类型明确，
     * 且能被 SpringDoc 自动识别并写进 OpenAPI 文档的 schema 里。
     * 若用 Map，文档里只会显示成一个无结构的 object。
     *
     * @param success   恒为 false，与业务响应的 success 字段对齐
     * @param error     机器可读的错误码（如 {@code BAD_REQUEST}）
     * @param message   面向用户的错误说明，可直接展示
     * @param path      发生错误的请求路径
     * @param timestamp 毫秒时间戳，用于和服务器日志对时间
     */
    public record ApiError(boolean success, String error, String message, String path, long timestamp) {

        /**
         * 构造标准错误体的工厂方法。
         *
         * <p>集中在这里 new，而不是让每个 handler 各写一遍——
         * 将来要加字段（比如 traceId）只需改这一处。
         */
        static ApiError of(String error, String message, HttpServletRequest request) {
            return new ApiError(false, error, message,
                    request == null ? null : request.getRequestURI(),
                    System.currentTimeMillis());
        }
    }

    /**
     * 处理请求体校验失败（{@code @Valid @RequestBody} 触发）。
     *
     * <p>这是最常被触发的一个：例如 {@code POST /ai/chat} 时 message 传了空串。
     * 异常内部携带了<b>所有</b>失败字段，本方法会把它们拼成一句完整的话，
     * 而不是只报第一个——用户一次改完所有问题，体验更好。
     *
     * @return 400，附全部字段的校验失败原因
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException e,
                                                         HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                // 格式："字段名: 原因"，例如 "message: message 不能为空"
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("；"));

        // 兜底：极少数情况下（例如类级别的校验）拿不到 fieldErrors
        if (detail.isBlank()) {
            detail = "请求参数校验失败";
        }

        // 记 warn 而不是 error：这是调用方传参有误，属于预期内的拒绝，
        // 不是服务端故障。用 error 级别会污染告警，让真正的故障被淹没
        log.warn("请求体校验失败：{} → {}", request.getRequestURI(), detail);

        return ResponseEntity.badRequest()
                .body(ApiError.of("BAD_REQUEST", detail, request));
    }

    /**
     * 处理方法参数级校验失败（{@code @Validated} + 方法参数上的约束注解触发）。
     *
     * <p>与上面那条的区别：上面管「请求体对象的字段」，这条管
     * 「方法参数本身」（例如 query 参数直接标了 {@code @Min}）。
     * 两条都留着，是为了让校验失败无论走哪条路径都返回同样的结构，
     * 避免出现「有的接口返回框架默认格式」的不一致。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParamValidation(ConstraintViolationException e,
                                                          HttpServletRequest request) {
        String detail = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        if (detail.isBlank()) {
            detail = "请求参数校验失败";
        }
        log.warn("方法参数校验失败：{} → {}", request.getRequestURI(), detail);
        return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST", detail, request));
    }

    /**
     * 处理请求体「根本读不出来」的情况（JSON 语法错误、类型对不上等）。
     *
     * <p>典型场景：前端把 {@code memoryId} 发成了字符串 {@code "abc"}。
     * 此时 Jackson 在反序列化阶段就失败了，压根走不到校验注解那一步。
     * 不处理的话用户会看到 500，而实际上这是他自己传错了参数。
     *
     * <p>注意这里<b>刻意不把原始异常信息返回给前端</b>：
     * Jackson 的报错包含完整的 Java 类名与字段路径，
     * 属于内部实现细节，暴露出去既无助于用户理解，也有信息泄露风险。
     * 只给一句可操作的提示，详细原因写进服务端日志。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e,
                                                         HttpServletRequest request) {
        log.warn("请求体无法解析：{} → {}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST",
                "请求体格式错误：请确认是合法的 JSON，且字段类型正确（memoryId 应为数字）",
                request));
    }

    /**
     * 处理缺少必填的 query 参数（GET 版接口常见）。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException e,
                                                       HttpServletRequest request) {
        log.warn("缺少必填参数：{} → {}", request.getRequestURI(), e.getParameterName());
        return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST",
                "缺少必填参数：" + e.getParameterName(), request));
    }

    /**
     * 处理参数类型不匹配（例如把 memoryId 写成 {@code ?memoryId=abc}）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                       HttpServletRequest request) {
        log.warn("参数类型不匹配：{} → {}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(ApiError.of("BAD_REQUEST",
                "参数类型错误：" + e.getName() + " 的取值不合法", request));
    }

    /**
     * 处理流式接口的输入校验失败（由 {@code InputGuardService} 抛出）。
     *
     * <p>理论上这个异常在 {@code AiController.streamChat} 内部就被捕获并
     * 转成 SSE 帧了，不会走到这里。但保留这个 handler 是必要的兜底：
     * 将来若有人直接调用 {@code InputGuardService}（比如在同步接口里复用），
     * 忘记捕获时也能得到一个结构化的 400，而不是 500。
     * <b>防御性代码的价值就在于「另一条路径将来可能被走通」。</b>
     */
    @ExceptionHandler(InputTooLongException.class)
    public ResponseEntity<ApiError> handleInputTooLong(InputTooLongException e,
                                                       HttpServletRequest request) {
        log.warn("输入校验未通过：{} → {}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(ApiError.of("INPUT_TOO_LONG", e.getMessage(), request));
    }

    /**
     * 处理「输入被安全策略拒绝」（提示词注入等）。
     *
     * <h4>为什么用 400 而不是 200 + blocked=true？</h4>
     * 这里有一个刻意的语义区分：
     * <pre>
     *   输入被安全策略拒绝 → 400 INPUT_REJECTED
     *        「你这次请求的内容不合规」——属于调用方需要修正的问题
     *   模型回答被输出护栏掩码 → 200 + blocked=true
     *        「系统正常工作，只是内容做了合规处理」——业务正常流程
     * </pre>
     * 前者是<b>请求本身的问题</b>（在调用模型之前就被拦下，没有产生任何模型成本），
     * 后者是<b>业务流程内的一次正常结果</b>。用状态码区分这两者，
     * 监控告警才能正确地只关注前者，而不会把正常的合规掩码当成故障。
     *
     * <h4>为什么错误码不写「具体命中了哪条规则」？</h4>
     * 那等于把规则集交给攻击者，方便其逐条试探绕过。
     * 对外只给「疑似提示词注入」这一层信息，详细命中情况记在服务端日志里。
     */
    @ExceptionHandler(InputRejectedException.class)
    public ResponseEntity<ApiError> handleInputRejected(InputRejectedException e,
                                                        HttpServletRequest request) {
        log.warn("输入被安全策略拒绝：{} → {}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest().body(ApiError.of("INPUT_REJECTED", e.getMessage(), request));
    }

    /**
     * 处理护栏拦截（{@link GuardrailException} 及其子类的兜底）。
     *
     * <p>需要说明的是：{@code /chat-sync} 接口<b>自己</b>就捕获了护栏异常，
     * 并返回 {@code {"success":false,"blocked":true}} 这种业务形态的响应——
     * 因为「被护栏拒绝」是预期内的正常业务流程，不该用错误码表达。
     * <p>那为什么还要留这个 handler？因为护栏可能被挂在<b>其他</b>接口上
     * （例如将来给 {@code /report} 也加护栏）。届时若没有这条兜底，
     * 一次正常的「内容被拒」就会变成 500，被监控当成系统故障告警。
     */
    @ExceptionHandler(GuardrailException.class)
    public ResponseEntity<ApiError> handleGuardrail(GuardrailException e,
                                                    HttpServletRequest request) {
        log.warn("请求被护栏拦截：{} → {}", request.getRequestURI(), e.getMessage());
        // 用 422 Unprocessable Entity 而不是 400：
        // 请求本身格式没问题，是「内容」不被接受，语义上更准确
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of("BLOCKED_BY_GUARDRAIL", e.getMessage(), request));
    }

    /**
     * 处理 Content-Type 不支持的情况。
     *
     * <p>实用价值在于排错：调用方常忘记设置 {@code Content-Type: application/json}，
     * 此时 Spring 会抛这个异常。默认响应里只有「415」这个数字，
     * 而这里会直接告诉对方缺什么——能省掉一轮「为什么报 415」的沟通。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e,
                                                               HttpServletRequest request) {
        log.warn("不支持的 Content-Type：{} → {}", request.getRequestURI(), e.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiError.of(
                "UNSUPPORTED_MEDIA_TYPE",
                "不支持的 Content-Type：" + e.getContentType() + "，POST 接口请使用 application/json",
                request));
    }

    /**
     * 处理 404（需要配合 {@code spring.mvc.throw-exception-if-no-handler-found=true} 才会触发）。
     *
     * <p>保留它的意义在于：让「接口路径写错」也返回统一结构，
     * 而不是掉进 HTML 白板页——前端解析 JSON 时就不会因格式不符而报二次错误。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException e,
                                                   HttpServletRequest request) {
        log.warn("接口不存在：{} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "接口不存在：" + e.getRequestURL(), request));
    }

    /**
     * 处理「静态资源找不到」—— 在 Spring Boot 3 里，这实际上承担了 404 的职责。
     *
     * <h4>⚠ 这是一个实测踩到的坑，值得记下来</h4>
     * 直觉上「接口路径不存在」应该触发 {@link NoHandlerFoundException}，
     * 但实测返回的是 <b>500 而不是 404</b>。原因是 Spring Boot 3 的行为已经变了：
     * <ul>
     *   <li>Boot 3 默认注册了一个 {@code /**} 的静态资源处理器。请求进来时
     *       它<b>先</b>被匹配上，于是「没有对应的 @RequestMapping」这件事
     *       根本不会变成 {@code NoHandlerFoundException}；</li>
     *   <li>静态资源处理器找不到对应文件后，抛的是
     *       {@code NoResourceFoundException}（{@code ResponseStatusException} 的子类）；</li>
     *   <li>它落到本类的 {@code @ExceptionHandler(Exception.class)} 兜底分支里，
     *       于是被当成了「服务端故障」，返回 500。</li>
     * </ul>
     * 结果就是：用户把 URL 拼错，服务端却报告「内部错误」，
     * 既误导用户，也会污染监控告警。下面的 handler 专门修正这一点。
     *
     * <h4>为什么不需要 spring.web.resources.add-mappings=false</h4>
     * 网上常见的解法是关掉静态资源映射来「逼出」NoHandlerFoundException，
     * 但在本项目行不通——Swagger UI 的页面与脚本正是靠静态资源映射
     * 从 webjars 里读出来的（详见 {@code application.yml} 中的说明）。
     * 识别出真正的异常类型并单独处理，比关掉整个静态资源机制更安全。
     *
     * @return 404，附统一结构的错误体
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e,
                                                     HttpServletRequest request) {
        log.warn("资源不存在（通常是接口路径写错）：{}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "接口不存在：" + request.getRequestURI(), request));
    }

    /**
     * 最终兜底：处理所有未被上面列出的异常。
     *
     * <h4>为什么必须有这一条？</h4>
     * 没有它，未预期的异常会走 Spring Boot 默认错误处理，
     * 响应体结构与其他错误不一致，前端就得写第二套解析逻辑。
     * 有了它，<b>「任何异常都返回同一个结构」</b>这个契约才成立。
     *
     * <h4>为什么这里要记 error 级别日志？</h4>
     * 走到这一步说明是真正的服务端故障（模型不可用、空指针、网络中断……），
     * 与前面那些「用户传参有误」的 warn 完全不同，
     * 必须能被监控系统捕获并告警。
     *
     * <h4>为什么对外不返回异常详情？</h4>
     * 异常 message 常包含内部类名、SQL 片段、文件路径等敏感信息。
     * 对外只给一句通用提示，真实原因写进日志——
     * 这是「对外安全、对内可查」的标准做法。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未预期的服务端异常：{}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of(
                "INTERNAL_ERROR", "服务暂时不可用，请稍后重试。", request));
    }
}
