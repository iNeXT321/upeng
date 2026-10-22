package com.yt.agent.controller;

import com.alibaba.fastjson.JSON;
import com.yt.agent.agent.enums.AgentType;
import com.yt.agent.platform.guard.GuardChain;
import com.yt.agent.platform.runtime.AgentRuntime;
import com.yt.agent.platform.runtime.AgentRuntimeProperties;
import com.yt.agent.platform.runtime.CancellationToken;
import com.yt.agent.platform.runtime.CancellationRegistry;
import com.yt.agent.printer.GuardedPrinter;
import com.yt.agent.printer.Printer;
import com.yt.agent.printer.SSEPrinter;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.utils.StringUtil;
import jakarta.annotation.Resource;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent HTTP 入口（SSE 流式）。
 *
 * <p>本类只做三件事：<b>适配 HTTP</b>（SseEmitter 生命周期）、<b>参数归一化</b>、
 * <b>把活交给 {@link AgentRuntime}</b>。业务编排全部下沉，避免又回到
 * "Controller 里塞满 200 行、异常路径漏掉 emitter.complete()"的老问题。</p>
 *
 * <p>本次修复的连接层缺陷：
 * <ol>
 *   <li>异常时未 complete → 连接悬挂直到网关超时（现在 finally 保证关闭）；</li>
 *   <li>心跳任务未取消 → 每个请求泄漏一个调度任务，越跑越多；</li>
 *   <li>心跳用字符串 "heartbeat" 混在业务消息流里 → 前端可能误当消息渲染，
 *       改用 SSE comment（协议层心跳，不进入 onmessage）；</li>
 *   <li>客户端断开后服务端仍继续跑完 40 步 → onCompletion/onError 取消令牌，
 *       真正停止推理（省钱、省算力）。</li>
 * </ol>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AgentController {

    /** 心跳调度器：单线程足够（心跳只是写 socket，不阻塞），daemon 避免影响停机 */
    private static final ScheduledExecutorService HEARTBEAT =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    @Resource
    private AgentRuntime agentRuntime;

    @Resource
    private GuardChain guardChain;

    @Resource
    private AgentRuntimeProperties runtimeProperties;

    @Resource
    private CancellationRegistry cancellationRegistry;

    @Resource
    @Qualifier("agentWorkerExecutor")
    private ExecutorService worker;

    @PostMapping("/autoAgent")
    public SseEmitter autoAgent(@RequestBody AgentRequest request) {
        normalize(request);
        log.info("{} auto agent request: {}", request.getRequestId(), JSON.toJSONString(request));

        // 参数问题在建立 SSE 之前就拒绝，避免前端先拿到 200 再收到错误
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return immediateError("EMPTY_QUERY", "问题内容不能为空。");
        }

        SseEmitter emitter = new SseEmitter(runtimeProperties.getSseTimeoutMs());
        CancellationToken token = cancellationRegistry.register(request.getRequestId());

        ScheduledFuture<?> heartbeat = startHeartbeat(emitter, request.getRequestId());
        AtomicBoolean closedByServer = new AtomicBoolean(false);
        registerMonitor(emitter, request.getRequestId(), heartbeat, token, closedByServer);

        Printer printer = new GuardedPrinter(
                new SSEPrinter(emitter, request, request.getAgentType()), guardChain);

        worker.execute(() -> {
            try {
                agentRuntime.execute(request, printer);
            } catch (Exception e) {
                // Runtime 已把业务异常转成 error 事件；走到这里说明是发送/收尾阶段的意外
                log.error("{} auto agent unexpected error", request.getRequestId(), e);
            } finally {
                closedByServer.set(true);
                heartbeat.cancel(true);
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("{} emitter complete ignored: {}", request.getRequestId(), e.getMessage());
                }
                log.info("{} auto agent request complete", request.getRequestId());
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

    private ScheduledFuture<?> startHeartbeat(SseEmitter emitter, String requestId) {
        long interval = Math.max(1000L, runtimeProperties.getHeartbeatIntervalMs());
        return HEARTBEAT.scheduleAtFixedRate(() -> {
            try {
                // 用 comment 而不是业务消息：EventSource 不会把 comment 交给 onmessage
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                log.info("{} heartbeat failed, closing connection: {}", requestId, e.getMessage());
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                    // 已经关闭
                }
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void registerMonitor(SseEmitter emitter, String requestId, ScheduledFuture<?> heartbeat,
                                 CancellationToken token, AtomicBoolean closedByServer) {
        emitter.onCompletion(() -> {
            heartbeat.cancel(true);
            // 服务端主动 complete 时不算取消；客户端断开才需要停止推理
            if (!closedByServer.get()) {
                log.info("{} SSE completed by client, cancelling agent", requestId);
                token.cancel("client_disconnected");
            }
        });
        emitter.onTimeout(() -> {
            log.info("{} SSE connection timed out", requestId);
            heartbeat.cancel(true);
            token.cancel("sse_timeout");
            emitter.complete();
        });
        emitter.onError((ex) -> {
            log.info("{} SSE connection error: {}", requestId, ex.getMessage());
            heartbeat.cancel(true);
            token.cancel("sse_error");
        });
    }
}
