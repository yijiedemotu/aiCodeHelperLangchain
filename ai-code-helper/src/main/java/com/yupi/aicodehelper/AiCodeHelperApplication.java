package com.yupi.aicodehelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用启动入口。
 *
 * <h3>@SpringBootApplication 到底是什么？</h3>
 * 它是一个「组合注解」，等价于下面三个注解叠加：
 * <pre>
 *   @SpringBootConfiguration   标明这是配置类（内部就是 @Configuration）
 *   @EnableAutoConfiguration   开启自动配置（Spring Boot 的灵魂）
 *   @ComponentScan             扫描当前包及其子包下的 @Component/@Service/@Configuration
 * </pre>
 *
 * <h4>其中最有价值的是自动配置</h4>
 * 它以「约定优于配置」的方式，根据类路径下存在哪些依赖来自动装配 Bean。本项目中：
 * <ul>
 *   <li>类路径有 {@code spring-boot-starter-web} → 自动配置 Tomcat 与 Spring MVC；</li>
 *   <li>类路径有 {@code langchain4j-community-dashscope-spring-boot-starter}
 *       → 自动读取 yml 中的 {@code langchain4j.community.dashscope.*} 配置，
 *       创建并注册 {@code ChatModel}、{@code StreamingChatModel}、{@code EmbeddingModel}
 *       三个 Bean。</li>
 * </ul>
 * 所以本项目的业务代码里找不到任何「new 一个大模型客户端」的代码——
 * 这正是自动配置带来的好处。
 *
 * <p><b>注意 @ComponentScan 的扫描范围是本类所在的包（{@code com.yupi.aicodehelper}）
 * 及其子包。</b>如果将来新增的类放在这个包之外（比如 {@code com.example.xxx}），
 * Spring 就扫描不到，会出现「明明写了 @Service 却注入不进来」的问题。
 * 这是初学者最常见的困惑之一。
 *
 * <h3>@ConfigurationPropertiesScan 的作用（本项目新增）</h3>
 * 它让 Spring 扫描 {@code @ConfigurationProperties} 标注的类并注册为 Bean，
 * 无需在每个配置类上再写 {@code @Component}。
 * 本项目的 {@link com.yupi.aicodehelper.config.AiHelperProperties} 就靠它生效。
 *
 * <p>两种写法对比：
 * <pre>
 *   方式一：@Component + @ConfigurationProperties
 *           —— 配置类作为普通组件被扫描到，但语义上不够准确（它不是「组件」，是「配置」）
 *   方式二：@ConfigurationProperties + 启动类 @ConfigurationPropertiesScan
 *           —— 职责清晰，且便于把配置类集中放在 config 包下统一管理（本项目采用）
 * </pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiCodeHelperApplication {

    /**
     * 启动入口。
     *
     * <p>{@code SpringApplication.run} 内部完成了相当多的工作，大致顺序是：
     * <ol>
     *   <li>推断应用类型（Servlet / Reactive / None）——本项目因引入
     *       {@code spring-boot-starter-web} 而被判定为 Servlet 应用；</li>
     *   <li>准备 Environment：加载 application.yml 与各 profile 配置文件；</li>
     *   <li>创建 ApplicationContext 容器；</li>
     *   <li>执行自动配置 + 组件扫描，实例化所有 Bean；</li>
     *   <li>启动内嵌 Tomcat 并绑定端口。</li>
     * </ol>
     *
     * <p>启动日志里「Tomcat started on port 8081 (http) with context path '/api'」
     * 这一行出现时，才意味着服务真正可用。之前的
     * 「Root WebApplicationContext: initialization completed」只是容器初始化完成，
     * <b>此时端口尚未绑定，服务还不能接受请求</b>——这是看启动日志时需要注意的细节，
     * 否则容易误判为「已经启动了」。
     *
     * @param args 命令行参数。Spring Boot 会把形如 {@code --server.port=8081}
     *             的参数作为「配置项」纳入 Environment，
     *             且其优先级高于 application.yml 与环境变量。
     *             这正是本项目启动时用 {@code --server.port=8081} 覆盖
     *             外部注入端口号的原理。
     */
    public static void main(String[] args) {
        SpringApplication.run(AiCodeHelperApplication.class, args);
    }

}
