package com.yt.agent.platform.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型网关配置。
 *
 * <p>设计目标：把"模型是谁"从代码里彻底抽离。业务代码只写 {@code context.getLlm().chat(ModelRole.PLANNER)}，
 * 具体用哪家、哪个型号、超时多久、失败怎么降级，全部由配置决定。</p>
 */
@Data
@ConfigurationProperties(prefix = "agent.llm")
public class LlmProperties {

    /** 总开关；关闭后网关会退化为"容器里任意一个 ChatModel" */
    private boolean enabled = true;

    /** 未显式配置 provider 时的默认供应商 */
    private String defaultProvider = "zhipuai";

    /** 未显式配置 model 时的默认模型 */
    private String defaultModel = "glm-4.5";

    /** 角色路由表：key 为 ModelRole.key */
    private Map<String, ModelRoute> routes = new LinkedHashMap<>();

    /**
     * 降级顺序（供应商）。主路由失败（限流/超时/鉴权失效）时按顺序尝试。
     * 例如 [zhipuai, ollama]：线上模型挂了先降级到本地小模型，保证"有响应"而不是直接 5xx。
     */
    private List<String> fallbackProviders = new ArrayList<>();

    /** 单个目标的最大尝试次数（含首次） */
    private int maxAttempts = 2;

    /** 重试退避基数（毫秒），指数退避 + 抖动 */
    private long retryBackoffMs = 400L;

    /** 单次模型调用超时（毫秒） */
    private long callTimeoutMs = 120_000L;

    /** 当路由里的 provider 找不到对应 Bean 时，是否允许退化为"任意可用模型" */
    private boolean fallbackToAnyProvider = true;

    public ModelRoute routeFor(ModelRole role) {
        ModelRoute configured = routes.get(role.getKey());
        ModelRoute route = new ModelRoute();
        route.setProvider(configured != null && hasText(configured.getProvider())
                ? configured.getProvider() : defaultProvider);
        route.setModel(configured != null && hasText(configured.getModel())
                ? configured.getModel() : defaultModel);
        Double temperature = configured != null && configured.getTemperature() != null
                ? configured.getTemperature() : role.getDefaultTemperature();
        route.setTemperature(temperature);
        route.setMaxTokens(configured != null ? configured.getMaxTokens() : null);
        route.setTimeoutMs(configured != null && configured.getTimeoutMs() != null
                ? configured.getTimeoutMs() : callTimeoutMs);
        route.setEnabled(configured == null || configured.isUsable());
        return route;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
