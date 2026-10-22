package com.yt.agent.platform.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 多目标容错模型：按「目标列表 × 尝试次数」逐级降级。
 *
 * <p>这是 Agent 生产可用性的关键一环。单模型接入的典型故障是：供应商限流(429)或区域网络抖动，
 * 表现为整轮会话失败。而 Agent 任务往往已经消耗了大量 token 和时间，最后一步失败等于全部白做。
 * 因此策略是：</p>
 *
 * <ol>
 *   <li><b>同 provider 重试</b>：仅对瞬时错误（超时/429/5xx）做指数退避重试；</li>
 *   <li><b>跨 provider 降级</b>：主模型不可用时切到备用模型（如本地 Ollama），
 *       宁可质量降级也要给出结果；</li>
 *   <li><b>不重试确定性错误</b>：鉴权失败、模型不存在、余额不足重试无意义，直接换目标或失败。</li>
 * </ol>
 *
 * <p>代价与取舍：降级到弱模型可能导致输出质量下降甚至格式不合法。这里的选择是
 * "可用性优先"，并通过指标（agent.llm.calls{outcome}）暴露降级频率——
 * 如果降级率持续偏高，说明主模型容量或配置需要调整，而不是静默接受。</p>
 */
@Slf4j
public class ResilientChatModel implements ChatModel {

    private final List<MeteredChatModel> targets;
    private final int maxAttempts;
    private final long backoffMs;

    public ResilientChatModel(List<MeteredChatModel> targets, int maxAttempts, long backoffMs) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("ResilientChatModel 至少需要一个目标模型");
        }
        this.targets = List.copyOf(targets);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMs = Math.max(0, backoffMs);
    }

    public List<MeteredChatModel> getTargets() {
        return targets;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ModelCallException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            for (MeteredChatModel target : targets) {
                try {
                    return target.call(withModel(prompt, target.getModelId()));
                } catch (ModelCallException e) {
                    last = e;
                    log.warn("[ResilientChatModel] role={} 目标 {}:{} 第{}次失败(retryable={}): {}",
                            target.getRole().getKey(), target.getProvider(), target.getModelId(),
                            attempt, e.isRetryable(), e.getMessage());
                }
            }
            if (attempt < maxAttempts) {
                sleepBackoff(attempt);
            }
        }
        throw last != null ? last : new ModelCallException("所有模型目标均不可用", false, "unknown", "unknown", null);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Flux<ChatResponse> chain = Flux.error(new IllegalStateException("no target"));
        // 反向组合 onErrorResume，保证顺序仍是 targets 的顺序
        for (int i = targets.size() - 1; i >= 0; i--) {
            MeteredChatModel target = targets.get(i);
            Flux<ChatResponse> current = chain;
            chain = target.stream(withModel(prompt, target.getModelId()))
                    .onErrorResume(e -> {
                        log.warn("[ResilientChatModel] 流式降级 {}:{} -> {}", target.getProvider(),
                                target.getModelId(), e.getMessage());
                        return current;
                    });
        }
        return chain;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return targets.get(0).getDefaultOptions();
    }

    /**
     * 把目标模型名写入 Prompt 选项。
     *
     * <p>为什么必须在这里改而不是在 ChatClient 的 defaultOptions 里设：
     * 调用方（各 Agent）会显式传入 {@code ToolCallingChatOptions}（关闭内置工具执行），
     * 显式选项会覆盖默认选项，导致 defaultOptions 里的 model 被丢弃。
     * 只有在真正发起调用的最内层改写，才能保证"路由到哪个模型就请求哪个模型"。</p>
     */
    private Prompt withModel(Prompt prompt, String model) {
        if (model == null || model.isBlank()) {
            return prompt;
        }
        ChatOptions options = prompt.getOptions();
        if (options != null && model.equals(options.getModel())) {
            return prompt;
        }
        ChatOptions rebuilt = rebuild(options, model);
        return new Prompt(prompt.getInstructions(), rebuilt);
    }

    private ChatOptions rebuild(ChatOptions options, String model) {
        if (options instanceof ToolCallingChatOptions toolOptions) {
            ToolCallingChatOptions.Builder builder = ToolCallingChatOptions.builder()
                    .model(model)
                    .internalToolExecutionEnabled(Boolean.TRUE.equals(toolOptions.getInternalToolExecutionEnabled()))
                    .temperature(options.getTemperature())
                    .topP(options.getTopP())
                    .maxTokens(options.getMaxTokens())
                    .frequencyPenalty(options.getFrequencyPenalty())
                    .presencePenalty(options.getPresencePenalty())
                    .stopSequences(options.getStopSequences());
            if (options.getTopK() != null) {
                builder.topK(options.getTopK());
            }
            if (toolOptions.getToolCallbacks() != null && !toolOptions.getToolCallbacks().isEmpty()) {
                builder.toolCallbacks(toolOptions.getToolCallbacks());
            }
            return builder.build();
        }
        ChatOptions.Builder builder = ChatOptions.builder()
                .model(model)
                .temperature(options == null ? null : options.getTemperature())
                .topP(options == null ? null : options.getTopP())
                .maxTokens(options == null ? null : options.getMaxTokens())
                .frequencyPenalty(options == null ? null : options.getFrequencyPenalty())
                .presencePenalty(options == null ? null : options.getPresencePenalty())
                .stopSequences(options == null ? null : options.getStopSequences());
        if (options != null && options.getTopK() != null) {
            builder.topK(options.getTopK());
        }
        return builder.build();
    }

    private void sleepBackoff(int attempt) {
        long base = backoffMs * attempt;
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, backoffMs / 2));
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
