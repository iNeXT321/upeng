package com.yt.agent.platform.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Agent 运行时指标采集。
 *
 * <p>为什么不能只靠日志：Agent 是长链路、非确定性系统，日志只能回答"发生了什么"，
 * 无法回答"成功率是多少""P95 延迟多少""token 花在哪个角色上"。没有指标的 Agent 无法做容量规划，
 * 也无法判断一次模型切换是变好还是变坏。</p>
 *
 * <p>实现选择：直接使用 Micrometer 的 {@link MeterRegistry} 抽象，而不是自研计数器——
 * 这样后续接入 Prometheus / OTLP 时只需换一个 Registry Bean，业务代码零改动。
 * 当前没有 actuator，因此退化到 {@link SimpleMeterRegistry}（进程内聚合），
 * 通过 {@code GET /ai/stats} 暴露快照。</p>
 *
 * <p>指标设计（遵循 USE/RED 原则，低基数优先，禁止把 requestId/query 作为 tag）：
 * <ul>
 *   <li>agent.requests{agentType,outcome}：请求量；</li>
 *   <li>agent.request.duration{agentType}：端到端耗时；</li>
 *   <li>agent.llm.calls{role,model,outcome} / agent.llm.latency：模型调用成功率与延迟；</li>
 *   <li>agent.llm.tokens{role,kind}：token 消耗（成本归因）；</li>
 *   <li>agent.tool.calls{tool,outcome} / agent.tool.truncated：工具调用与结果截断；</li>
 *   <li>agent.guard.blocks{stage,rule}：护栏拦截；</li>
 *   <li>agent.cancelled / agent.sse.errors：运行期异常终止。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class AgentMetrics {

    /** 进程内注册表；若容器中存在 MeterRegistry（接入 actuator/prometheus）则自动使用 */
    private final MeterRegistry registry;

    public AgentMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry provided = registryProvider.getIfAvailable();
        this.registry = provided != null ? provided : new SimpleMeterRegistry();
        log.info("[AgentMetrics] 使用 MeterRegistry: {}", this.registry.getClass().getSimpleName());
    }

    public MeterRegistry registry() {
        return registry;
    }

    public void request(String agentType, String outcome, long costMs) {
        safe(() -> {
            Counter.builder("agent.requests")
                    .description("Agent 请求数")
                    .tag("agentType", n(agentType))
                    .tag("outcome", n(outcome))
                    .register(registry).increment();
            Timer.builder("agent.request.duration")
                    .tag("agentType", n(agentType))
                    .register(registry).record(Math.max(0, costMs), TimeUnit.MILLISECONDS);
        });
    }

    public void modelCall(String role, String model, boolean success, long costMs,
                          Integer promptTokens, Integer completionTokens) {
        safe(() -> {
            Counter.builder("agent.llm.calls")
                    .description("模型调用次数")
                    .tag("role", n(role))
                    .tag("model", n(model))
                    .tag("outcome", success ? "success" : "error")
                    .register(registry).increment();
            Timer.builder("agent.llm.latency")
                    .tag("role", n(role))
                    .tag("model", n(model))
                    .register(registry).record(Math.max(0, costMs), TimeUnit.MILLISECONDS);
            if (promptTokens != null && promptTokens > 0) {
                Counter.builder("agent.llm.tokens").tag("role", n(role)).tag("kind", "prompt")
                        .register(registry).increment(promptTokens);
            }
            if (completionTokens != null && completionTokens > 0) {
                Counter.builder("agent.llm.tokens").tag("role", n(role)).tag("kind", "completion")
                        .register(registry).increment(completionTokens);
            }
        });
    }

    public void toolCall(String tool, boolean success, long costMs, boolean truncated) {
        safe(() -> {
            Counter.builder("agent.tool.calls")
                    .description("工具调用次数")
                    .tag("tool", n(tool))
                    .tag("outcome", success ? "success" : "error")
                    .register(registry).increment();
            Timer.builder("agent.tool.duration")
                    .tag("tool", n(tool))
                    .register(registry).record(Math.max(0, costMs), TimeUnit.MILLISECONDS);
            if (truncated) {
                Counter.builder("agent.tool.truncated").tag("tool", n(tool)).register(registry).increment();
            }
        });
    }

    public void guardBlocked(String stage, String rule) {
        safe(() -> Counter.builder("agent.guard.blocks")
                .description("护栏拦截次数")
                .tag("stage", n(stage))
                .tag("rule", n(rule))
                .register(registry).increment());
    }

    public void guardModified(String stage, String rule) {
        safe(() -> Counter.builder("agent.guard.modified")
                .description("护栏改写次数（脱敏/改写后放行）")
                .tag("stage", n(stage))
                .tag("rule", n(rule))
                .register(registry).increment());
    }

    public void cancelled(String reason) {
        safe(() -> Counter.builder("agent.cancelled").tag("reason", n(reason)).register(registry).increment());
    }

    public void sseError() {
        safe(() -> Counter.builder("agent.sse.errors").register(registry).increment());
    }

    /**
     * 指标快照：给运维/调试用，避免必须接入外部系统才能观察。
     * 返回结构化 Map，前端或 curl 均可直接消费。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backend", registry.getClass().getSimpleName());

        Map<String, Object> requests = new LinkedHashMap<>();
        requests.put("total", sum("agent.requests"));
        requests.put("success", sumWithTag("agent.requests", "outcome", "success"));
        requests.put("failed", sumWithTag("agent.requests", "outcome", "error"));
        requests.put("blocked", sumWithTag("agent.requests", "outcome", "blocked"));
        requests.put("cancelled", sum("agent.cancelled"));
        requests.put("avgCostMs", meanMs("agent.request.duration"));
        requests.put("maxCostMs", maxMs("agent.request.duration"));
        out.put("requests", requests);

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("calls", sum("agent.llm.calls"));
        llm.put("errors", sumWithTag("agent.llm.calls", "outcome", "error"));
        llm.put("promptTokens", sumWithTag("agent.llm.tokens", "kind", "prompt"));
        llm.put("completionTokens", sumWithTag("agent.llm.tokens", "kind", "completion"));
        llm.put("avgLatencyMs", meanMs("agent.llm.latency"));
        out.put("llm", llm);

        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("calls", sum("agent.tool.calls"));
        tools.put("errors", sumWithTag("agent.tool.calls", "outcome", "error"));
        tools.put("truncated", sum("agent.tool.truncated"));
        tools.put("avgLatencyMs", meanMs("agent.tool.duration"));
        out.put("tools", tools);

        Map<String, Object> guard = new LinkedHashMap<>();
        guard.put("blocked", sum("agent.guard.blocks"));
        guard.put("sseErrors", sum("agent.sse.errors"));
        out.put("guard", guard);

        out.put("meters", registry.getMeters().size());
        return out;
    }

    private double sum(String name) {
        return registry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private double sumWithTag(String name, String key, String value) {
        return registry.find(name).tag(key, value).counters().stream().mapToDouble(Counter::count).sum();
    }

    private double meanMs(String name) {
        return registry.find(name).timers().stream()
                .mapToDouble(t -> t.mean(TimeUnit.MILLISECONDS))
                .average().orElse(0d);
    }

    private double maxMs(String name) {
        return registry.find(name).timers().stream()
                .mapToDouble(t -> t.max(TimeUnit.MILLISECONDS))
                .max().orElse(0d);
    }

    private String n(String v) {
        return (v == null || v.isBlank()) ? "unknown" : v;
    }

    /** 指标绝不能影响主链路：任何采集异常都吞掉并降级为 debug 日志 */
    private void safe(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("[AgentMetrics] 指标采集失败: {}", e.getMessage());
        }
    }
}
