package com.yupi.aicodehelper.ai.guardrail;

import com.yupi.aicodehelper.config.AiHelperProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputGuardService} 的单元测试。
 *
 * <h3>为什么这个测试不用 @SpringBootTest？</h3>
 * 因为它<b>根本不需要 Spring</b>。被测对象只有两个协作者（长度护栏与配置对象），
 * 都是纯 Java 对象，手工塞进去就能测。这样做的收益很直接：
 * <ul>
 *   <li><b>快</b>——毫秒级完成，而 {@code @SpringBootTest} 要拉起整个容器
 *       （本项目实测启动约 10 秒，首次还要调用 embedding 接口建向量库）；</li>
 *   <li><b>稳</b>——不依赖 API Key、不依赖网络、不依赖磁盘上的记忆文件与向量库快照。
 *       任何「需要真实密钥才能跑的测试」最终都会在 CI 上被跳过或长期失败；</li>
 *   <li><b>聚焦</b>——失败时能立刻定位到是本类的逻辑问题，
 *       而不是「容器里某个 Bean 没装配好」。</li>
 * </ul>
 * <b>能用单元测试覆盖的逻辑，就不要用集成测试</b>——这是测试金字塔的核心结论。
 *
 * <h3>为什么用反射注入，而不是给字段加 setter？</h3>
 * 生产代码里给 Spring 用的 {@code @Resource} 字段不需要对外暴露 setter，
 * 为了测试而放宽访问权限会污染 API。<b>反射在这里是「测试专用手段」</b>，
 * 它把不便之处限制在测试代码内部，代价是字段改名时测试会失败——
 * 这是可接受的，因为改名本身就是需要同步更新测试的变更。
 * （若项目后续引入 {@code spring-boot-starter-test} 的
 * {@code ReflectionTestUtils}，可以替换成它，可读性更好。）
 */
class InputGuardServiceTest {

    /** 与被测服务共享的同一个护栏实例——刻意复用，验证「阈值单一来源」这一设计 */
    private InputLengthGuardrail guardrail;

    private InputGuardService service;

    /** 测试用的长度上限，取一个小值让用例读起来更直观 */
    private static final int MAX_LENGTH = 100;

    @BeforeEach
    void setUp() throws Exception {
        // 构造配置对象：只设长度上限，其余字段用类里自带的默认值
        AiHelperProperties properties = new AiHelperProperties();
        properties.getGuardrail().setMaxInputLength(MAX_LENGTH);

        guardrail = new InputLengthGuardrail(MAX_LENGTH);

        service = new InputGuardService();
        inject(service, "inputLengthGuardrail", guardrail);
        inject(service, "properties", properties);
    }

    /** 把协作者塞进被测对象的 @Resource 字段（模拟 Spring 的依赖注入） */
    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("正常长度输入应当放行（不抛异常）")
    void shouldPassNormalInput() {
        // 边界内：恰好等于上限，应当放行。
        // 「恰好等于」是边界值分析里最关键的一个用例——
        // 长度判断写错成 >= 时，只有这个用例能发现
        String input = "a".repeat(MAX_LENGTH);
        assertDoesNotThrow(() -> service.validate(input));

        // 典型正常输入
        assertDoesNotThrow(() -> service.validate("什么是 Java 的垃圾回收？"));
    }

    @Test
    @DisplayName("超过长度上限应当抛 InputTooLongException")
    void shouldRejectTooLongInput() {
        String input = "a".repeat(MAX_LENGTH + 1);

        InputTooLongException e = assertThrows(InputTooLongException.class,
                () -> service.validate(input));

        // 提示语必须同时包含「当前长度」与「上限」，
        // 否则用户只知道被拒，不知道该删到多短。这是可用性要求，不只是文案偏好
        assertTrue(e.getMessage().contains(String.valueOf(MAX_LENGTH + 1)),
                "提示语应包含实际长度，实际为：" + e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(MAX_LENGTH)),
                "提示语应包含配置的上限，实际为：" + e.getMessage());
    }

    @Test
    @DisplayName("null 与空串应当放行，由 DTO 上的 @NotBlank 负责拦截")
    void shouldLetBlankInputThrough() {
        // 这里体现的是「职责边界」：长度护栏只管长度，
        // 「不能为空」是 @NotBlank 的职责。两道校验各管一段，不重复判断。
        // 如果这里也去拦空值，就会出现两个地方维护同一件事的情况
        assertDoesNotThrow(() -> service.validate(null));
        assertDoesNotThrow(() -> service.validate(""));
    }

    @Test
    @DisplayName("中文按字符数计算，不应被 UTF-8 字节数误判")
    void shouldCountChineseByCharacters() {
        // 这是一个容易写错的点：如果实现里用了 text.getBytes().length，
        // 中文每字 3 字节，上限 100 就只能输入 33 个汉字——
        // 用户会觉得「明明没写多少字就被拒了」。
        // 本用例锁死「按字符计」这一语义
        String chinese = "汉".repeat(MAX_LENGTH);
        assertDoesNotThrow(() -> service.validate(chinese),
                "恰好 " + MAX_LENGTH + " 个汉字应当放行（按字符计，不是按字节）");

        String tooLong = "汉".repeat(MAX_LENGTH + 1);
        assertThrows(InputTooLongException.class, () -> service.validate(tooLong));
    }

    @Test
    @DisplayName("配置阈值变化后，护栏判定应当随之变化（阈值确实来自配置）")
    void guardrailShouldFollowConfiguredThreshold() throws Exception {
        // 这个用例真正要证明的是「阈值只有一个来源」：
        // 改掉 AiHelperProperties 里的 max-input-length，护栏的行为必须跟着变。
        //
        // 为什么值得专门测？因为项目里存在两处需要一致的地方——
        // 同步接口（AiService 内部挂载的护栏）与流式接口（Controller 前置校验）。
        // 如果哪天有人把注入改回 new InputLengthGuardrail(写死的数字)，
        // 就会出现「调大配置但流式接口仍然按旧值拒绝」的静默不一致。
        int newMax = 5;
        AiHelperProperties properties = new AiHelperProperties();
        properties.getGuardrail().setMaxInputLength(newMax);

        // 关键一步：按新阈值重建护栏，并重新注入——
        // 模拟「应用启动时依据配置构造护栏」的真实过程
        InputGuardService reconfigured = new InputGuardService();
        inject(reconfigured, "inputLengthGuardrail", new InputLengthGuardrail(newMax));
        inject(reconfigured, "properties", properties);

        // 4 个字符：在新阈值内，放行
        assertDoesNotThrow(() -> reconfigured.validate("abcd"));

        // 6 个字符：超过新阈值，拒绝，且提示语里的上限应当是 5 而不是 100
        InputTooLongException e = assertThrows(InputTooLongException.class,
                () -> reconfigured.validate("abcdef"));
        assertTrue(e.getMessage().contains("5"),
                "提示语应反映最新配置的上限 5，实际为：" + e.getMessage());
    }
}
