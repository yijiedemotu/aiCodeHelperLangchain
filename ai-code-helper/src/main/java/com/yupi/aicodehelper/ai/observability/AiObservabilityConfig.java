package com.yupi.aicodehelper.ai.observability;

import com.yupi.aicodehelper.config.AiHelperProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.OutputGuardrailExecutedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceErrorListener;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.observability.api.listener.OutputGuardrailExecutedListener;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;

/**
 * AI 调用可观测性装配 —— 本项目接入的 <b>LangChain4j 1.20.0 官方事件总线</b>。
 *
 * <h2>一、它是什么？</h2>
 * LangChain4j 1.20.0 在 {@code dev.langchain4j.observability.api} 包里引入了一套
 * <b>AiService 生命周期事件</b>。AI Service 在一次调用过程中，会在固定节点把
 * 「发生了什么」以事件对象的形式广播出来，我们只需订阅自己关心的事件：
 * <pre>
 *   调用开始        →  AiServiceStartedEvent          （拿到 systemMessage / userMessage）
 *   请求已发出      →  AiServiceRequestIssuedEvent    （拿到最终发给模型的 ChatRequest）
 *   收到模型响应    →  AiServiceResponseReceivedEvent （拿到 ChatResponse：token 用量 / 结束原因）
 *   工具执行完毕    →  ToolExecutedEvent              （拿到工具名、参数、结果）
 *   工具执行出错补偿 →  ToolCompensatedEvent
 *   护栏执行完毕    →  InputGuardrailExecutedEvent / OutputGuardrailExecutedEvent
 *   调用成功结束    →  AiServiceCompletedEvent        （拿到方法返回值）
 *   调用异常结束    →  AiServiceErrorEvent            （拿到 Throwable）
 * </pre>
 *
 * <h2>二、为什么用它，而不用 {@code ChatModelListener}？</h2>
 * 框架里还有一套更底层的 {@code dev.langchain4j.model.chat.listener.ChatModelListener}
 * （onRequest / onResponse / onError）。两者都能看到请求与响应，但视角不同：
 * <table border="1">
 *   <caption>两套可观测机制的差异</caption>
 *   <tr><th>对比项</th><th>ChatModelListener</th><th>AiServiceListener（本项目采用）</th></tr>
 *   <tr><td>抽象层级</td><td>模型层：一次 HTTP 调用</td><td>服务层：一次业务方法调用</td></tr>
 *   <tr><td>能看到业务身份吗</td>
 *       <td><b>不能</b>。只有 messages，不知道是哪个用户、哪个会话、哪个接口</td>
 *       <td><b>能</b>。{@code InvocationContext} 里有接口名、方法名、memoryId</td></tr>
 *   <tr><td>一次业务调用触发几次</td>
 *       <td>多次（工具调用会产生多轮模型请求）</td>
 *       <td>各事件一次，边界与业务调用对齐</td></tr>
 * </table>
 * 对一个多会话的聊天应用来说，「这是<b>哪个会话</b>花的钱」比「这次 HTTP 发了多少 token」
 * 有用得多——所以本项目以 AiServiceListener 为主。
 *
 * <h2>三、最关键的上下文对象：{@code InvocationContext}</h2>
 * 每个事件都携带 {@link InvocationContext}，它是「这次调用的身份证」：
 * <pre>
 *   invocationId()   本次调用的唯一 ID    —— 串联同一次调用的多个事件（日志里用它做关联）
 *   interfaceName()  AI Service 接口名    —— 如 com.yupi.aicodehelper.ai.AiCodeHelperService
 *   methodName()     方法名              —— 如 chatStream / chatWithMeta
 *   chatMemoryId()   会话 ID             —— ⭐ 排查「记忆串号」与按会话计费的唯一凭据
 *   methodArguments() 方法入参
 *   timestamp()      调用开始时间         —— 用它算耗时，精度高于自己打点
 *   modelProvider()  模型提供方           —— 换模型时能区分数据来源
 * </pre>
 *
 * <h2>四、注册方式</h2>
 * 事件总线是<b>每个 AI Service 各自持有一份</b>（挂在 {@code AiServiceContext} 上），
 * 所以监听器要在构建每个 AI Service 时注册，见
 * {@code AiCodeHelperServiceFactory#baseBuilder} 里的 {@code registerListeners(...)}。
 * 本类只负责<b>生产监听器</b>，不负责注册——职责分开，避免装配逻辑散落在两处。
 *
 * <h2>五、一条重要的工程约定：监听器绝不能抛异常</h2>
 * 事件是在业务调用的<b>关键路径上同步派发</b>的。监听器一旦抛异常：
 * <ul>
 *   <li>轻则被框架捕获后打一条日志（取决于 {@code shouldThrowExceptionOnEventError} 配置）；</li>
 *   <li>重则直接把一次正常的模型调用变成失败——
 *       <b>「监控代码把业务搞挂了」是最典型的自伤</b>。</li>
 * </ul>
 * 因此本类所有监听器的 {@code onEvent} 都用 {@code try/catch(Throwable)} 兜底，
 * 出问题只记 debug 日志。这是刻意的防御，不是多余的样板代码。
 */
@Slf4j
@Configuration
public class AiObservabilityConfig {

    /** 读取「记录什么」的开关 */
    @Resource
    private AiHelperProperties properties;

    /**
     * token 用量监听器 —— 对应「这次问答花了多少钱」。
     *
     * <p>订阅 {@link AiServiceResponseReceivedEvent}，它能拿到完整的
     * {@link ChatResponse}，其中 {@code tokenUsage()} 是<b>框架从模型响应里解析出来的真实用量</b>
     * （不是估算），这是成本核算最可靠的来源。
     */
    @Bean
    public AiServiceResponseReceivedListener tokenUsageListener() {
        return new TokenUsageListener(properties.getObservability().isLogTokenUsage());
    }

    /**
     * 异常监听器 —— 对应「哪一次调用失败了、是谁的会话」。
     *
     * <p>订阅 {@link AiServiceErrorEvent}。它的价值在于补全了异常的<b>业务上下文</b>：
     * 普通的 try/catch 只能拿到堆栈，拿不到「是哪个 memoryId 触发的」。
     */
    @Bean
    public AiServiceErrorListener aiServiceErrorListener() {
        return new ErrorListener();
    }

    /**
     * 工具执行监听器 —— 对应「模型到底有没有真的调用工具」。
     *
     * <p>订阅 {@link ToolExecutedEvent}。
     */
    @Bean
    public ToolExecutedEventListener toolExecutedEventListener() {
        return new ToolExecutionListener(properties.getObservability().isLogToolExecution());
    }

    /**
     * 输出护栏监听器 —— 对应「护栏拦了多少次」。
     *
     * <p>订阅 {@link OutputGuardrailExecutedEvent}。之所以只订阅输出护栏：
     * 本项目的输入校验已经在 HTTP 入口由 {@code InputGuardService} 完成，
     * AiService 上不再挂输入护栏（原因见该服务类注释）。
     */
    @Bean
    public OutputGuardrailExecutedListener outputGuardrailExecutedListener() {
        return new GuardrailListener(properties.getObservability().isLogGuardrail());
    }

    // ==========================================================================
    //  以下是四个监听器实现。它们都是无状态的（只有 final 的配置字段），
    //  会被所有并发请求共享，因此必须线程安全。
    // ==========================================================================

    /**
     * 记录每次模型调用的 token 消耗与端到端耗时。
     *
     * <p>输出样例：
     * <pre>
     *   [LLM用量] 接口=AiCodeHelperService 方法=chatWithMeta 会话=99001
     *             模型=qwen-max 输入=1523 输出=306 合计=1829 结束原因=STOP 耗时=4180ms
     * </pre>
     */
    static class TokenUsageListener implements AiServiceResponseReceivedListener {

        private final boolean enabled;

        TokenUsageListener(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public void onEvent(AiServiceResponseReceivedEvent event) {
            if (!enabled) {
                return;
            }
            try {
                ChatResponse response = event.response();
                InvocationContext context = event.invocationContext();
                if (response == null || context == null) {
                    return;
                }

                /*
                 * tokenUsage() 可能为 null —— 并非所有模型服务商都会在响应里返回用量
                 * （尤其是流式响应的中间帧）。这里必须判空，否则监听器会抛 NPE，
                 * 而监听器抛异常会影响正常业务（见类注释第五节的约定）。
                 */
                TokenUsage usage = response.tokenUsage();
                String usageText = usage == null
                        ? "模型未返回用量"
                        : String.format("输入=%s 输出=%s 合计=%s",
                        usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());

                /*
                 * 耗时用「调用开始时间戳 → 现在」计算，而不是在这里另打一个点。
                 * 因为本事件是「收到模型响应」时触发的，两者之差就是这次调用的真实延迟。
                 * 注意：工具调用会产生多轮模型请求，因此这个事件可能触发多次，
                 * 用 invocationId 才能把同一次业务调用的多条记录串起来。
                 */
                long elapsedMs = Duration.between(context.timestamp(), Instant.now()).toMillis();

                log.info("[LLM用量] 调用={} 接口={} 方法={} 会话={} | 模型={} {} 结束原因={} 耗时={}ms",
                        shortId(context.invocationId()),
                        simpleName(context.interfaceName()),
                        context.methodName(),
                        context.chatMemoryId(),
                        response.modelName(),
                        usageText,
                        response.finishReason(),
                        elapsedMs);

                /*
                 * 一个容易被忽略但很实用的告警：finishReason=LENGTH 表示回答被
                 * 「最大输出长度」截断了，用户看到的是一段没写完的话。
                 * 这类问题不会抛异常，只会在体验上表现为「答到一半就没了」，
                 * 不打日志几乎不可能被发现。
                 */
                if (response.finishReason() != null
                        && "LENGTH".equals(response.finishReason().name())) {
                    log.warn("[LLM用量] 本次回答因达到最大输出长度被截断，调用={} 方法={}。"
                                    + "如属常态，请在模型配置中调大 max-output-tokens",
                            shortId(context.invocationId()), context.methodName());
                }
            } catch (Throwable t) {
                // 见类注释第五节：监听器绝不能让业务失败，这里吞掉并降级为 debug
                log.debug("token 用量监听器执行异常（已忽略，不影响业务）", t);
            }
        }
    }

    /**
     * 记录 AI 调用的异常。
     *
     * <p>与「在业务代码里 try/catch」相比，它的独到价值是能拿到
     * {@code InvocationContext} —— 也就是「是哪个会话、哪个方法失败的」。
     * 排查线上问题时，这一条信息往往比堆栈更先派上用场。
     */
    static class ErrorListener implements AiServiceErrorListener {

        @Override
        public void onEvent(AiServiceErrorEvent event) {
            try {
                InvocationContext context = event.invocationContext();
                log.error("[LLM异常] 调用={} 接口={} 方法={} 会话={} 错误类型={} 错误信息={}",
                        context == null ? "-" : shortId(context.invocationId()),
                        context == null ? "-" : simpleName(context.interfaceName()),
                        context == null ? "-" : context.methodName(),
                        context == null ? "-" : context.chatMemoryId(),
                        event.error() == null ? "-" : event.error().getClass().getSimpleName(),
                        event.error() == null ? "-" : event.error().getMessage(),
                        // 异常对象本身作为最后一个参数传给 slf4j：会打印完整堆栈
                        event.error());
            } catch (Throwable t) {
                log.debug("异常监听器自身执行异常（已忽略，不影响业务）", t);
            }
        }
    }

    /**
     * 记录工具调用明细。
     *
     * <p>「模型回答得不对」的第一嫌疑永远是「工具没被调用，或者调用了但返回空」。
     * 有了这条日志，这个判断从「猜」变成「看一眼」。
     *
     * <p><b>注意参数与结果要截断。</b>工具的入参和返回值可能很长
     * （本项目里 {@code interviewQuestionSearch} 一次能返回 20 道题），
     * 全量打日志会把日志文件迅速冲爆——而日志本身也是要花钱存的。
     */
    static class ToolExecutionListener implements ToolExecutedEventListener {

        /** 参数与结果在日志里的最大展示长度 */
        private static final int MAX_LOG_LENGTH = 300;

        private final boolean enabled;

        ToolExecutionListener(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public void onEvent(ToolExecutedEvent event) {
            if (!enabled) {
                return;
            }
            try {
                ToolExecutionRequest request = event.request();
                String resultText = event.resultText();
                InvocationContext context = event.invocationContext();

                log.info("[工具调用] 调用={} 会话={} 工具={} 参数={} 结果长度={} 结果预览={}",
                        context == null ? "-" : shortId(context.invocationId()),
                        context == null ? "-" : context.chatMemoryId(),
                        request == null ? "-" : request.name(),
                        request == null ? "-" : truncate(request.arguments(), MAX_LOG_LENGTH),
                        resultText == null ? 0 : resultText.length(),
                        truncate(resultText, MAX_LOG_LENGTH));
            } catch (Throwable t) {
                log.debug("工具调用监听器自身执行异常（已忽略，不影响业务）", t);
            }
        }
    }

    /**
     * 记录输出护栏的执行结果。
     *
     * <p>{@link OutputGuardrailExecutedEvent} 与其它事件略有不同：它是<b>泛型事件</b>
     * （{@code GuardrailExecutedEvent<P, R, G>}），携带请求、结果、护栏实现类和耗时。
     * 这让「哪条规则命中得最多」这类统计变得可以直接实现——
     * 合规复盘时这是刚需，而靠翻日志文本找是做不到的。
     */
    static class GuardrailListener implements OutputGuardrailExecutedListener {

        private final boolean enabled;

        GuardrailListener(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public void onEvent(OutputGuardrailExecutedEvent event) {
            if (!enabled) {
                return;
            }
            try {
                InvocationContext context = event.invocationContext();
                boolean passed = event.result() != null && event.result().isSuccess();

                /*
                 * 护栏「通过」是绝大多数情况，用 debug 记录；
                 * 只有「命中」才用 warn —— 让日志里出现的都是值得看的东西，
                 * 这是控制日志信噪比的基本手法。
                 */
                if (passed) {
                    log.debug("[护栏] 输出护栏通过 调用={} 护栏={} 耗时={}ms",
                            context == null ? "-" : shortId(context.invocationId()),
                            event.guardrailName(),
                            event.duration() == null ? -1 : event.duration().toMillis());
                } else {
                    log.warn("[护栏] 输出护栏命中！调用={} 会话={} 护栏={} 耗时={}ms",
                            context == null ? "-" : shortId(context.invocationId()),
                            context == null ? "-" : context.chatMemoryId(),
                            event.guardrailName(),
                            event.duration() == null ? -1 : event.duration().toMillis());
                }
            } catch (Throwable t) {
                log.debug("护栏监听器自身执行异常（已忽略，不影响业务）", t);
            }
        }
    }

    // ==========================================================================
    //  日志格式化辅助方法
    // ==========================================================================

    /**
     * 把 UUID 截短用于日志。
     *
     * <p>完整 UUID 有 36 个字符，四五个字段拼起来会把一行日志撑得很长且难以扫读。
     * 取前 8 位足够在单次排查中区分不同调用（碰撞概率可以忽略）。
     */
    private static String shortId(Object invocationId) {
        if (invocationId == null) {
            return "-";
        }
        String text = String.valueOf(invocationId);
        return text.length() <= 8 ? text : text.substring(0, 8);
    }

    /**
     * 取全限定类名的最后一段。
     *
     * <p>{@code com.yupi.aicodehelper.ai.AiCodeHelperService} → {@code AiCodeHelperService}。
     * 包名对排查没有价值，去掉后日志更干净。
     */
    private static String simpleName(String interfaceName) {
        if (interfaceName == null) {
            return "-";
        }
        int index = interfaceName.lastIndexOf('.');
        return index < 0 ? interfaceName : interfaceName.substring(index + 1);
    }

    /** 截断过长文本，避免日志被单条记录冲爆 */
    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "null";
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= maxLength
                ? singleLine
                : singleLine.substring(0, maxLength) + "…(共 " + singleLine.length() + " 字符)";
    }
}
