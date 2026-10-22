package com.yt.agent.tools;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.nl2sql.SchemaLinker;
import com.yt.agent.nl2sql.SqlExecutor;
import com.yt.agent.nl2sql.SqlSafetyGuard;
import com.yt.agent.nl2sql.SqlSelfCorrector;
import com.yt.agent.platform.knowledge.KnowledgeTool;
import com.yt.agent.platform.tool.ToolGovernance;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 集中的工具注册类。
 *
 * <p>工具来源有三类，最终合并成一份"本次请求可用工具清单"：
 * <ol>
 *   <li><b>MCP 远程工具</b>：通过 {@link ToolCallbackProvider} 注入，
 *       Server 侧新增工具后客户端自动可见（无需改代码）；</li>
 *   <li><b>本地能力工具</b>：Agent Skills（渐进式披露）、问数（Schema 召回 + SQL 执行）；</li>
 *   <li><b>知识库工具</b>：内部文档检索。</li>
 * </ol>
 * </p>
 *
 * <p>两个关键处理：
 * <ul>
 *   <li><b>按名去重</b>：MCP 与本地工具重名时以先注册者为准。重名会让模型困惑
 *       （同一份 schema 出现两次），也可能导致调用被路由到非预期实现；</li>
 *   <li><b>统一治理</b>：所有工具都经过 {@link ToolGovernance} 包装，
 *       保证白名单/超时/截断/重复抑制对 MCP 工具同样生效——不能因为工具来自远端就失去管控。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class ToolRegistration {

    @Resource
    private ObjectProvider<ToolCallbackProvider> toolCallbackProviders;

    @Resource
    private SchemaLinker schemaLinker;

    @Resource
    private SqlSafetyGuard sqlSafetyGuard;

    @Resource
    private SqlExecutor sqlExecutor;

    @Resource
    private SqlSelfCorrector sqlSelfCorrector;

    @Resource
    private ToolGovernance toolGovernance;

    @Resource
    private KnowledgeTool knowledgeTool;

    /**
     * Agent Skills 技能目录（classpath 通配符），可通过配置覆盖。
     * 遵循 agentskills.io 规范，兼容 Claude Code 的 SKILL.md 格式。
     */
    @Value("${autobots.autoagent.skills.location:classpath:/.agent/skills/*/SKILL.md}")
    private String skillsLocation;

    public ToolCallback[] allTools(AgentContext agentContext) {
        List<ToolCallback> raw = new ArrayList<>();

        // 1) MCP 远程工具（可能未配置任何 Server，此时 Provider 为空）
        toolCallbackProviders.orderedStream().forEach(provider -> {
            ToolCallback[] callbacks = provider.getToolCallbacks();
            if (callbacks != null) {
                raw.addAll(List.of(callbacks));
            }
        });

        // 2) 本地工具
        SkillsTool skillsTool = new SkillsTool(skillsLocation);
        Nl2SqlTool nl2SqlTool = new Nl2SqlTool(schemaLinker, sqlSafetyGuard, sqlExecutor, sqlSelfCorrector);
        ToolCallback[] localTools = ToolCallbacks.from(skillsTool, nl2SqlTool, knowledgeTool);
        raw.addAll(List.of(localTools));

        // 3) 按名去重（保留先注册者）
        Map<String, ToolCallback> deduped = new LinkedHashMap<>();
        for (ToolCallback callback : raw) {
            if (callback == null) {
                continue;
            }
            String name = callback.getToolDefinition().name();
            ToolCallback existing = deduped.putIfAbsent(name, callback);
            if (existing != null) {
                log.warn("[ToolRegistration] 工具重名，已忽略后注册的实现: {}", name);
            }
        }

        ToolCallback[] governed = toolGovernance.govern(deduped.values().toArray(ToolCallback[]::new), agentContext);
        log.info("[ToolRegistration] requestId={} 可用工具 {} 个: {}",
                agentContext == null ? "-" : agentContext.getRequestId(), governed.length,
                governed.length == 0 ? "[]" : Stream.of(governed)
                        .map(t -> t.getToolDefinition().name()).toList());
        return governed;
    }
}
