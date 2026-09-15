package com.yupi.aicodehelper.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 「带护栏」的同步问答服务。
 *
 * <h3>为什么要单独定义一个接口，而不是复用 AiCodeHelperService？</h3>
 * 因为护栏的配置是<b>挂在 AI Service 级别</b>的（{@code AiServices.builder().inputGuardrails(...)}），
 * 一旦挂上，该接口下的<b>所有方法</b>都会受影响。而护栏有副作用：
 * <ul>
 *   <li>输入护栏失败会抛 {@code InputGuardrailException}，直接中断请求；</li>
 *   <li>输出护栏要求「拿到完整回复」才能校验，与流式逐字输出天然冲突。</li>
 * </ul>
 * 如果把护栏挂在 {@link AiCodeHelperService} 上，流式接口的行为会变得难以预期。
 * 所以这里做<b>接口隔离</b>：把「需要严格校验的同步问答」独立成一个接口，
 * 行为边界清晰，也便于将来对不同接口做不同的安全等级划分。
 *
 * <h3>这个方法为什么是同步（返回 String）而不是 Flux？</h3>
 * 这是护栏生效的前提。输出护栏需要完整的 {@code AiMessage} 才能做替换/重试判断，
 * 而流式场景下内容是边生成边推送的。所以：
 * <pre>
 *   要流式体验  → 用 /ai/chat（无输出护栏，靠输入护栏把关）
 *   要内容管控  → 用本接口（同步返回，输入输出双向护栏）
 * </pre>
 * 这不是妥协，而是安全与体验之间的正常取舍——任何流式 AI 产品都面临同样的问题。
 */
public interface GuardedAssistant {

    /**
     * 同步问答（受输入/输出护栏保护）。
     *
     * <p>{@code @SystemMessage(fromResource = ...)} 表示从 classpath 根目录读取
     * {@code system-prompt.txt} 作为系统提示词。相比硬编码在 Java 字符串里，
     * 放在独立文件中有三个好处：改提示词不用动代码、便于版本管理与 diff、
     * 非开发人员（如产品/运营）也能参与调优。
     *
     * @param userMessage 用户问题
     * @return 模型回复（已经过输出护栏校验与必要的掩码处理）
     */
    @SystemMessage(fromResource = "system-prompt.txt")
    String chat(@UserMessage String userMessage);
}
