package com.yupi.aicodehelper.ai.agentic;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 工作流中的第二个 Agent：面试题推荐师。
 *
 * <h3>它在流水线中的位置</h3>
 * <pre>
 *   StudyRoutePlanner  ──写入变量 route──►  本 Agent  ──写入变量 advice──►  StudySummaryWriter
 * </pre>
 *
 * <h3>关键点：参数名 route 必须与上游的 outputKey 严格一致</h3>
 * 在 {@code AgenticWorkflowConfig} 中，第一个 Agent 被配置为
 * {@code .outputKey("route")}，即把它的返回值存进 AgenticScope 的
 * {@code route} 变量。本接口的方法参数也叫 {@code route}，
 * 框架据此自动完成「读共享变量 → 注入方法参数」的绑定，无需手写任何胶水代码。
 *
 * <p>如果这里把参数改成 {@code plan} 或别的名字，框架在运行时会抛出
 * {@code MissingArgumentException}（来自 {@code dev.langchain4j.agentic.agent} 包）。
 * 这个异常在启动阶段不会暴露，只有真正发起请求时才炸——所以改参数名之后
 * 一定要跑一次完整调用做验证，不能只看编译是否通过。
 *
 * <h3>设计要点：给下游留「结构化输入」</h3>
 * 这里直接把上游的长文本整段塞进提示词。真实项目中更好的做法是让上游
 * 返回结构化对象（record / JSON），下游按字段引用，这样：
 * <ul>
 *   <li>token 消耗更可控（不用整段复述）；</li>
 *   <li>下游 Agent 的提示词更稳定，不受上游措辞长度波动影响；</li>
 *   <li>便于插入程序化校验（比如「阶段数少于 3 就重做」）。</li>
 * </ul>
 * 本项目为保持示例简洁，采用最直观的文本传递方式。
 */
public interface InterviewQuestionAdvisor {

    @SystemMessage("""
            你是面试辅导专家，熟悉各大厂技术面试的考察重点。
            你的建议必须紧扣给定的学习路线，不要泛泛而谈。
            """)
    @UserMessage("""
            下面是一位学习者的分阶段学习路线：

            {{route}}

            请基于这条路线，推荐 5 道最应该优先准备的面试题，并逐条说明「为什么现在就该看它」。
            """)
    @Agent(
            value = "基于学习路线推荐优先准备的面试题",
            description = "输入一份学习路线，输出该路线对应阶段最值得优先准备的 5 道面试题及理由"
    )
    String advise(String route);
}
