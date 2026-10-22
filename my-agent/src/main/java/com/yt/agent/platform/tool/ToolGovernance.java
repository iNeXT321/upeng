package com.yt.agent.platform.tool;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.platform.metrics.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * 工具治理入口：把原始工具数组包装成"受治理的工具数组"。
 *
 * <p>为什么在"每次请求"维度治理，而不是在 Bean 初始化时一次性包装：
 * 白名单可能随租户/用户变化（未来做多租户），重复调用计数也必须按请求隔离
 * （否则上一个用户的调用历史会污染下一个用户）。</p>
 */
@Slf4j
@Component
public class ToolGovernance {

    private final ToolPolicyProperties policy;
    private final AgentMetrics metrics;
    private final ExecutorService executor;

    public ToolGovernance(ToolPolicyProperties policy, AgentMetrics metrics,
                          @Qualifier("agentWorkerExecutor") ExecutorService executor) {
        this.policy = policy;
        this.metrics = metrics;
        this.executor = executor;
    }

    public ToolCallback[] govern(ToolCallback[] raw, AgentContext context) {
        if (raw == null || raw.length == 0) {
            return new ToolCallback[0];
        }
        ToolCallback[] governed = Arrays.stream(raw)
                .filter(Objects::nonNull)
                .map(callback -> (ToolCallback) new GovernedToolCallback(callback, policy, metrics, context, executor))
                .toArray(ToolCallback[]::new);
        log.info("[ToolGovernance] requestId={} 治理工具 {} 个, allow={}, deny={}",
                context == null ? "-" : context.getRequestId(), governed.length,
                policy.getAllow(), policy.getDeny());
        return governed;
    }

    /** 工具清单（供 /ai/tools 调试与前端"能力展示"） */
    public List<Map<String, Object>> describe(ToolCallback[] tools) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (tools == null) {
            return list;
        }
        for (ToolCallback tool : tools) {
            if (tool == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.getToolDefinition().name());
            item.put("description", tool.getToolDefinition().description());
            list.add(item);
        }
        return list;
    }

    public ToolPolicyProperties policy() {
        return policy;
    }
}
