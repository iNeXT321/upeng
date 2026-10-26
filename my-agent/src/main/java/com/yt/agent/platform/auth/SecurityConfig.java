package com.yt.agent.platform.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.List;

/**
 * 安全配置：无状态 JWT 鉴权。
 *
 * <p>放行清单（最小化原则，其余一律 authenticated）：</p>
 * <ul>
 *   <li>/auth/login、/auth/register —— 认证入口本身；</li>
 *   <li>/ai/health —— 容器 liveness probe 与负载均衡健康检查；</li>
 *   <li>OpenAPI 文档（knife4j /doc.html、/v3/api-docs、/swagger-ui）—— 开发调试；</li>
 *   <li>预检请求 OPTIONS —— CORS。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /**
     * 跨域来源白名单（逗号分隔，支持 Spring 的 origin pattern 通配写法）。
     *
     * <p>默认 {@code *}：本平台鉴权走 Authorization 头里的 JWT、不使用 Cookie（见下方
     * {@code allowCredentials=false}），因此放开来源不会造成凭据被第三方站点带发；
     * 而部署环境的访问地址并不固定（localhost / 局域网 IP / Tailscale 100.x 地址 / 反向代理域名），
     * 写死 localhost 会让"换台机器用 IP 访问"直接 403 Invalid CORS request。</p>
     *
     * <p>需要收紧时用环境变量覆盖，例如：
     * {@code AGENT_CORS_ALLOWED_ORIGINS=http://localhost:3000,http://100.106.78.98:8080}</p>
     */
    @Value("${agent.cors.allowed-origin-patterns:*}")
    private String allowedOriginPatterns;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // 无状态 token 方案，无 cookie 会话，CSRF 不适用
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/auth/login", "/auth/register").permitAll()
                        .requestMatchers("/ai/health").permitAll()
                        .requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        // 统一 JSON 401，而不是默认的重定向/空白页
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"code\":\"UNAUTHORIZED\",\"message\":\"登录已过期或未登录，请重新登录\"}");
                        })
                        .accessDeniedHandler((request, response, e) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"code\":\"FORBIDDEN\",\"message\":\"没有访问该资源的权限\"}");
                        }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Spring Security 层的 CORS 配置。
     *
     * <p>注意：Security 的 CorsFilter 在 DispatcherServlet 之前执行，本处的配置优先级高于
     * {@code com.yt.agent.config.CorsConfig}（WebMvcConfigurer）。两边不一致时，这里说了算——
     * 白名单没写全就会在进 Controller 之前被拦下，返回 403 "Invalid CORS request"。
     * 两处均由 {@code agent.cors.allowed-origin-patterns} 驱动，保持同一来源。</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Trace-Id"));
        // 无 Cookie 会话，不放开凭据；这样白名单里的 "*" 语义干净，不会与 allowCredentials 冲突
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
