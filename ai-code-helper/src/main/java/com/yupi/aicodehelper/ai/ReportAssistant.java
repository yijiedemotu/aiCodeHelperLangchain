package com.yupi.aicodehelper.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.util.List;

/**
 * 结构化输出服务（无会话记忆）。
 *
 * <h2>为什么要把这个方法从 AiCodeHelperService 里搬出来？</h2>
 * 这是本项目实测中发现的又一个真实缺陷——<b>记忆串号</b>。
 *
 * <h3>问题现场</h3>
 * 原来的 {@code AiCodeHelperService.chatForReport(String)} 没有 {@code @MemoryId} 参数，
 * 但它所在的 AiService 挂载了 {@code chatMemoryProvider}。LangChain4j 的规则是：
 * <b>没有 {@code @MemoryId} 的方法，一律使用名为 "default" 的那一份记忆</b>。
 * 于是落盘目录里出现了这样一个文件：
 * <pre>
 *   chat-data/memory/default.json   （15 KB）
 *   ├── SYSTEM  系统提示词
 *   ├── USER    "你好"                          ← 来自 /ai/chat-sync
 *   ├── USER    "用一句话介绍 Java 的垃圾回收机制"   ← 来自 /ai/chat-sync
 *   └── USER    "我想学 Java 后端开发，请给我 3 条具体建议" ← 来自 /ai/report
 * </pre>
 * 三次毫不相干的请求、来自不同接口，被塞进了<b>同一段对话</b>。
 *
 * <h3>后果有多严重？</h3>
 * <ol>
 *   <li><b>隐私泄露</b>——用户 A 用 /ai/chat-sync 问的内容，会作为「历史对话」
 *       连同用户 B 的下一次请求一起发给大模型。模型完全可能把 A 的内容说出来。
 *       这不是「上下文有点乱」，而是真实的跨用户数据泄露。</li>
 *   <li><b>成本与质量双输</b>——这份共享记忆会被无限撑大（窗口填满后仍要每轮全量读写），
 *       而且不相关的历史会干扰模型判断，回答质量下降。</li>
 *   <li><b>难以排查</b>——单机单人测试时完全正常（因为只有你一个用户），
 *       上线多用户后才暴露，且现象是「模型答非所问」这种主观感受，极难定位。</li>
 * </ol>
 *
 * <h3>正确的修法：把「有状态」与「无状态」的服务切开</h3>
 * 关键认识是：<b>记忆是挂在 AiService 级别上的，无法按方法开关</b>
 * （{@code AiServices.builder().chatMemoryProvider(...)} 一挂就是整个接口生效）。
 * 所以只要一个接口里同时存在「需要记忆」和「不需要记忆」的方法，
 * 就必然会产生共享的 default 记忆桶。唯一干净的解法是<b>接口隔离</b>：
 * <pre>
 *   AiCodeHelperService  → 有状态：流式对话（需要多轮上下文）
 *   GuardedAssistant     → 无状态：带护栏的一次性问答
 *   本接口 ReportAssistant → 无状态：结构化输出
 * </pre>
 * 这样设计还有个好处：<b>接口即契约</b>。看到某个接口没配记忆，
 * 就明确知道它的每次调用都是独立的，不需要去翻配置代码确认。
 *
 * <h3>无记忆的代价是什么？</h3>
 * 几乎没有代价。结构化输出本身是一次性任务（「给我 3 条学习建议」），
 * 用户不会期待这个接口记得上一轮说了什么。真正需要上下文的只有聊天流式接口。
 */
public interface ReportAssistant {

    /**
     * 结构化输出：让模型直接返回一个 Java 对象。
     *
     * <h4>这比返回 String 再自己解析好在哪里？</h4>
     * 传统做法是「让模型输出 JSON → 自己用 Jackson 解析」，但模型经常不听话：
     * 会在 JSON 外面包上 ```json 代码块、会加解释性文字、字段名大小写不一致。
     * 结构化输出由框架统一处理：把返回类型转成 JSON Schema 一并发给模型
     * （现代模型服务商原生支持结构化输出约束），再按 Schema 反序列化，
     * 可靠性远高于自己写正则去抠。
     *
     * <h4>为什么用 record 而不是普通类？</h4>
     * Java 16+ 的 record 只声明字段就能用，天然不可变、自带
     * equals/hashCode/toString/getter，非常适合做「数据载体」（DTO）。
     * 用 Lombok 的 {@code @Data} 也可以，但 record 更简洁且语义更准确——
     * 模型返回的结果本就不该被修改。
     *
     * <h4>字段类型选择的影响</h4>
     * {@code List<String> suggestionList} 告诉框架这里是一个字符串数组，
     * 会体现在生成的 JSON Schema 里，引导模型输出数组而不是一段拼接的文本。
     * <b>返回类型写得越精确，模型输出越规整</b>，这是结构化输出的实用技巧。
     *
     * @param userMessage 用户的学习需求描述
     * @return 包含称呼与建议列表的结构化对象
     */
    @SystemMessage(fromResource = "system-prompt.txt")
    Report chatForReport(@UserMessage String userMessage);

    /**
     * 学习建议的结构化载体。
     *
     * <p>定义在接口内部（嵌套 record）是有意为之：它和这个接口强相关，
     * 只有这里用得到，放在一起便于阅读，也不会污染包结构。
     * 外部通过 {@code ReportAssistant.Report} 引用。
     *
     * @param name           对用户的称呼
     * @param suggestionList 学习建议条目列表
     */
    record Report(String name, List<String> suggestionList) {
    }
}
