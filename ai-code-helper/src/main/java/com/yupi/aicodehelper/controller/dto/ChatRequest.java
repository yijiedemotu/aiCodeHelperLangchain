package com.yupi.aicodehelper.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 流式对话的请求体（POST /api/ai/chat）。
 *
 * <h3>为什么加 POST 版，而不是沿用 GET？</h3>
 * 原实现用 GET 传参：<code>GET /api/ai/chat?memoryId=1&amp;message=...</code>。
 * 实测发现两个硬伤（有启动日志佐证）：
 * <ol>
 *   <li><b>请求头上限</b>：中文经 URL 编码后每字占 9 字节（<code>%E4%BD%A0</code> 这种），
 *       Tomcat 默认 8KB 请求头大约只能容纳 900 个汉字。
 *       用户贴一段稍长的代码或日志，请求直接返回 400
 *       <code>Request header is too large</code>，连应用层都到不了。</li>
 *   <li><b>URL 长度本身就是语义错位</b>：把「一段可长可短的用户输入」塞进 URL，
 *       意味着它的长度上限由「链路中最脆弱的一环」（浏览器、反向代理、网关、Tomcat……）
 *       共同决定，随时可能再次撞墙。</li>
 * </ol>
 * POST + JSON 请求体没有这两个限制，所以长文本场景必须走 POST。
 *
 * <h3>「流式（SSE）不是只能用 GET 吗？」——这是个常见误解</h3>
 * SSE 与请求方法<b>无关</b>。它只要求响应满足两条：
 * <ul>
 *   <li>响应头是 <code>Content-Type: text/event-stream</code>；</li>
 *   <li>响应体按 <code>data: ...\n\n</code> 的格式逐条组织。</li>
 * </ul>
 * 浏览器原生 <code>EventSource</code> 确实只支持 GET，
 * 但前端用 <code>fetch + ReadableStream</code> 手动消费，
 * 同样能逐块拿到流式响应，还能<b>顺带获得「停止生成」能力</b>
 * （靠 <code>AbortController</code> 中断 fetch）——
 * 这正是 EventSource 原生 API 给不了、但 AI 产品几乎必备的交互。
 *
 * <h3>为什么用 record？</h3>
 * Java 16+ 的 record 只声明字段即可用，天然不可变、自带
 * equals/hashCode/toString，非常适合做「纯数据载体」（DTO）。
 * Spring MVC 能直接把请求体 JSON 反序列化成这个 record，无需任何样板代码。
 *
 * <h3>⚠ 校验注解为什么长在 record 组件上？</h3>
 * Bean Validation 的注解（{@code @NotBlank} 等）要生效，必须由 Controller
 * 参数上的 {@code @Valid} 触发。写在 record 的组件上时，注解会自动落到
 * 对应的<b>字段、构造器参数与 getter</b> 三处（record 的语义规定），
 * 因此无论校验器按哪种策略访问都能读到，这是 record 与校验组合的便利之处。
 *
 * <p>关于「长度上限」为什么<b>不</b>放在这里，见类尾说明。
 *
 * @param memoryId 会话标识（与 GET 版语义一致）。框架据此为每个会话维护
 *                 独立的聊天记忆，实现「你聊你的、我聊我的」。
 * @param message  用户问题。
 */
public record ChatRequest(

        /*
         * memoryId 允许为 null（老前端可能不传），Controller 里会用 0 兜底。
         * 但一旦传了值，就应当落在合理范围内：
         *
         * 为什么要有下限 0？
         *   memoryId 会参与记忆文件的命名（chat-data/memory/12345.json）。
         *   负数虽然不会直接造成问题，但语义上无意义，且前端生成的就是非负随机数。
         *
         * 为什么要有上限？
         *   记忆是「每个 memoryId 一份」，且窗口满之前只增不减。
         *   如果客户端每次请求都换一个新的超大随机数，就会在磁盘上无限累积
         *   记忆文件——这是一条很容易被忽略的资源耗尽路径。
         */
        @Min(value = 0, message = "memoryId 不能为负数")
        @Max(value = 999_999_999L, message = "memoryId 超出允许范围")
        Integer memoryId,

        /*
         * message 是核心业务字段，必须非空。
         * 用 @NotBlank 而不是 @NotNull：前者额外排除「只有空格」的输入。
         * 纯空白提问既浪费一次模型调用，也必然得到一个无意义回答。
         *
         * ⚠ 这里刻意【不】加 @Size(max = ...)。
         * 原因是长度上限已经有唯一的权威来源：ai-helper.guardrail.max-input-length，
         * 由 InputLengthGuardrail 在「即将调用模型之前」统一把关。
         * 如果这里再写死一个数字，就会出现两处阈值，改配置时必然漏改一处，
         * 产生「配置说 2000，实际 500 就报错」这类极难排查的不一致。
         * 让长度校验只有一个出口，是刻意为之的设计，不是遗漏。
         */
        @NotBlank(message = "message 不能为空")
        String message
) {
}
