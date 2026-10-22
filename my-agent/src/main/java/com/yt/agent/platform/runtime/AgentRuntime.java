package com.yt.agent.platform.runtime;

import com.yt.agent.agent.AgentContext;
import com.yt.agent.config.AgentConfig;
import com.yt.agent.platform.guard.GuardChain;
import com.yt.agent.platform.guard.GuardContext;
import com.yt.agent.platform.guard.GuardDecision;
import com.yt.agent.platform.llm.LlmGateway;
import com.yt.agent.platform.llm.ModelUnavailableException;
import com.yt.agent.platform.metrics.AgentMetrics;
import com.yt.agent.platform.persistence.SessionService;
import com.yt.agent.printer.Printer;
import com.yt.agent.req.AgentRequest;
import com.yt.agent.service.AgentHandlerService;
import com.yt.agent.service.impl.AgentHandlerFactory;
import com.yt.agent.tools.ToolRegistration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Agent 运行时 —— 一次请求的完整生命周期编排。
 *
 * <p>为什么要把这段逻辑从 Controller 里抽出来：原 Controller 同时承担了
 * HTTP 适配、心跳、上下文装配、业务执行、异常处理五件事，结果是
 * 任何一处异常都会让 {@code emitter.complete()} 被跳过（连接悬挂），
 * 而且根本无法复用（比如以后接 WebSocket、消息队列触发，就得复制一遍）。</p>
 *
 * <p>本类只做编排，具体推理交给 handler；职责边界：
 * <ol>
 *   <li>并发限流（信号量）——保护下游模型与数据库；</li>
 *   <li>输入护栏——在花钱调模型之前拦截；</li>
 *   <li>会话回放——把多轮历史装进 context；</li>
 *   <li>工具装配——按请求治理后的工具清单；</li>
 *   <li>统一异常语义——取消/超时/配置错误/未知错误分别给出可读事件；</li>
 *   <li>收尾——落库、释放令牌、上报指标。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
public class AgentRuntime {

    private final LlmGateway llmGateway;
    private final ToolRegistration toolRegistration;
    private final AgentHandlerFactory handlerFactory;
    private final GuardChain guardChain;
    private final SessionService sessionService;
    private final CancellationRegistry cancellationRegistry;
    private final AgentMetrics metrics;
    private final AgentRuntimeProperties properties;
    private final AgentConfig agentConfig;
    private final Semaphore permits;

    public AgentRuntime(LlmGateway llmGateway,
                        ToolRegistration toolRegistration,
                        AgentHandlerFactory handlerFactory,
                        GuardChain guardChain,
                        SessionService sessionService,
                        CancellationRegistry cancellationRegistry,
                        AgentMetrics metrics,
                        AgentRuntimeProperties properties,
                        AgentConfig agentConfig) {
        this.llmGateway = llmGateway;
        this.toolRegistration = toolRegistration;
        this.handlerFactory = handlerFactory;
        this.guardChain = guardChain;
        this.sessionService = sessionService;
        this.cancellationRegistry = cancellationRegistry;
        this.metrics = metrics;
        this.properties = properties;
        this.agentConfig = agentConfig;
        this.permits = new Semaphore(Math.max(1, properties.getMaxConcurrentRequests()), true);
    }

    /**
     * 执行一次 Agent 请求。
     *
     * <p>本方法不抛出异常：所有失败都被转换为 SSE error 事件。
     * 调用方（Controller）只负责连接生命周期，不需要理解业务异常体系。</p>
     */
    public void execute(AgentRequest request, Printer printer) {
        String requestId = request.getRequestId();
        long start = System.currentTimeMillis();
        String outcome = "success";
        boolean acquired = false;
        AgentContext context = null;
        CancellationToken token = cancellationRegistry.register(requestId);

        try {
            acquired = permits.tryAcquire(properties.getAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                outcome = "rejected";
                emitError(printer, "BUSY", "当前请求过多，请稍后重试。", "admission");
                return;
            }

            // 1) 输入护栏：在调用模型之前拦截，避免为攻击性/超长输入付费
            GuardDecision guard = guardChain.checkInput(request.getQuery(),
                    GuardContext.of(requestId, request.getErp(), request.getAgentType()));
            if (!guard.isAllowed()) {
                outcome = "blocked";
                emitError(printer, "GUARD_BLOCKED", guard.getReason(), "guard");
                return;
            }
            request.setQuery(guard.getText());

            // 2) 会话回放：多轮上下文真正进入 Agent Memory（原实现完全丢弃了前端传来的历史）
            SessionService.LoadedSession session = sessionService.load(request.getSessionId());

            // 3) 上下文装配
            context = AgentContext.builder()
                    .requestId(requestId)
                    .sessionId(request.getSessionId())
                    .userId(request.getErp())
                    .query(request.getQuery())
                    .rewrittenQuery(request.getQuery())
                    .printer(printer)
                    .isStream(Boolean.TRUE.equals(request.getIsStream()))
                    .agentType(request.getAgentType())
                    .sopPrompt(request.getAgentType() != null && request.getAgentType() == 3
                            ? resolveSopPrompt() : "")
                    .basePrompt(request.getAgentType() != null && request.getAgentType() == 5
                            ? resolveBasePrompt() : "")
                    .llm(llmGateway)
                    .chatClient(llmGateway.chat(com.yt.agent.platform.llm.ModelRole.REACT))
                    .history(session.messages())
                    .cancellation(token)
                    .deadlineEpochMs(start + properties.getRequestTimeoutMs())
                    .build();
            context.setAvailableTools(toolRegistration.allTools(context));

            // 4) 分派执行
            AgentHandlerService handler = handlerFactory.getHandler(context, request);
            log.info("[Runtime] requestId={} 开始执行 type={} handler={} historyTurns={}",
                    requestId, request.getAgentType(), handler.getClass().getSimpleName(), session.turnCount());
            handler.handle(context, request);

        } catch (AgentCancelledException e) {
            outcome = "cancelled";
            metrics.cancelled(e.getMessage());
            emitError(printer, "CANCELLED", "任务已停止。", "runtime");
        } catch (AgentTimeoutException e) {
            outcome = "timeout";
            emitError(printer, "TIMEOUT", "任务执行超过时间预算（"
                    + (properties.getRequestTimeoutMs() / 1000) + " 秒），已终止。可缩小问题范围后重试。", "runtime");
        } catch (UnsupportedAgentTypeException e) {
            outcome = "error";
            emitError(printer, "UNSUPPORTED_AGENT_TYPE", e.getMessage(), "dispatch");
        } catch (ModelUnavailableException e) {
            outcome = "error";
            log.error("[Runtime] 模型不可用 requestId={}: {}", requestId, e.getMessage());
            emitError(printer, "MODEL_UNAVAILABLE",
                    "模型服务未就绪：" + e.getMessage(), "model");
        } catch (Exception e) {
            outcome = "error";
            log.error("[Runtime] 执行失败 requestId={}", requestId, e);
            emitError(printer, "INTERNAL_ERROR",
                    "执行失败（requestId=" + requestId + "）：" + safeMessage(e), "runtime");
        } finally {
            // 5) 收尾：落库 → 释放令牌 → 释放许可 → 指标
            //    顺序很重要：先落库再释放许可，保证观察到的并发数与真实占用一致
            try {
                if (context != null) {
                    sessionService.persist(request.getSessionId(), request.getErp(), context.transcriptSnapshot());
                }
            } catch (Exception e) {
                log.warn("[Runtime] 会话落库异常 requestId={}: {}", requestId, e.getMessage());
            }
            cancellationRegistry.remove(requestId);
            if (acquired) {
                permits.release();
            }
            metrics.request(String.valueOf(request.getAgentType()), outcome, System.currentTimeMillis() - start);
            log.info("[Runtime] requestId={} 结束 outcome={} cost={}ms", requestId, outcome,
                    System.currentTimeMillis() - start);
        }
    }

    private String resolveSopPrompt() {
        return "";
    }

    private String resolveBasePrompt() {
        return "";
    }

    private void emitError(Printer printer, String code, String message, String stage) {
        if (printer == null) {
            log.error("[Runtime] 无 printer，无法发送错误: {} {}", code, message);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("stage", stage);
        printer.send("error", payload);
        metrics.sseError();
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return t.getClass().getSimpleName();
        }
        return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
    }

    public int activeRequests() {
        return properties.getMaxConcurrentRequests() - permits.availablePermits();
    }
}
