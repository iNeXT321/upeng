package com.yt.agent.controller;

import com.alibaba.fastjson.JSON;
import com.yt.agent.agent.enums.AgentType;
import com.yt.agent.platform.guard.GuardChain;
import com.yt.agent.platform.rate.RateLimitedException;
import com.yt.agent.platform.runtime.AgentRuntime;
import com.yt.agent.platform.runtime.AgentRuntimeProperties;
import com.yt.agent.platform.stream.SseHub;
import com.yt.agent.platform.stream.SseHubRegistry;
import com.yt.agent.printer.GuardedPrinter;
import com.yt.agent.printer.Printer;
import com.yt.agent.printer.SSEPrinter;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.utils.StringUtil;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Agent HTTP 入口（SSE 流式）。
 *
 * <p>本类只做四件事：<b>适配 HTTP</b>（SSE 生命周期交给 {@link SseHub}）、
 * <b>参数归一化</b>、<b>限流准入</b>、<b>把活交给 {@link AgentRuntime}</b>。</p>
 *
 * <p>连接层能力（行业标准的流式对话网关形态）：</p>
 * <ol>
 *   <li>事件带 seq 序号 + 服务端事件日志：客户端断线后带 Last-Event-ID 重连同一 requestId，
 *       只补缺失事件，Agent 不重跑、不重复计费；</li>
 *   <li>断线宽限取消：全部连接断开 30s 内重连则继续执行，超时才取消，兼顾体验与成本；</li>
 *   <li>入口限流：全局令牌桶快速拒绝（429），保护下游模型配额；</li>
 *   <li>异常路径 finally 保证 hub 收尾，不悬挂连接。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AgentController {

    @Resource
    private AgentRuntime agentRuntime;

    @Resource
    private GuardChain guardChain;

    @Resource
    private AgentRuntimeProperties runtimeProperties;

    @Resource
    private SseHubRegistry sseHubRegistry;

    @Resource
    private RateLimiterRegistry rateLimiterRegistry;

    @Resource
    @Qualifier("agentWorkerExecutor")
    private ExecutorService worker;

    @PostMapping("/autoAgent")
    public SseEmitter autoAgent(@RequestBody AgentRequest request, HttpServletRequest httpRequest) {
        normalize(request);
        log.info("{} auto agent request: {}", request.getRequestId(), JSON.toJSONString(request));

        // 参数问题在建立 SSE 之前就拒绝，避免前端先拿到 200 再收到错误
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return immediateError("EMPTY_QUERY", "问题内容不能为空。");
        }

        // 入口限流：令牌桶拒绝时抛 429，保护下游模型配额（不排队堆积）
        RateLimiter limiter = rateLimiterRegistry.rateLimiter("agent");
        if (!limiter.acquirePermission()) {
            log.warn("{} rate limited", request.getRequestId());
            throw new RateLimitedException("请求过于频繁，请稍后再试");
        }

        // 断线重连：Last-Event-ID 指向客户端最后收到的事件序号
        Long lastEventId = parseLastEventId(httpRequest.getHeader("Last-Event-ID"));
        // 同一 requestId 只有一个执行（hub 幂等创建），重连/新请求共用
        SseHub hub = sseHubRegistry.getOrCreate(request.getRequestId());
        SseEmitter emitter = hub.attach(lastEventId);
        if (hub.isRunning() || hub.isFinished()) {
            // 已有执行在跑或已结束：当前连接只做事件回放/续传，不重复执行
            log.info("{} 复用进行中/已完成的执行 (running={}, finished={})",
                    request.getRequestId(), hub.isRunning(), hub.isFinished());
            return emitter;
        }

        hub.markRunning();
        Printer printer = new GuardedPrinter(
                new SSEPrinter(hub, request, request.getAgentType()), guardChain);

        // MDC 上下文快照：虚拟线程池里的日志要与 HTTP 线程共用同一个 traceId/uid
        Map<String, String> mdc = org.slf4j.MDC.getCopyOfContextMap();
        worker.execute(() -> {
            if (mdc != null) {
                org.slf4j.MDC.setContextMap(mdc);
            }
            try {
                agentRuntime.execute(request, printer);
            } catch (Exception e) {
                // Runtime 已把业务异常转成 error 事件；走到这里说明是发送/收尾阶段的意外
                log.error("{} auto agent unexpected error", request.getRequestId(), e);
            } finally {
                hub.complete();
                sseHubRegistry.markFinished(request.getRequestId());
                log.info("{} auto agent request complete", request.getRequestId());
                org.slf4j.MDC.clear();
            }
        });
        return emitter;
    }

    /**
     * 查询指定请求的 Agent 执行链路 Trace（可观测性调试接口）
     */
    @GetMapping("/trace/{requestId}")
    public String trace(@PathVariable String requestId) {
        com.yt.agent.eval.AgentTraceCollector.Trace trace =
                com.yt.agent.eval.AgentTraceCollector.get(requestId);
        if (trace == null) {
            return "未找到 requestId=" + requestId + " 的追踪记录（可能已被清理或不存在）";
        }
        return trace.prettyPrint();
    }

    /** 参数归一化：缺 requestId 会破坏 trace/取消/落库三处能力，必须补上 */
    private void normalize(AgentRequest request) {
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            request.setRequestId(StringUtil.getUUID());
        }
        if (request.getAgentType() == null) {
            request.setAgentType(AgentType.REACT.getValue());
        }
        if (request.getIsStream() == null) {
            request.setIsStream(Boolean.TRUE);
        }
        if (request.getQuery() != null) {
            request.setQuery(request.getQuery().trim());
        }
    }

    private Long parseLastEventId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private SseEmitter immediateError(String code, String message) {
        SseEmitter emitter = new SseEmitter(0L);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        try {
            emitter.send(payload);
        } catch (Exception e) {
            log.debug("immediate error send failed: {}", e.getMessage());
        }
        emitter.complete();
        return emitter;
    }
}
