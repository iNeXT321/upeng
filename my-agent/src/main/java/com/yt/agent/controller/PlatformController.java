package com.yt.agent.controller;

import com.yt.agent.eval.Nl2SqlEvalService;
import com.yt.agent.platform.guard.GuardChain;
import com.yt.agent.platform.knowledge.KnowledgeDoc;
import com.yt.agent.platform.knowledge.KnowledgeHit;
import com.yt.agent.platform.knowledge.KnowledgeService;
import com.yt.agent.platform.llm.LlmGateway;
import com.yt.agent.platform.llm.ModelRole;
import com.yt.agent.platform.llm.RoutingLlmGateway;
import com.yt.agent.platform.metrics.AgentMetrics;
import com.yt.agent.platform.persistence.SessionMapper;
import com.yt.agent.platform.persistence.SessionService;
import com.yt.agent.platform.persistence.SessionStore;
import com.yt.agent.platform.runtime.AgentRuntime;
import com.yt.agent.platform.runtime.AgentRuntimeProperties;
import com.yt.agent.platform.runtime.CancellationRegistry;
import com.yt.agent.platform.tool.ToolGovernance;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.impl.AgentHandlerFactory;
import com.yt.agent.tools.ToolRegistration;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台运维接口：把"看不见的内部状态"变成可查询的接口。
 *
 * <p>为什么 Agent 系统必须自带这些接口：Agent 的行为高度依赖配置（模型路由、护栏开关、
 * 工具白名单、知识库后端）。线上出现"回答变差/工具不生效/很慢"时，
 * 如果没有健康检查与指标，排查只能靠猜和翻日志。这些接口是"可运维性"的最小集合。</p>
 *
 * <p>安全提示：这些接口会暴露内部配置（模型名、工具清单），生产环境应放在内网/鉴权之后。
 * 当前项目没有引入 Spring Security，属于已知缺口，已在架构评审文档中列为待办。</p>
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class PlatformController {

    @Resource
    private AgentRuntime agentRuntime;

    @Resource
    private CancellationRegistry cancellationRegistry;

    @Resource
    private AgentMetrics agentMetrics;

    @Resource
    private SessionService sessionService;

    @Resource
    private KnowledgeService knowledgeService;

    @Resource
    private ToolRegistration toolRegistration;

    @Resource
    private ToolGovernance toolGovernance;

    @Resource
    private Nl2SqlEvalService nl2SqlEvalService;

    @Resource
    private LlmGateway llmGateway;

    @Resource
    private GuardChain guardChain;

    @Resource
    private AgentHandlerFactory handlerFactory;

    @Resource
    private AgentRuntimeProperties runtimeProperties;

    /** 主动停止某个请求（前端"停止生成"按钮） */
    @PostMapping("/stop/{requestId}")
    public Map<String, Object> stop(@PathVariable String requestId) {
        boolean cancelled = cancellationRegistry.cancel(requestId, "user_stop");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("cancelled", cancelled);
        out.put("message", cancelled ? "已发送停止信号" : "未找到正在执行的请求（可能已结束）");
        return out;
    }

    /** 运行指标快照 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metrics", agentMetrics.snapshot());
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("activeRequests", agentRuntime.activeRequests());
        runtime.put("maxConcurrentRequests", runtimeProperties.getMaxConcurrentRequests());
        runtime.put("requestTimeoutMs", runtimeProperties.getRequestTimeoutMs());
        runtime.put("cancellableRequests", cancellationRegistry.active());
        out.put("runtime", runtime);
        return out;
    }

    /** 健康检查：模型路由、存储后端、知识库、护栏、处理器清单 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean llmOk = llmGateway.available(ModelRole.REACT);
        Object llm;
        if (llmGateway instanceof RoutingLlmGateway routing) {
            llm = routing.health();
        } else {
            llm = Map.of("available", llmOk);
        }

        SessionStore store = sessionService.store();
        Map<String, Object> persistence = new LinkedHashMap<>();
        persistence.put("backend", store.backend());
        persistence.put("persistent", store.persistent());
        persistence.put("historyTurns", sessionService.properties().getHistoryTurns());

        Map<String, Object> knowledge = new LinkedHashMap<>();
        knowledge.put("enabled", knowledgeService.enabled());
        knowledge.put("backend", knowledgeService.backend());
        knowledge.put("chunks", knowledgeService.size());

        Map<String, Object> guard = new LinkedHashMap<>();
        guard.put("enabled", guardChain.enabled());
        guard.put("rules", guardChain.rules());

        out.put("status", llmOk ? "UP" : "DEGRADED");
        out.put("llm", llm);
        out.put("persistence", persistence);
        out.put("knowledge", knowledge);
        out.put("guard", guard);
        out.put("handlers", handlerFactory.registeredHandlers());
        out.put("toolPolicy", Map.of(
                "allow", toolGovernance.policy().getAllow(),
                "deny", toolGovernance.policy().getDeny(),
                "maxResultChars", toolGovernance.policy().getMaxResultChars(),
                "callTimeoutMs", toolGovernance.policy().getCallTimeoutMs()));
        return out;
    }

    /** 当前可用工具清单（不执行任何工具，仅返回定义） */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        List<Map<String, Object>> list = toolGovernance.describe(toolRegistration.allTools(null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("tools", list);
        return out;
    }

    /** 会话列表 */
    @GetMapping("/sessions")
    public List<?> sessions(@RequestParam(required = false) String userId,
                            @RequestParam(defaultValue = "20") int limit) {
        return sessionService.list(userId, limit);
    }

    /** 会话详情（回放成可读文本，便于排查"模型到底看到了什么"） */
    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> session(@PathVariable String sessionId,
                                       @RequestParam(defaultValue = "100") int limit) {
        var turns = sessionService.store().load(sessionId, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("turns", turns.size());
        out.put("messages", turns);
        out.put("rendered", SessionMapper.render(turns));
        return out;
    }

    /** 清空会话 */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        sessionService.clear(sessionId);
        return Map.of("sessionId", sessionId, "cleared", true);
    }

    /** 知识库概览（已索引文档清单；检索调试用 /knowledge/search） */
    @GetMapping("/knowledge")
    public Map<String, Object> knowledge() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", knowledgeService.enabled());
        out.put("backend", knowledgeService.backend());
        out.put("chunks", knowledgeService.size());
        out.put("docs", knowledgeService.store().listDocs().stream().map(d -> Map.of(
                "id", d.getId(),
                "title", d.getTitle() == null ? "" : d.getTitle(),
                "source", d.getSource() == null ? "" : d.getSource(),
                "chars", d.getContent() == null ? 0 : d.getContent().length(),
                "tags", d.getTags() == null ? List.of() : d.getTags())).toList());
        return out;
    }

    /** 知识库写入（管理端） */
    @PostMapping("/knowledge")
    public Map<String, Object> ingest(@RequestBody KnowledgeIngestRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        KnowledgeDoc doc = knowledgeService.ingest(
                request.title() == null ? "未命名文档" : request.title(),
                request.content(),
                request.source() == null ? "api" : request.source(),
                request.tags());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("title", doc.getTitle());
        out.put("chunks", knowledgeService.size());
        out.put("backend", knowledgeService.backend());
        return out;
    }

    /** 知识库检索（调试召回效果） */
    @GetMapping("/knowledge/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "4") int topK) {
        List<KnowledgeHit> hits = knowledgeService.search(q, topK);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("backend", knowledgeService.backend());
        out.put("hits", hits.stream().map(h -> Map.of(
                "title", h.getDoc().getTitle() == null ? "" : h.getDoc().getTitle(),
                "source", h.getDoc().getSource() == null ? "" : h.getDoc().getSource(),
                "score", h.getScore(),
                "content", abbreviate(h.getDoc().getContent(), 300))).toList());
        return out;
    }

    /** NL2SQL 端到端评测（需要已配置只读数据源） */
    @PostMapping("/eval/nl2sql")
    public Map<String, Object> evalNl2Sql(@RequestParam(defaultValue = "0") int maxCases) {
        return nl2SqlEvalService.run(maxCases);
    }

    private String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 知识写入请求体。用 record 承载，避免为一个 DTO 新建文件。
     */
    public record KnowledgeIngestRequest(String title, String content, String source, List<String> tags) {
    }

    /** 供前端探测请求体结构（避免误用 AgentRequest 字段名） */
    @GetMapping("/eval/sample")
    public AgentRequest sampleRequest() {
        return AgentRequest.builder().query("上个月GMV是多少").agentType(5).isStream(true).build();
    }
}
