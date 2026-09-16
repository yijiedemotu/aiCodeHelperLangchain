package com.yupi.aicodehelper.ai.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.GuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 输出屏蔽词护栏：把模型回复中的敏感词替换成掩码后再返回。
 *
 * <h3>为什么输出侧也要加护栏？</h3>
 * 「输入合规」不等于「输出合规」。即使提示词写得再严，模型仍可能：
 * <ul>
 *   <li>被诱导输出不该出现的内容（提示词注入的下游后果）；</li>
 *   <li>检索到的知识库文档里本身含有需要屏蔽的词，被模型原样复述；</li>
 *   <li>在举例、引用时带出敏感表述。</li>
 * </ul>
 * 输入护栏拦不住这些，必须在<b>响应返回给用户之前</b>再过一道。
 *
 * <h3>为什么用「替换」而不是「拒绝」？</h3>
 * 对比两种处理方式：
 * <pre>
 *   拒绝（failure）：用户问「XXX 是什么」，系统回「抱歉我不能回答」
 *                    → 用户不知道哪句话触发的，体验很差，且可能被反复试探
 *   替换（successWith）：正常回答，只是把敏感词变成 "**"
 *                    → 回答依然有用，合规也满足了
 * </pre>
 * 所以本类采用 {@code successWith(改写后的文本)}。注意返回值里的
 * {@code successWith} 不是「顺带提一句」，而是真的把<b>整段回复替换掉</b>，
 * 框架会拿新文本作为最终结果。
 *
 * <h3>OutputGuardrail 独有的几种控制流（比 InputGuardrail 丰富）</h3>
 * 因为输出侧是「模型已经答完了」，除了成功/失败，还能要求模型重做：
 * <ul>
 *   <li>{@code success() / successWith(text)}——通过，可顺带改写；</li>
 *   <li>{@code failure(reason)}——失败，抛 {@code OutputGuardrailException}；</li>
 *   <li>{@code retry(reason)}——<b>丢弃本次回答，让模型重新生成一次</b>。
 *       适合「偶发格式错误」这类重试大概率能解决的问题；</li>
 *   <li>{@code reprompt(reprompt, reason)}——带着一条新的纠正指令重新提问，
 *       比单纯 retry 更精准，但更贵（多一轮完整交互）；</li>
 *   <li>{@code failureWithMessageRemoval(reason)}——失败，并把这条回复从
 *       记忆里抹掉，避免「错误回答」污染后续多轮对话的上下文。</li>
 * </ul>
 * 本类选用最轻量的 successWith（只做字符串替换，不多花任何模型调用费用）。
 *
 * <h3>⚠ 一个必须知道的限制</h3>
 * 输出护栏作用于「完整的模型回复」。对于<b>流式（streaming）</b>输出，
 * 内容是逐块吐给用户的——等你拿到完整回复时，敏感内容可能已经推送到浏览器了，
 * 此时再替换已经来不及（用户已经看到了）。所以：
 * <b>流式接口要靠输入护栏把关，输出护栏只对非流式接口有实质保护作用。</b>
 * 这是流式 AI 应用的固有安全取舍，不是本实现的缺陷。
 */
@Slf4j
public class SensitiveWordOutputGuardrail implements OutputGuardrail {

    /** 需要屏蔽的词列表，由配置注入（建议配置在 application-local.yml，不要提交到代码仓库） */
    private final List<String> bannedWords;

    /** 命中的词被替换成什么，用 "**" 比空字符串更直观——用户能看出「这里被屏蔽了」 */
    private static final String MASK = "**";

    public SensitiveWordOutputGuardrail(List<String> bannedWords) {
        this.bannedWords = bannedWords == null ? List.of() : bannedWords;
    }

    @Override
    public OutputGuardrailResult validate(AiMessage aiMessage) {
        // 空列表直接放行，避免每次回复都做无意义的字符串扫描
        if (bannedWords.isEmpty()) {
            return success();
        }

        String text = aiMessage.text();
        if (text == null || text.isEmpty()) {
            return success();
        }

        String sanitized = text;
        boolean hit = false;
        for (String word : bannedWords) {
            if (word == null || word.isBlank()) {
                continue;
            }
            if (sanitized.contains(word)) {
                hit = true;
                // 用 replace 而不是 replaceAll：屏蔽词是普通字符串，
                // 若用 replaceAll 传入含正则元字符的词（如 "c++"、"a.b"）会抛异常
                sanitized = sanitized.replace(word, MASK);
            }
        }

        if (!hit) {
            return success();
        }

        log.warn("输出护栏命中屏蔽词，已对回复做掩码替换。原始长度={}，替换后长度={}",
                text.length(), sanitized.length());
        // 用改写后的文本作为最终回复
        return successWith(sanitized);
    }

    /**
     * 辅助方法：演示如何读取护栏的失败详情。
     *
     * <p>{@code OutputGuardrailResult} 内部可以携带多个 Failure
     * （一个请求可能同时触发多条规则）。业务上如果要把违规详情记录下来
     * 做风控统计，就遍历这个列表。这里仅作演示，未在 validate 中调用。
     *
     * <h4>一个泛型上的坑（本方法踩过）</h4>
     * {@code failures()} 的签名是 {@code <F extends GuardrailResult.Failure> List<F> failures()}——
     * 返回类型是<b>由调用方推断的类型参数</b>。如果这里写成
     * {@code for (OutputGuardrailResult.Failure f : ...)}，编译器会尝试把
     * {@code GuardrailResult.Failure} 强转成子类型 {@code OutputGuardrailResult.Failure}，
     * 从而报「不兼容的类型」。
     * 正确做法是<b>用基类型接收</b>，让类型推断自然落到 {@code GuardrailResult.Failure}。
     */
    public void logFailures(OutputGuardrailResult result) {
        if (result == null || result.failures() == null || result.failures().isEmpty()) {
            return;
        }
        for (GuardrailResult.Failure failure : result.failures()) {
            log.warn("护栏失败明细: message={}, cause={}",
                    failure.message(), failure.cause() == null ? "无" : failure.cause().getMessage());
        }
    }
}
