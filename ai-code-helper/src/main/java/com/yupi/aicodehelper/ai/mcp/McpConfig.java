package com.yupi.aicodehelper.ai.mcp;

import com.yupi.aicodehelper.config.AiHelperProperties;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * MCP（Model Context Protocol）接入配置。
 *
 * <h3>原作者的代码为什么编译不过？—— 一次完整的「踩坑复盘」</h3>
 * 原 {@code McpConfig} 里有一段被注释掉的代码，作者还留了一行疑问：
 * <pre>{@code
 * // 这里我有一个问题：
 * // 我引入了mcp的注解，但是找不到 McpToolProvider
 * }</pre>
 * 被注释的代码大致是这样：
 * <pre>{@code
 * import io.modelcontextprotocol.client.McpClient;    // 来自 Spring AI
 * import io.modelcontextprotocol.spec.McpTransport;   // 来自 Spring AI
 * ...
 * McpTransport transport = new HttpMcpTransport.Builder()...   // Spring AI 的类
 * McpToolProvider toolProvider = McpToolProvider.builder()     // LangChain4j 的类
 *         .mcpClients(mcpClient)                               // 却传入 Spring AI 的对象
 *         .build();
 * }</pre>
 *
 * <p><b>根因：同时引入了两套互不兼容的 MCP 实现，并混用了它们的类。</b>
 * 原 pom 里既有 {@code langchain4j-mcp}，又有
 * {@code spring-ai-mcp-client-webflux-spring-boot-starter}，于是类路径下存在
 * 两个同名但完全不同的 {@code McpClient}：
 * <table border="1">
 *   <caption>两套 MCP SDK 的区别</caption>
 *   <tr><th>对比项</th><th>Spring AI 版</th><th>LangChain4j 版</th></tr>
 *   <tr><td>包名</td><td>io.modelcontextprotocol.*</td>
 *       <td>dev.langchain4j.mcp.*</td></tr>
 *   <tr><td>客户端接口</td><td>io.modelcontextprotocol.client.McpClient</td>
 *       <td>dev.langchain4j.mcp.client.McpClient</td></tr>
 *   <tr><td>工具提供者</td><td>无此概念</td>
 *       <td>dev.langchain4j.mcp.McpToolProvider</td></tr>
 * </table>
 * {@code McpToolProvider} 只接受<b>自己家</b>的 {@code McpClient} 类型，
 * 而代码传入的是 Spring AI 的客户端，所以：
 * <ol>
 *   <li>编译期报「找不到符号 / 类型不匹配」；</li>
 *   <li>即便靠 import 顺序侥幸编过，运行期也会因类型不兼容抛
 *       {@code ClassCastException} 或 {@code NoSuchMethodError}。</li>
 * </ol>
 * 这类「同名的两套 SDK」问题在 Java 生态里很常见（比如 slf4j 与 log4j 的桥接包），
 * 排查方法是看包名而不是类名。
 *
 * <h3>修复方式</h3>
 * <ol>
 *   <li>从 pom 中<b>移除</b> {@code spring-ai-mcp-client-webflux-spring-boot-starter}
 *       （它还会拖入 WebFlux 一大串依赖，与 Spring MVC 混用徒增复杂度）；</li>
 *   <li>统一使用 LangChain4j 的 MCP 实现；
 *       同时 {@code AiCodeHelperServiceFactory} 里那个残留的
 *       {@code import io.modelcontextprotocol.client.McpClient} 也必须删掉——它正是
 *       升级依赖后编译报错的唯一原因。</li>
 * </ol>
 *
 * <h3>MCP 是什么，为什么值得接</h3>
 * 传统做法是把每个外部能力手写成 {@code @Tool} 方法（就像
 * {@link com.yupi.aicodehelper.ai.tools.InterviewQuestionTool} 那样用 Jsoup 抓页面）。
 * 问题在于：每接一个服务都要写一遍胶水代码，而且这些工具只在 LangChain4j 内部可用。
 * MCP 把这件事标准化了——只要对方提供 MCP Server，任何支持 MCP 的客户端
 * 都能即插即用地拿到它的工具清单，且清单能在运行时动态变化。
 *
 * <p>典型可接入的 MCP Server：联网搜索、浏览器自动化、数据库查询、
 * 文件系统操作、Git 操作等。
 *
 * <h3>为什么默认关闭？</h3>
 * 连接外部 MCP Server 需要可用的网络和有效凭据。
 * 若默认开启，没有外网的开发者会在启动阶段直接失败，
 * 而这种失败和业务代码无关，排查很浪费时间。
 * 因此设计为需要 {@code ai-helper.mcp.enabled=true} 显式开启。
 *
 * <h3>@ConditionalOnProperty 的作用</h3>
 * 这是「条件装配」：只有配置项满足条件，这个配置类才生效；
 * 不满足时它就像不存在一样，里面的 Bean 一个都不会创建。
 * 配合 {@code ObjectProvider}（见 {@code AiCodeHelperServiceFactory}），
 * 就实现了「有则用之，无则跳过」的可选依赖模式。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "ai-helper.mcp", name = "enabled", havingValue = "true")
public class McpConfig {

    @Resource
    private AiHelperProperties properties;

    /**
     * MCP 客户端：负责与远程 MCP Server 建立连接、握手、拉取工具清单。
     *
     * <p>{@code destroyMethod = "close"} 告诉 Spring：容器关闭时调用该 Bean 的
     * {@code close()} 方法释放连接。不加这一句，应用退出时连接不会优雅断开，
     * 可能出现端口未释放、对方服务端看到僵死会话等问题。
     *
     * <h4>关于传输协议的选择</h4>
     * LangChain4j 支持三种 MCP 传输方式，各有适用场景：
     * <pre>
     *   StreamableHttpMcpTransport —— 远程 HTTP 服务（本项目采用，当前主流）
     *   StdioMcpTransport          —— 启动本地子进程通信，如 npx 起的本地 MCP Server
     *   WebSocketMcpTransport      —— 需要双向长连接的场景
     * </pre>
     * 注意：旧的 SSE 传输（原代码里的 {@code HttpMcpTransport} + {@code sseUrl}）
     * 已被 MCP 协议规范弃用，新项目应直接用 Streamable HTTP。
     * <b>这是原代码的另一个过时点</b>——它照抄的写法在当前版本已不推荐。
     */
    @Bean(destroyMethod = "close")
    public McpClient mcpClient() {
        String url = properties.getMcp().getUrl();
        if (!StringUtils.hasText(url)) {
            // 启用了 MCP 却没填地址，属于配置错误，必须明确报错而不是静默跳过——
            // 否则用户会疑惑「我明明开了 MCP，为什么模型还是不会用那些工具」
            throw new IllegalStateException(
                    "已启用 MCP（ai-helper.mcp.enabled=true）但未配置 ai-helper.mcp.url，请填写 MCP Server 地址");
        }

        McpTransport transport = StreamableHttpMcpTransport.builder()
                .url(url)
                // 开启报文日志：调试 MCP 连接问题时，这是唯一能看到真实请求/响应的地方
                .logRequests(properties.getMcp().isLogRequests())
                .logResponses(properties.getMcp().isLogRequests())
                .build();

        McpClient client = new DefaultMcpClient.Builder()
                // key 是本客户端的标识，多 MCP Server 场景下用于区分工具来源
                .key("aiCodeHelperMcpClient")
                // 指定 MCP 客户端上报给服务端的名字与版本，便于服务端做兼容与统计
                .clientName("ai-code-helper")
                .clientVersion("1.0.0")
                .transport(transport)
                // 工具执行超时：MCP 工具可能是慢操作（真实网页抓取、数据库查询等），
                // 不设超时保护会把用户请求一直挂住
                .toolExecutionTimeout(Duration.ofSeconds(60))
                // 初始化握手超时：连接阶段卡住时快速失败，而不是无限等待
                .initializationTimeout(Duration.ofSeconds(15))
                .build();

        log.info("MCP 客户端已创建，服务地址: {}", url);
        return client;
    }

    /**
     * MCP 工具提供者：把 MCP 客户端暴露的工具清单交给 AI Service 使用。
     *
     * <p>{@link McpToolProvider} 实现了 LangChain4j 的
     * {@code dev.langchain4j.service.tool.ToolProvider} 接口，
     * 因此可以直接传给 {@code AiServices.builder().toolProvider(...)}。
     *
     * <p>它与 {@code .tools(Object...)} 的本质区别：
     * <pre>
     *   tools(...)        编译期就知道有哪些工具（本地 Java 对象的 @Tool 方法）
     *   toolProvider(...) 运行时才向外部询问有哪些工具（MCP 的典型场景）
     * </pre>
     * 后者让「模型能力可热插拔」成为可能——新增一个 MCP Server，
     * 模型立刻多出一批工具，而 Java 代码一行都不用改。
     */
    @Bean
    public McpToolProvider mcpToolProvider(McpClient mcpClient) {
        McpToolProvider provider = McpToolProvider.builder()
                .mcpClients(mcpClient)
                // 关键参数：某个 MCP Server 连接失败时，是否让整个请求失败。
                // 设为 false 表示「降级」——挂了就少几个工具，对话仍能继续。
                // 生产环境通常选 false，可用性优先于功能完整性；
                // 若某个工具是业务必需（比如支付校验），则应设为 true 快速暴露故障
                .failIfOneServerFails(false)
                .build();

        log.info("MCP 工具提供者已就绪，模型将通过 MCP 协议获得远程工具");
        return provider;
    }
}
