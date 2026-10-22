package com.yt.agent.platform.llm;

import com.yt.agent.platform.metrics.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ChatModel 装饰器：给任意模型实现统一加上「超时 + 计量 + 异常归一化」。
 *
 * <p>为什么必须在模型层做超时：Agent 主循环是同步阻塞的，一旦某个 provider 的网络卡住，
 * 整个请求线程（以及 SSE 连接）都会被挂死，用户侧表现为"永远转圈"。
 * Spring AI 各 provider 的超时配置项名称不一致、默认值偏长，靠配置无法统一治理，
 * 因此在这里用虚拟线程 + Future.get(timeout) 做硬超时兜底。</p>
 *
 * <p>为什么异常要归一化：上层只需要知道"这次调用失败、能不能重试"，
 * 不需要理解每个 SDK 的异常体系（WebClientResponseException / IOException / ApiException…）。</p>
 */
@Slf4j
public class MeteredChatModel implements ChatModel {

    private final ChatModel delegate;
    private final String provider;
    private final String modelId;
    private final ModelRole role;
    private final long timeoutMs;
    private final AgentMetrics metrics;
    private final ExecutorService executor;

    public MeteredChatModel(ChatModel delegate, String provider, String modelId, ModelRole role,
                            long timeoutMs, AgentMetrics metrics, ExecutorService executor) {
        this.delegate = delegate;
        this.provider = provider;
        this.modelId = modelId;
        this.role = role;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 120_000L;
        this.metrics = metrics;
        this.executor = executor;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelId() {
        return modelId;
    }

    public ModelRole getRole() {
        return role;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.currentTimeMillis();
        Future<ChatResponse> future = executor.submit(() -> delegate.call(prompt));
        try {
            ChatResponse response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long cost = System.currentTimeMillis() - start;
            recordUsage(response, cost, true);
            return response;
        } catch (TimeoutException e) {
            future.cancel(true);
            long cost = System.currentTimeMillis() - start;
            recordUsage(null, cost, false);
            throw new ModelCallException(
                    String.format("模型调用超时(%dms) provider=%s model=%s", timeoutMs, provider, modelId),
                    true, provider, modelId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordUsage(null, System.currentTimeMillis() - start, false);
            throw new ModelCallException("模型调用被中断", false, provider, modelId, e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            long cost = System.currentTimeMillis() - start;
            recordUsage(null, cost, false);
            throw new ModelCallException(
                    String.format("模型调用失败 provider=%s model=%s: %s", provider, modelId, cause.getMessage()),
                    isRetryable(cause), provider, modelId, cause);
        }
    }

    /**
     * 流式调用：同样做计量与超时。
     * 注意流式的超时语义是"两个 token 之间的最大间隔"，而不是整体时长——
     * 否则长回答会被误杀。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.currentTimeMillis();
        return delegate.stream(prompt)
                .doOnNext(resp -> recordUsage(resp, System.currentTimeMillis() - start, true))
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorMap(TimeoutException.class, e -> new ModelCallException(
                        "模型流式响应超时(" + timeoutMs + "ms)", true, provider, modelId, e));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        try {
            ChatOptions options = delegate.getDefaultOptions();
            if (options != null) {
                return options;
            }
        } catch (Exception e) {
            log.debug("[MeteredChatModel] 读取默认选项失败, 使用兜底选项: {}", e.getMessage());
        }
        return ChatOptions.builder().model(modelId).build();
    }

    private void recordUsage(ChatResponse response, long costMs, boolean success) {
        Integer promptTokens = null;
        Integer completionTokens = null;
        try {
            if (response != null && response.getMetadata() != null) {
                Usage usage = response.getMetadata().getUsage();
                if (usage != null) {
                    promptTokens = usage.getPromptTokens();
                    completionTokens = usage.getCompletionTokens();
                }
            }
        } catch (Exception ignored) {
            // usage 缺失不影响主流程（部分兼容 OpenAI 的网关不返回 usage）
        }
        metrics.modelCall(role.getKey(), modelId == null ? provider : modelId, success, costMs,
                promptTokens, completionTokens);
        if (!success) {
            log.warn("[MeteredChatModel] 调用失败 role={} provider={} model={} cost={}ms",
                    role.getKey(), provider, modelId, costMs);
        }
    }

    /**
     * 复现性判断：只对"重试有意义"的错误重试。
     * 401/403/400/404 属于配置或请求问题，重试必然再次失败，还会放大费用；
     * 429/5xx/超时/连接异常才是瞬时故障。
     */
    private boolean isRetryable(Throwable cause) {
        String msg = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
        if (msg.contains("401") || msg.contains("403") || msg.contains("unauthorized")
                || msg.contains("invalid api key") || msg.contains("invalid_api_key")
                || msg.contains("400") || msg.contains("404") || msg.contains("model not found")
                || msg.contains("insufficient balance") || msg.contains("余额")) {
            return false;
        }
        return msg.contains("429") || msg.contains("rate limit") || msg.contains("timeout")
                || msg.contains("timed out") || msg.contains("502") || msg.contains("503")
                || msg.contains("504") || msg.contains("connection reset") || msg.contains("connection refused")
                || msg.contains("temporarily unavailable");
    }
}
