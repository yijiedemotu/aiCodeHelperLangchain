package com.yupi.aicodehelper.ai.agentic;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 工作流中的第三个 Agent：学习建议整合师（终点节点）。
 *
 * <h3>为什么要有这个「整合」步骤？</h3>
 * 这是多 Agent 工作流中很常见的一个收尾模式，值得单独说明。
 * 前两个 Agent 各自产出的是「面向任务的中间结果」：
 * <ul>
 *   <li>{@code route}——一份学习路线清单；</li>
 *   <li>{@code advice}——一份面试题清单。</li>
 * </ul>
 * 两份都是「资料」，不是「给用户看的答案」。直接拼接返回给用户会有三个问题：
 * <ol>
 *   <li>篇幅冗长，且两部分可能重复表述；</li>
 *   <li>缺少统一的优先级判断，用户看完不知道该先干哪个；</li>
 *   <li>格式不统一，前端的 Markdown 渲染出来会很乱。</li>
 * </ol>
 * 所以最后加一个 Agent 负责「收敛」：把多路中间结果压缩成一份有主次、
 * 有行动指向的最终建议。这个模式在业界常被称为
 * <b>Map-Reduce 式 Agent 编排</b>或 <b>汇总节点（Aggregator / Reducer）</b>。
 *
 * <h3>两个参数，两个来源</h3>
 * <pre>
 *   route  ← StudyRoutePlanner    的 outputKey
 *   advice ← InterviewQuestionAdvisor 的 outputKey
 * </pre>
 * 一个方法同时读取两个共享变量，这正是 AgenticScope 的价值所在：
 * 变量在整个工作流内全局可见，任何后置节点都能取用前面任意节点的产物，
 * 而不需要像传统函数调用那样层层透传参数。
 *
 * <h3>为什么给最终的输出也设 outputKey？</h3>
 * 在配置里这个 Agent 的 outputKey 是 {@code summary}，
 * 整个工作流（sequenceBuilder）的 outputKey 也设成 {@code summary}。
 * 这样调用 {@code workflow.invoke(input)} 时，框架就知道该从共享变量里
 * 取哪个值作为整个工作流的返回值——多个 Agent 都会写变量，
 * 必须明确指定「哪一个才是最终答案」。
 */
public interface StudySummaryWriter {

    @SystemMessage("""
            你是学习顾问，擅长把分散的资料整理成清晰的行动建议。
            语言要直接、口语化，不要写「希望对你有帮助」这类客套话。
            """)
    @UserMessage("""
            请把下面两份材料整合成一份 400 字以内的学习建议，要求按「先做什么、再做什么」给出顺序。

            【学习路线】
            {{route}}

            【面试题建议】
            {{advice}}
            """)
    @Agent(
            value = "把学习路线与面试建议整合为最终学习方案",
            description = "输入学习路线与面试题建议，输出一份有先后顺序、可直接执行的精简学习方案"
    )
    String summarize(String route, String advice);
}
