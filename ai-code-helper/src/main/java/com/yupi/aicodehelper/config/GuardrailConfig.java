package com.yupi.aicodehelper.config;

import com.yupi.aicodehelper.ai.guardrail.InputLengthGuardrail;
import com.yupi.aicodehelper.ai.guardrail.SensitiveWordOutputGuardrail;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 护栏实例的装配配置。
 *
 * <h3>为什么要把护栏单独声明成 Bean，而不是在工厂里 new？</h3>
 * 原先 {@link com.yupi.aicodehelper.ai.AiCodeHelperServiceFactory} 是
 * {@code new InputLengthGuardrail(...)} 直接造实例的。这在只有一个使用方时没问题，
 * 但现在有<b>两处</b>需要同一套长度规则：
 * <pre>
 *   ① 同步接口：AiService 内部挂载的 InputGuardrail（框架在调用模型前自动执行）
 *   ② 流式接口：Controller 在进入 AiService 之前，由 InputGuardService 主动调用
 * </pre>
 * 如果两边各自 {@code new} 一个，就会产生两个<b>独立的阈值来源</b>。
 * 今天它们碰巧读同一个配置项还算一致，但任何人只要改动其中一处（比如给流式接口
 * 单独放宽到 5000 字），就会立刻出现「同样一段输入，走 /chat 能过、走 /chat-sync 被拒」
 * 这种用户完全无法理解的行为差异。把它提升为容器里的<b>单例 Bean</b>，
 * 就从结构上杜绝了这种不一致——两处注入的是同一个对象。
 *
 * <h3>为什么只有输入护栏做成 Bean，输出护栏却仍在工厂里创建？</h3>
 * 因为使用方数量不同：
 * <ul>
 *   <li>{@link InputLengthGuardrail} 被「同步服务」和「流式入口」两处共用 → 需要单例；</li>
 *   <li>{@link SensitiveWordOutputGuardrail} 只在同步 AiService 里被挂载 →
 *       单一使用方，就地创建反而更直观（读工厂代码时不必跳转）。</li>
 * </ul>
 * 判断标准是「有几个使用方」，不是「是不是护栏」。
 *
 * <h3>顺带一提：为什么护栏可以直接当单例？</h3>
 * 因为它们是<b>无状态</b>的——两个实现都只有 final 的配置字段，
 * 校验过程中不写任何实例变量。多线程并发调用互不影响。
 * 反过来说：如果哪天要在护栏里记录「每个用户的命中次数」这类可变状态，
 * 就必须改成 prototype 作用域或把状态外置，否则会出现跨用户串数据的问题。
 */
@Slf4j
@Configuration
public class GuardrailConfig {

    @Resource
    private AiHelperProperties properties;

    /**
     * 输入长度护栏（单例）。
     *
     * <p>阈值来自 {@code ai-helper.guardrail.max-input-length}，
     * 这是全应用唯一的长度上限定义处。
     *
     * @return 可被多处共享的输入长度护栏实例
     */
    @Bean
    public InputLengthGuardrail inputLengthGuardrail() {
        int maxInputLength = properties.getGuardrail().getMaxInputLength();
        log.info("输入长度护栏已装配为单例 Bean，上限 {} 字符（同步与流式接口共用此阈值）", maxInputLength);
        return new InputLengthGuardrail(maxInputLength);
    }
}
