package com.yupi.aicodehelper.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger 文档配置。
 *
 * <h3>这套东西解决什么问题？</h3>
 * 没有它的时候，要把接口讲清楚只能靠文档或口口相传，而且必然很快过时。
 * springdoc 的做法是<b>直接从代码里读</b>：它扫描 {@code @RestController} 上的
 * 映射注解与参数类型，自动生成符合 OpenAPI 3 规范的描述文件。
 * 于是「文档」和「代码」是同一份东西，不存在「代码改了文档没改」的问题。
 *
 * <h3>引入后自动获得两个端点</h3>
 * <pre>
 *   GET /api/v3/api-docs      —— OpenAPI JSON（机器可读，可用于生成前端 SDK）
 *   GET /api/swagger-ui.html  —— 可交互的调试页面（能直接在浏览器里发请求）
 * </pre>
 * 注意前面那层 {@code /api} 来自 {@code server.servlet.context-path}，
 * 不是 SpringDoc 自己的前缀——这是本项目里很容易被忘记的一点：
 * 配置了 context-path 后，<b>所有</b>路径（包括第三方组件注册的）都会带上它。
 *
 * <h3>为什么还要写这个配置类？全自动不就行了吗？</h3>
 * 自动生成只能覆盖「接口长什么样」（路径、参数、返回类型），
 * 而这里的 {@code Info} 补充的是「这个项目是什么」——标题、版本、描述、联系人。
 * 这些信息会显示在 Swagger UI 页面顶部，是使用者对项目的第一印象。
 * 另外它也是加全局鉴权配置（SecurityScheme）等扩展的落脚点。
 *
 * <h3>为什么本项目的接口注释写得那么细？</h3>
 * 因为 {@code @Operation} / {@code @Parameter} 里的 description 会直接渲染到页面上。
 * 对这个教学项目而言，Swagger UI 本身就是一个很好的「接口说明书」，
 * 值得把「为什么这么设计」写进去，而不只是写「参数含义」。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 构建 OpenAPI 元信息。
     *
     * <p>方法名 {@code aiCodeHelperOpenApi} 会成为 Bean 名称。
     * 用这个略显啰嗦的名字是为了避免与将来可能引入的其他 OpenAPI 相关 Bean 冲突。
     *
     * <p>注意：这个 Bean 是<b>可选增强</b>，不是必需品。
     * 删掉本类，{@code /v3/api-docs} 依然能正常工作，只是页面顶部缺少标题与描述。
     * 认识到「SpringDoc 的核心能力不依赖这个 Bean」有助于理解它与自动配置的分工。
     */
    @Bean
    public OpenAPI aiCodeHelperOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        // 页面顶部的大标题
                        .title("AI 编程小助手 接口文档")
                        // 版本号建议与 pom.xml 中的 project.version 保持一致
                        .version("0.0.1-SNAPSHOT")
                        .description("""
                                基于 Spring Boot 3.5 + LangChain4j 1.20 + 通义千问 qwen-max 的 AI 编程问答后端。

                                **四个核心接口**（每个能力都提供 GET 与 POST 两种形态）：
                                - `POST /ai/chat` —— 流式对话（SSE），支持多会话记忆
                                - `POST /ai/chat-sync` —— 护栏问答，输入/输出双向内容安全
                                - `POST /ai/report` —— 结构化输出，模型直接返回 Java 对象
                                - `POST /ai/study-plan` —— 多 Agent 工作流，约 45 秒

                                **两个重要约定**：
                                1. GET 版仅用于调试。中文经 URL 编码后每字占 9 字节，
                                   Tomcat 默认 8KB 请求头约 900 字就会返回 400，
                                   生产环境请一律使用 POST 版。
                                2. 流式接口 `POST /ai/chat` 的错误也走 SSE 数据帧
                                   （形如 `data:[系统提示] ...`），而不是 JSON 错误体——
                                   因为它的响应头在首个数据块发出时就已固定为 200。

                                **本页面可直接调试**：展开任意接口 → Try it out → 填参数 → Execute。
                                """)
                        .contact(new Contact()
                                .name("aiCodeHelper")
                                .url("https://github.com/yijiedemotu/aiCodeHelperLangchain"))
                        .license(new License()
                                .name("仅供学习使用")));
    }
}
