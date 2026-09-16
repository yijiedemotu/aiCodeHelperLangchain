package com.yupi.aicodehelper.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域（CORS）配置。
 *
 * <h3>为什么需要它？—— 浏览器的同源策略</h3>
 * 前端页面跑在 {@code http://localhost:5173}，后端在 {@code http://localhost:8081}，
 * 端口不同即视为「不同源」。浏览器会拦截这种跨源请求，报错：
 * <pre>
 *   Access to fetch at 'http://localhost:8081/api/ai/chat' from origin
 *   'http://localhost:5173' has been blocked by CORS policy
 * </pre>
 * 注意这是<b>浏览器行为</b>，不是服务端拒绝——请求可能根本就没发出去。
 * 解决办法是让服务端在响应里带上允许跨域的响应头，即本类的作用。
 *
 * <h3>本项目其实不太需要它（但保留是对的）</h3>
 * 因为 {@code aiHelper/vite.config.js} 配置了开发代理：
 * <pre>{@code
 *   server: { proxy: { '/api': { target: 'http://localhost:8081' } } }
 * }</pre>
 * 前端请求 {@code /api/ai/chat} 时，是 Vite 开发服务器代为转发给后端的。
 * 浏览器眼里这是「同源请求」（都在 5173），压根不触发 CORS 检查——
 * <b>代理是绕过跨域问题的常用手段</b>，生产环境也常由 Nginx 承担这个角色。
 * <p>那为什么还要保留 CORS 配置？因为一旦前端直接访问后端地址（不走代理），
 * 或者将来前后端分开部署且没有网关，就需要它。属于「留着不亏」的配置。
 *
 * <h3>⚠️ 当前配置的安全性提醒</h3>
 * 现在放行了所有来源（{@code allowedOriginPatterns("*")}）且允许携带 Cookie
 * （{@code allowCredentials(true)}）。这个组合在开发环境很方便，但<b>生产环境有风险</b>：
 * 任何网站都能在用户浏览器里携带其凭据调用你的接口。
 * 上线前应改为白名单：
 * <pre>{@code
 *   .allowedOriginPatterns("https://你的正式域名")
 * }</pre>
 *
 * <h3>一个容易被问到的细节：为什么用 allowedOriginPatterns 而不是 allowedOrigins？</h3>
 * 因为 CORS 规范规定：当 {@code Access-Control-Allow-Credentials: true} 时，
 * {@code Access-Control-Allow-Origin} <b>不能是通配符 {@code *}</b>。
 * 若用 {@code allowedOrigins("*")} 配合 {@code allowCredentials(true)}，
 * Spring 会直接抛异常。{@code allowedOriginPatterns} 是 Spring 提供的替代方案，
 * 它支持通配符写法（如 {@code https://*.example.com}）同时又能与
 * allowCredentials 共存——原代码的注释正确指出了这一点。
 */
@Slf4j
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 注册跨域规则。
     *
     * <p>{@code WebMvcConfigurer} 是 Spring MVC 提供的「扩展点」接口：
     * 实现它并按需重写方法，就能在不修改框架源码的前提下定制 MVC 行为
     * （跨域、拦截器、消息转换器、静态资源映射等都属于这类扩展点）。
     * 接口里的方法都是 {@code default} 方法，所以可以只重写自己关心的部分。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 对哪些路径生效。/** 表示所有接口
        registry.addMapping("/**")
                // 是否允许携带凭据（Cookie、Authorization 头等）。
                // 这是「是否需要登录态」的开关，开启后来源必须精确匹配，不能用 *
                .allowCredentials(true)
                // 允许的来源。开发阶段放行全部，生产应改为白名单（见类注释）
                .allowedOriginPatterns("*")
                // 允许的 HTTP 方法。显式列出 OPTIONS 很重要——
                // 浏览器发送非简单请求前会先发一个 OPTIONS「预检请求」，
                // 若 OPTIONS 未被放行，正式请求根本不会被发出
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // 允许的请求头，* 表示全部（Content-Type、Authorization 等）
                .allowedHeaders("*")
                // 允许前端 JS 读取的响应头。默认情况下浏览器只放行少数几个「安全头」，
                // 若前端需要读取自定义头（如流式响应里的追踪 ID），必须在这里声明暴露
                .exposedHeaders("*");

        log.info("全局跨域配置已生效：允许所有来源（仅适用于开发环境，上线前请改为白名单）");
    }
}
