package com.yupi.aicodehelper.ai.agentic;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 工作流中的第一个 Agent：学习路线规划师。
 *
 * <h3>Agentic 模块是什么？和普通 AI Service 有什么区别？</h3>
 * 普通 AI Service（{@code AiServices.builder(...)}）解决的是「一次提问 → 一次回答」。
 * 但真实任务往往是多步骤的，比如「帮我规划学习路线」实际上包含：
 * <pre>
 *   ① 先想清楚学什么、分几个阶段
 *   ② 再针对这条路线找出该优先刷的面试题
 *   ③ 最后把两者整合成一份可执行建议
 * </pre>
 * 单次调用做不到这么好——把三件事塞进一个提示词，模型容易顾此失彼。
 * 更可靠的做法是拆成三个各司其职的「专职 Agent」串成流水线，
 * 前一个的输出作为后一个的输入。这就是 Agentic 模块（1.3.0 起新增）要做的事。
 *
 * <h3>三种声明方式</h3>
 * Agentic 提供了两条路：
 * <ol>
 *   <li><b>编程式</b>（本项目采用）：定义接口，再用
 *       {@code AgenticServices.agentBuilder(XxxAgent.class)} 构建。
 *       优点是配置灵活、便于调试，且能用上 Spring 的依赖注入。</li>
 *   <li><b>声明式</b>：用 {@code @SequenceAgent} / {@code @ParallelAgent}
 *       等注解直接标注在一个「总工作流接口」上，再由
 *       {@code AgenticServices.createAgenticSystem(Class)} 一次性生成整套系统。
 *       代码更少，但灵活性低。</li>
 * </ol>
 *
 * <h3>这个注解为什么写两个属性？</h3>
 * {@code @Agent} 的 {@code value} 是 Agent 的一句话用途说明，
 * {@code description} 是更详细的能力描述。二者在<b>纯 Agentic 模式</b>
 * （Supervisor / Planner 模式）下至关重要——那时由一个大模型扮演「调度员」，
 * 它靠阅读每个 Agent 的描述来决定「这一步该派谁去干」。
 * 描述写得含糊，调度就会出错。本项目用的是顺序工作流（顺序是代码写死的），
 * 描述暂时只用于日志和监控展示，但仍建议写清楚，方便后续切换到 Supervisor 模式。
 *
 * <h3>方法参数是怎么变成「共享变量」的？</h3>
 * 工作流内部有一个共享上下文叫 {@code AgenticScope}，各 Agent 通过
 * <b>读写命名变量</b>来传递数据：
 * <pre>
 *   Agent1 写 route   ──►  Agent2 读 route、写 advice
 *                              ──►  Agent3 读 route + advice
 * </pre>
 * 参数名与变量名的对应关系靠编译参数 {@code -parameters} 保留的方法参数名自动完成
 * （Spring Boot 父 POM 默认开启）。因此：
 * <ul>
 *   <li>参数名 {@code topic} 必须和调用时 Map 里的 key 一致；</li>
 *   <li>参数名 {@code route} 必须和上游 Agent 的 outputKey 一致；</li>
 *   <li>改名要同步改，否则运行时报「缺少必需参数」。
 *       <b>这是引入 Agentic 后最容易犯的错误，且编译器不会提醒你。</b></li>
 * </ul>
 *
 * <p>提示词里的 {@code {{topic}}} 是 LangChain4j 的模板占位符语法，
 * 会从 AgenticScope 中取同名变量填充。
 */
public interface StudyRoutePlanner {

    @SystemMessage("""
            你是资深的编程学习规划师，擅长把宏大的学习目标拆解成可执行的阶段。
            要求：每个阶段必须给出「目标」和「核心知识点」，不要写空泛的鼓励话。
            """)
    @UserMessage("""
            请针对学习方向「{{topic}}」，输出一份分阶段的精简学习路线。
            要求 4 到 6 个阶段，每个阶段包含：阶段目标、核心知识点、一个练手项目建议。
            """)
    @Agent(
            value = "根据学习方向生成分阶段的学习路线",
            description = "输入学习方向，输出包含阶段目标、核心知识点与练手项目的分阶段学习路线"
    )
    String plan(String topic);
}
