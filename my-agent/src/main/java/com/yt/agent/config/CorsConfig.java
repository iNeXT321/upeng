package com.yt.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * 全局跨域配置（MVC 层）。
 *
 * <p>注意优先级：Spring Security 的 CorsFilter 在 DispatcherServlet 之前执行，
 * {@code com.yt.agent.platform.auth.SecurityConfig#corsConfigurationSource()} 才是实际生效的那一份，
 * 本类只覆盖 Security 未处理到的场景。两者共用 {@code agent.cors.allowed-origin-patterns}，
 * 避免"一处放行、另一处拦截"导致登录接口 403 Invalid CORS request。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 与 Security 层同源的白名单配置，默认放行任意来源 */
    @Value("${agent.cors.allowed-origin-patterns:*}")
    private String allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] patterns = Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addMapping("/**")
                // 允许发送 Cookie
                .allowCredentials(true)
                // 放行哪些域名（必须用 patterns，否则 * 会和 allowCredentials 冲突）
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*");
    }
}
