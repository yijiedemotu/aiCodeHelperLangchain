package com.yupi.aicodehelper.ai.guardrail;

/**
 * 输入过长异常（由 {@link InputGuardService} 抛出）。
 *
 * <h3>为什么不直接复用 LangChain4j 的 GuardrailException？</h3>
 * 因为流式接口 {@code /ai/chat} 走的是<b>完全不同的校验路径</b>，两者不能混用：
 *
 * <pre>
 *   同步接口（/chat-sync）
 *     → 输入交给 AiService 的 InputGuardrail 处理
 *     → 失败抛 InputGuardrailException（LangChain4j 自有类型）
 *     → Controller 捕获后返回 {"success":false,"blocked":true,...}
 *
 *   流式接口（/chat）
 *     → 在进入 AiService <b>之前</b>就由本异常拦下（见下方「为什么必须前置」）
 *     → Controller 捕获后以 SSE 帧形式告知前端
 * </pre>
 *
 * <h3>为什么流式接口的输入校验必须「前置」？</h3>
 * 这是本项目里一个不显眼但很关键的取舍。如果用把输入护栏挂到流式 AiService 上，
 * 会发生两件坏事：
 * <ol>
 *   <li><b>语义不对</b>：护栏是在 AiService 内部、即将调用模型时才运行的，
 *       而流式接口的响应头在第一个数据块发出时就已提交为 200。
 *       此时再抛护栏异常，前端拿到的就不是「参数错误」，
 *       而是一条已经开始的流被中途掐断——用户完全不知道是输入太长。</li>
 *   <li><b>白花一次调用</b>：多轮对话场景下，护栏失败前框架可能已经做完了
 *       记忆装载、RAG 检索（检索本身要调 embedding 接口，按 token 计费）。
 *       在请求入口处直接拦下，这些开销全部为零。</li>
 * </ol>
 * 所以流式路径的输入校验被提到 Controller 调用服务之前，作为一道独立闸门；
 * 而校验所用的<b>阈值与提示文案与护栏完全一致</b>（都读
 * {@code ai-helper.guardrail.max-input-length}，都由 {@link InputLengthGuardrail} 判定），
 * 保证用户无论走哪个接口，得到的长度限制行为是统一的。
 *
 * <p>继承 {@code IllegalArgumentException} 而非 {@code RuntimeException}：
 * 语义上它确实是「调用方给的参数不合法」，继承后者能让意外捕获
 * {@code IllegalArgumentException} 的既有代码也正确处理它。
 */
public class InputTooLongException extends IllegalArgumentException {

    /**
     * @param message 面向用户的完整拒绝原因（含当前长度与上限，可直接展示）
     */
    public InputTooLongException(String message) {
        super(message);
    }
}
