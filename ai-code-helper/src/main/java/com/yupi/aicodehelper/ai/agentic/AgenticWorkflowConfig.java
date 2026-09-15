package com.yupi.aicodehelper.ai.agentic;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.workflow.SequentialAgentService;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多 Agent 顺序工作流的装配配置。
 *
 * <h3>这个类是 Agentic 模式的核心，值得逐段理解</h3>
 *
 * <h4>第一步：把「接口」变成「能跑的 Agent」</h4>
 * {@link StudyRoutePlanner} 这些只是<b>空接口</b>，一行实现都没有。
 * {@code AgenticServices.agentBuilder(XxxAgent.class)} 会返回一个构建器，
 * 调用 {@code .build()} 时，框架用<b>动态代理</b>在运行时生成实现类：
 * 拦截接口方法调用 → 把参数写进 AgenticScope → 按 {@code @SystemMessage} /
 * {@code @UserMessage} 模板拼提示词 → 调用大模型 → 解析返回 → 存进 outputKey。
 * <p>这与 {@code AiServices.builder(...)} 生成 AI Service 是同一套思路，
 * 区别在于 Agentic 额外管理了「跨 Agent 的共享上下文 AgenticScope」。
 *
 * <h4>第二步：给每个 Agent 声明 outputKey（写变量的名字）</h4>
 * <pre>
 *   planner.outputKey("route")   → 规划师的产出存入 route
 *   advisor.outputKey("advice")  → 推荐师的产出存入 advice
 *   writer.outputKey("summary")  → 整合师的产出存入 summary
 * </pre>
 * 这三个名字必须和下游接口的<b>方法参数名</b>对得上，否则运行时报
 * {@code MissingArgumentException}。对应关系：
 * <pre>
 *   advisor.advise(String route)                    ← 读 route
 *   writer.summarize(String route, String advice)   ← 读 route 和 advice
 * </pre>
 *
 * <h4>第三步：用 sequenceBuilder 串成流水线</h4>
 * {@code AgenticServices.sequenceBuilder()} 返回一个顺序工作流构建器，
 * {@code subAgents(...)} 按<b>传入顺序</b>依次执行。Agentic 模块一共提供五种编排方式，
 * 了解它们的适用场景比记住 API 更重要：
 * <table border="1">
 *   <caption>Agentic 工作流编排方式对比</caption>
 *   <tr><th>构建器</th><th>执行方式</th><th>典型场景</th></tr>
 *   <tr><td>sequenceBuilder</td><td>依次执行，前一个的输出喂给后一个</td>
 *       <td>有明确先后依赖的流水线，如本项目</td></tr>
 *   <tr><td>parallelBuilder</td><td>同时执行，最后汇总</td>
 *       <td>多个互不依赖的子任务，如同时查资料 + 查天气</td></tr>
 *   <tr><td>loopBuilder</td><td>循环执行直到满足退出条件</td>
 *       <td>需要反复打磨的任务，如「写作 → 评审 → 不通过就改」</td></tr>
 *   <tr><td>conditionalBuilder</td><td>按条件分支选择执行哪个子 Agent</td>
 *       <td>分流场景，如「问技术问题走技术 Agent，问职业走求职 Agent」</td></tr>
 *   <tr><td>supervisorBuilder</td><td>由大模型动态决定下一步调谁</td>
 *       <td>开放域复杂任务，流程无法预先写死</td></tr>
 * </table>
 * <p>本项目选 sequenceBuilder，因为「规划 → 选题 → 整合」的依赖是确定且单向的。
 * 能用确定性编排解决的，就不要交给大模型去决策——后者更贵、更慢、更不可预测。
 *
 * <h4>第四步：为什么工作流本身也要一个 outputKey</h4>
 * 三个 Agent 各自往共享上下文写了变量，那么 {@code invoke()} 该返回哪一个？
 * 在最外层再声明一次 {@code .outputKey("summary")}，就是告诉框架：
 * 「整个工作流的返回值，取 summary 这个变量」。
 * 漏掉这一步，取回的可能不是你期望的结果。
 *
 * <h3>关于性能的一个提醒</h3>
 * 这个工作流一次调用会串行触发 <b>3 次</b>完整的大模型请求，
 * 延迟大致是单次问答的 3 倍，token 消耗也接近 3 倍。
 * 所以它适合「用户明确点击了『生成完整学习方案』按钮」这类场景，
 * 而不是用在每一次普通对话上。这也是为什么本项目把它做成独立的 HTTP 接口，
 * 与日常聊天分开。
 */
@Slf4j
@Configuration
public class AgenticWorkflowConfig {

    /**
     * 直接注入 Spring 容器里由 Starter 自动装配好的 ChatModel（通义千问 qwen-max）。
     * Agentic 的构建器需要显式传入模型，它不会自己去 Spring 里找。
     */
    @Resource
    private ChatModel qwenChatModel;

    /**
     * 学习方案生成工作流：学习路线规划 → 面试题推荐 → 整合成最终建议。
     *
     * @return 一个「无类型 Agent」。这里的 UntypedAgent 并不是说不类型安全，
     *         而是指它的输入不是强类型的 Java 对象，而是一个
     *         {@code Map<String, Object>}（键为共享变量名）。
     *         调用方式：{@code workflow.invoke(Map.of("topic", "Java"))}
     */
    @Bean
    public UntypedAgent studyPlanWorkflow() {
        // ---------- 构建第一个 Agent：学习路线规划师 ----------
        StudyRoutePlanner routePlanner = AgenticServices
                .agentBuilder(StudyRoutePlanner.class)
                .chatModel(qwenChatModel)
                // 把 plan() 的返回值写入共享变量 route，供下游读取
                .outputKey("route")
                // 给 Agent 起个名字，会出现在监控和日志里，方便定位是哪一步出的问题
                .name("routePlanner")
                .build();

        // ---------- 构建第二个 Agent：面试题推荐师 ----------
        InterviewQuestionAdvisor questionAdvisor = AgenticServices
                .agentBuilder(InterviewQuestionAdvisor.class)
                .chatModel(qwenChatModel)
                // advise(String route) 读 route，这里把结果写进 advice
                .outputKey("advice")
                .name("questionAdvisor")
                .build();

        // ---------- 构建第三个 Agent：学习建议整合师 ----------
        StudySummaryWriter summaryWriter = AgenticServices
                .agentBuilder(StudySummaryWriter.class)
                .chatModel(qwenChatModel)
                .outputKey("summary")
                .name("summaryWriter")
                .build();

        // ---------- 串成顺序工作流 ----------
        SequentialAgentService<UntypedAgent> workflowBuilder = AgenticServices.sequenceBuilder();

        UntypedAgent workflow = workflowBuilder
                // 顺序即依赖：planner 必须先跑，否则 advisor 读不到 route
                .subAgents(routePlanner, questionAdvisor, summaryWriter)
                // 声明整个工作流的最终返回值取哪个共享变量
                .outputKey("summary")
                .name("studyPlanWorkflow")
                .description("输入一个学习方向，输出包含学习路线、面试题建议与整合方案的学习计划")
                // 【可选增强】调用前打印一下入口参数，便于排查「变量没传进来」类问题
                .beforeCall(scope -> log.info("学习方案工作流启动，入口变量={}", scope.readState("topic")))
                .build();

        log.info("Agentic 学习方案工作流装配完成，共 3 个 Agent：routePlanner → questionAdvisor → summaryWriter");
        return workflow;
    }
}
