package com.yupi.aicodehelper.ai.guardrail;

import com.yupi.aicodehelper.config.AiHelperProperties;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 输入校验闸门 —— 在<b>进入 AiService 之前</b>对用户原始输入做全部校验。
 *
 * <h2>它为什么必须存在？—— 一个通用性很强的教训</h2>
 * 「校验放在哪一层」不是风格问题，而是正确性问题。本项目实测踩到了
 * <b>同一类缺陷的两次</b>，两次都源于把输入校验挂在了 AiService 上：
 *
 * <h3>第一次：长度护栏量到了检索内容</h3>
 * AiService 级别的 InputGuardrail 校验的不是用户原始输入，而是
 * {@code RetrievalAugmentor} 注入检索结果<b>之后</b>的消息。后果是：
 * <pre>
 *   输入 "Java"  （4 字符）→ 报「当前 2162 字符」，被拒
 *   输入 "java"  （4 字符）→ 报「当前 3444 字符」，被拒
 *   输入 "面试题" （3 字符）→ 报「当前 4149 字符」，被拒
 * </pre>
 * 规律是「凡是能检索到知识库的问题都会被误杀」，
 * 恰好把 RAG 最该发挥作用的场景变成了重灾区。
 *
 * <h3>第二次：注入护栏被检索到的文档触发</h3>
 * 修好长度问题后，把注入护栏留在 AiService 上，结果<b>换了个症状、同一个病根</b>：
 * <pre>
 *   输入 "Java"     → 被「提示词注入」规则拦截
 *   输入 "什么是 JVM" → 被「提示词注入」规则拦截
 * </pre>
 * 原因相同——检索进来的技术文档里含有结构化片段
 * （分隔线、{"role": ...} 之类的示例、以 system:/user: 开头的行），
 * 被注入规则当成了攻击话术。<b>用户一个字都没打错，却被告知「你在攻击我」。</b>
 *
 * <h3>结论</h3>
 * <b>一切针对「用户输入」的校验，都必须在检索之前、于 HTTP 入口处完成。</b>
 * 一旦下移到 AiService 内部，量到的就不再是用户输入，
 * 而是「用户输入 + 系统自己拼进去的内容」——把系统的东西算在用户头上，
 * 必然产生大量无法解释的误拒。这也解释了为什么
 * {@code /chat}（流式）从头到尾都没出现这两个问题：它的校验一直在本类里做。
 *
 * <p>本类因此成为<b>唯一的输入校验入口</b>，同步与流式两条路径都走它：
 * <pre>
 *   /chat      → streamChat()   → inputGuardService.validate(rawMessage)
 *   /chat-sync → runChatSync()  → inputGuardService.validate(rawMessage)
 * </pre>
 * 护栏能力本身没有减少，只是位置正确了。
 *
 * <h3>为什么可以安全地共享这些护栏实例？</h3>
 * 它们都只有 final 的配置字段，validate 过程中不写入任何实例状态，
 * 因此被并发请求共享不会相互干扰。
 */
@Slf4j
@Service
public class InputGuardService {

    /**
     * 长度护栏（由 {@code GuardrailConfig} 提供的单例）。
     *
     * <p>注入而不是自己 {@code new}，是为了让阈值在全应用内只有一个出处。
     */
    @Resource
    private InputLengthGuardrail inputLengthGuardrail;

    /**
     * 提示词注入护栏（中英双语）。
     *
     * <p>刻意在这里 {@code new} 而不是做成 Bean：它是本类<b>专用</b>的协作者，
     * 没有第二个使用方，就地创建让「输入校验包含哪些检查」在阅读本类时一目了然。
     * （对比 {@code InputLengthGuardrail} 需要单例，是因为它同时要被
     * 历史代码引用，属于跨类共享的配置来源。）
     */
    private final InputGuardrail promptInjectionGuardrail = new BilingualPromptInjectionGuardrail();

    /** 仅用于日志与提示文案中展示阈值，避免用户不知道上限是多少 */
    @Resource
    private AiHelperProperties properties;

    /**
     * 校验用户输入是否允许放行。
     *
     * <p>调用方约定：<b>返回即代表放行</b>，被拒时抛异常。
     * 这种「异常即拒绝」的风格与 LangChain4j 护栏一致，
     * 好处是调用方不需要写 {@code if (!ok) return ...} 这类分支——
     * 主流程代码保持线性，拒绝路径集中在异常处理器里。
     *
     * @param message 用户输入（允许为 null，null 视为空串放行，由后续校验处理）
     * @throws InputTooLongException   输入超过 {@code ai-helper.guardrail.max-input-length}
     * @throws InputRejectedException  输入命中提示词注入规则
     */
    public void validate(String message) {
        // null / 空串在这里直接放行：非空校验是 DTO 上 @NotBlank 的职责。
        // 两道校验各管一段，避免同一件事在两处重复判断
        if (message == null || message.isEmpty()) {
            return;
        }

        checkLength(message);
        checkPromptInjection(message);
    }

    /**
     * 长度校验：复用 LangChain4j 护栏的判定逻辑。
     *
     * <p>{@code UserMessage.from(...)} 是框架提供的消息构造方式，
     * 护栏正是围绕这个类型设计的，所以必须包一层。
     */
    private void checkLength(String message) {
        InputGuardrailResult result = inputLengthGuardrail.validate(UserMessage.from(message));

        // InputGuardrailResult 自带 isSuccess()，成功时不做任何事
        if (result.isSuccess()) {
            return;
        }

        // 失败时把框架的失败原因取出来。failures() 的泛型是「由调用方推断」的
        // （详见 SensitiveWordOutputGuardrail#logFailures 里记录的泛型坑），
        // 所以这里用基类型 GuardrailResult.Failure 接收
        String reason = result.failures() == null || result.failures().isEmpty()
                ? "输入内容过长，已超过上限 " + properties.getGuardrail().getMaxInputLength() + " 字符"
                : result.failures().get(0).message();

        log.warn("输入长度校验未通过，长度 {} 字符，上限 {} 字符",
                message.length(), properties.getGuardrail().getMaxInputLength());

        throw new InputTooLongException(reason);
    }

    /**
     * 提示词注入校验。
     *
     * <p>失败原因从护栏结果里取出后，再由 Controller 统一清洗掉框架前缀
     * （框架的异常消息会带上完整 Java 类名，不适合直接给用户看）。
     */
    private void checkPromptInjection(String message) {
        InputGuardrailResult result = promptInjectionGuardrail.validate(UserMessage.from(message));
        if (result.isSuccess()) {
            return;
        }

        String reason = result.failures() == null || result.failures().isEmpty()
                ? "输入未通过内容安全校验，请调整提问后重试。"
                : result.failures().get(0).message();

        log.warn("输入注入校验未通过，输入长度={}", message.length());

        throw new InputRejectedException(reason);
    }
}

