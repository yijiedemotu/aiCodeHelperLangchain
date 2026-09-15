package com.yupi.aicodehelper.ai.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 输入长度护栏：拦截过长的用户输入。
 *
 * <h3>为什么需要「长度」这一道护栏？</h3>
 * 看似只是浪费点 token，实际是三个真实风险：
 * <ol>
 *   <li><b>成本失控</b>——大模型按输入 token 计费，一次塞进几万字就是白花花的钱。
 *       攻击者可以写脚本反复提交超长输入来刷你的账单（DoS 的变种）。</li>
 *   <li><b>上下文挤占</b>——模型上下文窗口是有限的。超长输入把窗口占满后，
 *       系统提示词、RAG 检索结果、历史对话都可能被挤掉，回答质量断崖式下跌。</li>
 *   <li><b>请求被上游拒绝</b>——模型服务商本身有输入长度限制，超限会返回 400。
 *       与其把错误抛给用户，不如在本地就拦下来并给一句明确提示。</li>
 * </ol>
 *
 * <h3>LangChain4j 护栏的实现约定</h3>
 * 只需实现 {@link InputGuardrail} 接口。接口里 {@code validate(UserMessage)} 是
 * 默认方法，框架内部真正调用的是 {@code validate(InputGuardrailRequest)}，
 * 后者默认会转调 {@code validate(userMessage)}——所以我们重写
 * {@code validate(UserMessage)} 就够了，这是最简写法。
 *
 * <h3>返回值的四种语义（重点，容易混）</h3>
 * <ul>
 *   <li>{@code success()}——通过，原样放行。</li>
 *   <li>{@code successWith(新文本)}——通过，但<b>替换</b>掉用户输入。
 *       适合「自动修正」场景，比如把输入截断到合法长度。</li>
 *   <li>{@code failure(原因)}——不通过，抛 {@code InputGuardrailException}，
 *       请求直接失败。需要调用方处理异常并给用户友好提示。</li>
 *   <li>{@code fatal(原因)}——致命失败，语义比 failure 更严重，
 *       表示「重试也没有意义」，不会再尝试后续护栏。</li>
 * </ul>
 * 本类缺省使用 {@code failure}，即「宁可明确拒绝，也不悄悄截断用户输入」——
 * 因为静默截断会让用户困惑「我明明问了三个问题，怎么只答了一个」。
 *
 * <h3>注意：护栏实例是共享的</h3>
 * 同一个护栏实例会服务所有并发请求，所以<b>不要在字段里存请求相关的可变状态</b>。
 * 本类只有 final 配置字段，天然线程安全。
 */
@Slf4j
public class InputLengthGuardrail implements InputGuardrail {

    /** 允许的最大输入字符数，由配置注入 */
    private final int maxLength;

    public InputLengthGuardrail(int maxLength) {
        this.maxLength = maxLength;
    }

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        // hasSingleText() 判断这条消息是否为「纯文本单段」。
        // 多模态消息（文字 + 图片）会返回 false，此时不做长度校验直接放行，
        // 避免为了取文本而破坏消息结构。这是防御式写法的必要一步
        if (!userMessage.hasSingleText()) {
            return success();
        }

        String text = userMessage.singleText();
        if (text == null) {
            return success();
        }

        int length = text.length();
        if (length > maxLength) {
            log.warn("输入护栏拦截：输入长度 {} 字符，超过上限 {} 字符", length, maxLength);
            // 提示语要说清「为什么被拒」和「怎么办」，否则用户只会觉得系统坏了
            return failure("输入内容过长（当前 " + length + " 字符，上限 " + maxLength
                    + " 字符）。请精简问题后重试，或将长文本拆分成多次提问。");
        }

        return success();
    }
}
