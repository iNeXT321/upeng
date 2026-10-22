package com.yt.agent.platform.llm;

import com.yt.agent.advisor.MyLoggerAdvisor;
import com.yt.agent.platform.metrics.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 默认模型网关实现：从 Spring 容器中发现所有 {@link ChatModel} Bean，按角色路由。
 *
 * <p>关键设计——「不绑定厂商」：本类不 import 任何 provider 的类，
 * 只通过 Bean 名做匹配（zhipuai → zhiPuAiChatModel，ollama → ollamaChatModel）。
 * 新增一个厂商只需要引入对应 starter，配置里写 provider 名即可，代码零改动。</p>
 *
 * <p>为什么按角色缓存 ChatClient：ChatClient 构造会解析 advisors、默认选项，
 * 每步 think 都 new 一个属于无谓开销；而 ChatClient 本身线程安全，可以复用。</p>
 */
@Slf4j
public class RoutingLlmGateway implements LlmGateway {

    private final ApplicationContext applicationContext;
    private final LlmProperties properties;
    private final AgentMetrics metrics;
    private final ExecutorService executor;

    private final Map<ModelRole, ChatModel> modelCache = new ConcurrentHashMap<>();
    private final Map<ModelRole, ChatClient> clientCache = new ConcurrentHashMap<>();
    private final Map<ModelRole, String> descriptionCache = new ConcurrentHashMap<>();

    private volatile Map<String, ChatModel> discoveredBeans;

    public RoutingLlmGateway(ApplicationContext applicationContext, LlmProperties properties,
                             AgentMetrics metrics, ExecutorService executor) {
        this.applicationContext = applicationContext;
        this.properties = properties;
        this.metrics = metrics;
        this.executor = executor;
    }

    @Override
    public ChatClient chat(ModelRole role) {
        return clientCache.computeIfAbsent(role, r -> ChatClient.builder(model(r))
                .defaultAdvisors(new MyLoggerAdvisor())
                .build());
    }

    @Override
    public ChatModel model(ModelRole role) {
        return modelCache.computeIfAbsent(role, r -> {
            ModelRoute route = properties.routeFor(r);
            List<MeteredChatModel> targets = resolveTargets(r, route);
            ChatModel resilient = new ResilientChatModel(targets,
                    properties.getMaxAttempts(), properties.getRetryBackoffMs());
            descriptionCache.put(r, describeTargets(route, targets));
            log.info("[LlmGateway] 角色 {} 路由完成 -> {}", r.getKey(), descriptionCache.get(r));
            return resilient;
        });
    }

    @Override
    public ToolCallingChatOptions toolOptions(ModelRole role) {
        ModelRoute route = properties.routeFor(role);
        return ToolCallingChatOptions.builder()
                .model(route.getModel())
                .temperature(route.getTemperature())
                .maxTokens(route.getMaxTokens())
                // 关闭 Spring AI 内置工具执行：Agent 自己维护 think-act，
                // 才能在每步之间做上下文压缩、重复调用抑制、SSE 推送与中断检查
                .internalToolExecutionEnabled(false)
                .build();
    }

    @Override
    public String describe(ModelRole role) {
        if (descriptionCache.containsKey(role)) {
            return descriptionCache.get(role);
        }
        ModelRoute route = properties.routeFor(role);
        return route.getProvider() + ":" + route.getModel();
    }

    @Override
    public boolean available(ModelRole role) {
        try {
            model(role);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 健康快照：把路由结果暴露给 /ai/health，避免"以为用的是 A 模型，实际降级到了本地小模型"。
     */
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("discoveredModels", beans().keySet());
        Map<String, Object> roles = new LinkedHashMap<>();
        for (ModelRole role : ModelRole.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("route", properties.routeFor(role).getProvider() + ":" + properties.routeFor(role).getModel());
            item.put("available", available(role));
            item.put("resolved", describe(role));
            roles.put(role.getKey(), item);
        }
        out.put("roles", roles);
        return out;
    }

    private List<MeteredChatModel> resolveTargets(ModelRole role, ModelRoute route) {
        List<MeteredChatModel> targets = new ArrayList<>();

        if (route.isUsable()) {
            ChatModel primary = findBean(route.getProvider());
            if (primary != null) {
                targets.add(wrap(primary, route.getProvider(), route.getModel(), role, route.getTimeoutMs()));
            } else {
                log.warn("[LlmGateway] 未找到 provider={} 对应的 ChatModel Bean，尝试降级", route.getProvider());
            }
        }

        for (String fallback : properties.getFallbackProviders()) {
            if (fallback == null || fallback.isBlank()) {
                continue;
            }
            if (normalize(fallback).equals(normalize(route.getProvider()))) {
                continue;
            }
            ChatModel bean = findBean(fallback);
            if (bean != null) {
                // 降级模型不强制指定 model 名：不同厂商的模型标识不通用，交给对应 Bean 的默认值
                targets.add(wrap(bean, fallback, null, role, route.getTimeoutMs()));
            }
        }

        if (targets.isEmpty() && properties.isFallbackToAnyProvider()) {
            Map<String, ChatModel> beans = beans();
            if (!beans.isEmpty()) {
                Map.Entry<String, ChatModel> first = beans.entrySet().iterator().next();
                log.warn("[LlmGateway] 角色 {} 未命中配置路由，退化为容器内任意模型: {}", role.getKey(), first.getKey());
                targets.add(wrap(first.getValue(), first.getKey(), null, role, route.getTimeoutMs()));
            }
        }

        if (targets.isEmpty()) {
            throw new ModelUnavailableException(
                    "没有可用的 ChatModel。请检查：(1) spring.ai.<provider>.api-key 是否配置；"
                    + "(2) agent.llm.routes." + role.getKey() + ".provider 是否与已引入的 starter 匹配；"
                    + "(3) 当前发现的模型 Bean: " + beans().keySet());
        }
        return targets;
    }

    private MeteredChatModel wrap(ChatModel delegate, String provider, String model, ModelRole role, Long timeoutMs) {
        return new MeteredChatModel(delegate, provider, model, role,
                timeoutMs == null ? properties.getCallTimeoutMs() : timeoutMs, metrics, executor);
    }

    private String describeTargets(ModelRoute route, List<MeteredChatModel> targets) {
        StringBuilder sb = new StringBuilder(route.getProvider()).append(':').append(route.getModel());
        if (targets.size() > 1) {
            sb.append(" -> [");
            for (int i = 1; i < targets.size(); i++) {
                MeteredChatModel t = targets.get(i);
                sb.append(t.getProvider());
                if (t.getModelId() != null) {
                    sb.append(':').append(t.getModelId());
                }
                if (i < targets.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /** 懒发现：允许运行期新增模型 Bean（例如动态注册本地模型）后被感知 */
    private Map<String, ChatModel> beans() {
        Map<String, ChatModel> snapshot = discoveredBeans;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = discoveredBeans;
                if (snapshot == null) {
                    Map<String, ChatModel> all = new LinkedHashMap<>(applicationContext.getBeansOfType(ChatModel.class));
                    // 排除本网关产生的装饰器，避免自我递归
                    all.entrySet().removeIf(e -> e.getValue() instanceof ResilientChatModel
                            || e.getValue() instanceof MeteredChatModel);
                    snapshot = Map.copyOf(all);
                    discoveredBeans = snapshot;
                }
            }
        }
        return snapshot;
    }

    /**
     * 按 provider 名匹配 Bean：先精确，再按"去掉 ChatModel 后缀"的归一化名匹配。
     * 覆盖 zhipuai/zhiPuAiChatModel、ollama/ollamaChatModel、openai/openAiChatModel 等命名习惯。
     */
    private ChatModel findBean(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        Map<String, ChatModel> beans = beans();
        String target = normalize(provider);
        for (Map.Entry<String, ChatModel> entry : beans.entrySet()) {
            if (normalize(entry.getKey()).equals(target)) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, ChatModel> entry : beans.entrySet()) {
            String key = normalize(entry.getKey());
            String stripped = key.replace("chatmodel", "").replace("model", "");
            if (stripped.equals(target) || key.contains(target) || target.contains(stripped)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @SuppressWarnings("unused")
    private ChatOptions defaultOptions(ModelRoute route) {
        return ChatOptions.builder()
                .model(route.getModel())
                .temperature(route.getTemperature())
                .maxTokens(route.getMaxTokens())
                .build();
    }
}
