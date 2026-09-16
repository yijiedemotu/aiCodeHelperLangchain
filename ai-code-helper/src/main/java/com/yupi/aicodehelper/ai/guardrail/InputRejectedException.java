package com.yupi.aicodehelper.ai.guardrail;

/**
 * 输入被安全策略拒绝（由 {@link InputGuardService} 抛出）。
 *
 * <h3>为什么不让它继承 InputTooLongException？</h3>
 * 两者是<b>并列</b>的拒绝原因，而不是包含关系：
 * <ul>
 *   <li>{@link InputTooLongException}——「输入不合法」（太长，请缩短后重试）；
 *       对调用方而言是<b>可修复的参数问题</b>，映射为 HTTP 400 INPUT_TOO_LONG。</li>
 *   <li>本异常——「内容不被接受」（命中提示词注入规则）；
 *       属于<b>安全策略拒绝</b>，映射为 HTTP 400 INPUT_REJECTED，
 *       且刻意<b>不告诉对方具体命中了哪条规则</b>——
 *       否则等于把规则集交给攻击者，方便其逐条试探绕过。</li>
 * </ul>
 *
 * <h3>为什么要有这个类型，而不是直接复用 InputTooLongException？</h3>
 * 因为调用方需要能区分「我改短一点就行」和「我的内容被判定为攻击」，
 * 前者是输入长度问题，后者是内容合规问题，给用户的提示与后续动作完全不同。
 *
 * <h3>顺带说明：为什么不直接抛 LangChain4j 的 InputGuardrailException？</h3>
 * 因为那个异常的消息带框架包装前缀（含完整 Java 类名）。
 * 校验在本类里完成时我们<b>拿到了裸的原因文案</b>，
 * 用一个自己的异常类型承载它，Controller 就不必再去剥壳。
 * 「在源头产出干净的数据」比「在下游到处清洗」更可靠。
 */
public class InputRejectedException extends IllegalArgumentException {

    /**
     * @param message 面向用户的拒绝原因（不含框架包装前缀，可直接展示）
     */
    public InputRejectedException(String message) {
        super(message);
    }
}
