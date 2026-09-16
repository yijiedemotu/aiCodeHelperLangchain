package com.yupi.aicodehelper.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 「面试题搜索」工具：让大模型能够主动去面试鸭（mianshiya.com）检索真实面试题。
 *
 * <h3>为什么需要一个「工具」？</h3>
 * 大模型的知识来自训练数据，有两个硬伤：
 * <ol>
 *   <li><b>知识截止</b>——训练完之后新增的题目它不知道；</li>
 *   <li><b>不知道具体来源</b>——它可能「大概记得」有类似题目，但说不准，
 *       甚至可能编造（幻觉）。</li>
 * </ol>
 * 工具调用（Function Calling / Tool Calling）解决的是这个问题：
 * 告诉模型「你有一个叫 interviewQuestionSearch 的工具，需要时可以调用」，
 * 模型判断需要实时数据时，会输出一个「调用请求」而不是直接回答，
 * 框架负责执行这个 Java 方法、把真实结果回传，模型再基于真实数据作答。
 *
 * <h3>一次完整的工具调用时序</h3>
 * <pre>
 *   ① 用户提问：「有哪些常见的 Redis 面试题？」
 *   ② 框架把「工具清单（含 description）」随提示词一起发给模型
 *   ③ 模型判断需要实时数据 → 返回 ToolExecutionRequest{ 工具名, 参数JSON }
 *   ④ 框架用反射调用本类的 searchInterviewQuestions("redis")
 *   ⑤ 方法返回值作为 ToolExecutionResultMessage 回传给模型
 *   ⑥ 模型基于真实结果组织自然语言回答
 * </pre>
 * 关键理解：<b>模型不执行代码，它只是「申请」调用</b>，真正执行的是 JVM 里的 Java 方法。
 *
 * <h3>@Tool 的 description 是给模型看的，不是给人看的</h3>
 * 这段描述会作为工具说明发送给模型，模型<b>靠它决定「什么时候该用这个工具」</b>。
 * 所以描述要回答三个问题：
 * <ol>
 *   <li><b>做什么</b>——Retrieves relevant interview questions from mianshiya.com</li>
 *   <li><b>什么时候用</b>——when the user asks for interview questions about
 *       specific technologies（不写清楚，模型可能对所有问题都调它，浪费时间和 token）</li>
 *   <li><b>参数怎么填</b>——The input should be a clear search term（引导模型给出
 *       干净的关键词，而不是把整句话当关键词）</li>
 * </ol>
 * 工具描述写得含糊是工具调用失败的头号原因，值得反复打磨。
 */
@Slf4j
public class InterviewQuestionTool {

    /** 单次真正发起 HTTP 请求的超时时间（毫秒） */
    private static final int TIMEOUT_MS = 8000;

    /** 最多返回多少道题，避免把大量文本塞进上下文（既费 token 又淹没有效信息） */
    private static final int MAX_RESULTS = 20;

    /**
     * 从面试鸭网站获取与关键词相关的面试题列表。
     *
     * <h4>@P 注解的作用</h4>
     * 为参数提供「给模型看的名字和说明」。原代码写的是
     * {@code @P(value = "the keyword to search", required = false)}。
     * 这里把 {@code required} 改为 {@code true}，原因是：
     * 这是一次搜索，<b>关键词缺失时该工具没有任何意义</b>。
     * 声明为必填能让框架在调用前就做参数校验，
     * 而不是让方法内部去处理一个语义上不该出现的空值。
     *
     * <h4>关于「爬虫」这件事的诚实说明</h4>
     * 本工具用 Jsoup 解析搜索结果页的 HTML。这种做法的固有风险：
     * <ul>
     *   <li><b>依赖对方 DOM 结构</b>——站点改版会让选择器立刻失效，
     *       而且表现为「返回空列表」而非报错，属于静默故障，很难发现；</li>
     *   <li><b>可能违反对方服务条款</b>，也不够稳定（对方可能限流或封 IP）；</li>
     *   <li>没有处理 JavaScript 渲染，若页面改为前端渲染，Jsoup 抓不到内容。</li>
     * </ul>
     * 生产项目应优先使用官方 API 或成熟的搜索服务，而不是爬页面。
     * 这里保留爬虫写法是为了演示工具调用的完整链路。
     *
     * <h4>为什么失败时要返回错误信息字符串，而不是抛异常？</h4>
     * 这是工具实现的一个重要技巧。工具方法抛异常会导致<b>整轮对话失败</b>，
     * 用户直接收到错误页。而返回一段说明性的文本，模型能读懂「工具没拿到数据」，
     * 进而自然地回复「实时检索暂时不可用，我先根据已有知识回答你」——
     * 用户体验从「系统崩了」变成「功能降级但可用」。
     *
     * @param keyword 搜索关键词（如 "redis"、"java多线程"）
     * @return 面试题列表（每行一道题），若抓取失败则返回原因说明
     */
    @Tool(name = "interviewQuestionSearch", value = """
            Retrieves relevant interview questions from mianshiya.com based on a keyword.
            Use this tool when the user asks for interview questions about specific technologies,
            programming concepts, or job-related topics.
            The input should be a clear, short search term such as "redis" or "java 多线程",
            NOT a full sentence.
            """
    )
    public String searchInterviewQuestions(
            @P(value = "the keyword to search, e.g. 'redis' or 'java 多线程'", required = true) String keyword) {

        // 参数校验前置。虽然声明了 required=true，但模型偶尔仍会传空串或纯空白
        if (keyword == null || keyword.isBlank()) {
            log.warn("面试题搜索工具被调用，但关键词为空");
            return "检索失败：未提供搜索关键词，请先用一个明确的技术名词重新调用本工具。";
        }

        String trimmedKeyword = keyword.trim();

        // 构建搜索 URL。中文必须做 URL 编码，否则空格、中文会破坏 URL 结构
        String encodedKeyword = URLEncoder.encode(trimmedKeyword, StandardCharsets.UTF_8);
        String url = "https://www.mianshiya.com/search/all?searchText=" + encodedKeyword;

        Document doc;
        try {
            doc = Jsoup.connect(url)
                    // 设置 User-Agent：部分站点会拒绝没有 UA 的请求
                    // （但请注意，伪造 UA 绕过访问控制是不合规的，正式项目应走官方接口）
                    .userAgent("Mozilla/5.0")
                    // 同时设置连接超时与读取超时，避免网络异常时线程被长时间挂住。
                    // 工具调用是同步阻塞的，超时设置直接决定用户等待时长上限
                    .timeout(TIMEOUT_MS)
                    .get();
        } catch (IOException e) {
            log.error("面试题搜索工具请求失败，keyword={}，url={}", trimmedKeyword, url, e);
            // 返回可读的说明而不是抛异常，让模型有机会降级回答（见方法注释说明）
            return "实时检索面试鸭失败（网络异常：" + e.getMessage()
                    + "）。请基于你已有的知识回答用户，并说明题目可能不是最新的。";
        }

        // 解析结果。这里做了一个「多选择器兜底」的改进：
        // 原代码只用 .ant-table-cell > a，一旦站点调整 DOM 结构就彻底失效。
        // 依次尝试几组常见选择器，能显著提升抗改版能力
        LinkedHashSet<String> questions = new LinkedHashSet<>();
        questions.addAll(extractBySelector(doc, ".ant-table-cell > a"));
        if (questions.isEmpty()) {
            questions.addAll(extractBySelector(doc, ".ant-table-cell a"));
        }
        if (questions.isEmpty()) {
            questions.addAll(extractBySelector(doc, "a[href*='/bank/']"));
        }

        if (questions.isEmpty()) {
            log.warn("面试题搜索工具未解析到任何题目，keyword={}，页面标题={}。"
                    + "可能是站点改版导致选择器失效，或该关键词确实无结果", trimmedKeyword, doc.title());
            return "未检索到与「" + trimmedKeyword + "」相关的面试题。"
                    + "可能是该关键词对应题目较少，或站点页面结构已调整。";
        }

        // 截断结果数量，避免把过长文本塞进模型上下文
        List<String> limited = new ArrayList<>(questions);
        if (limited.size() > MAX_RESULTS) {
            limited = limited.subList(0, MAX_RESULTS);
        }

        log.info("面试题搜索工具执行成功，keyword={}，命中 {} 道题（返回 {} 道）",
                trimmedKeyword, questions.size(), limited.size());

        return String.join("\n", limited);
    }

    /**
     * 按选择器抽取题目文本的辅助方法。
     *
     * <p>抽取时顺手做了两件事：
     * <ul>
     *   <li>{@code trim()}——去掉 HTML 里常见的多余空白；</li>
     *   <li>过滤过短文本——导航链接、按钮文字也会命中 {@code a} 标签，
     *       用长度阈值把「首页」「登录」这类噪音滤掉。</li>
     * </ul>
     * 这类「结果清洗」是爬虫类工具的必备步骤，否则模型会收到一堆垃圾数据。
     */
    private List<String> extractBySelector(Document doc, String cssQuery) {
        List<String> result = new ArrayList<>();
        Elements elements = doc.select(cssQuery);
        for (Element element : elements) {
            String text = element.text().trim();
            // 题目标题一般不会太短，用 6 个字符作为噪音过滤阈值
            if (text.length() >= 6) {
                result.add(text);
            }
        }
        return result;
    }
}
