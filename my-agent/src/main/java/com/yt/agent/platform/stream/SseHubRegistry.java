package com.yt.agent.platform.stream;

import com.yt.agent.platform.runtime.AgentRuntimeProperties;
import com.yt.agent.platform.runtime.CancellationToken;
import com.yt.agent.platform.runtime.CancellationRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * SseHub 注册表：requestId → hub。
 *
 * <p>作用：让"重连"能找到"同一次执行"——同一 requestId 的后到连接
 * 直接挂到已有 hub 上，而不是再跑一遍 Agent。</p>
 *
 * <p>清理策略：定时扫描已结束的 hub，结束后保留 {@code RETAIN_MS}
 * 供迟到重连补事件，超时释放内存。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseHubRegistry {

    private static final long RETAIN_MS = 10 * 60_000L; // 结束后保留 10 分钟供回放
    private static final long CLEAN_INTERVAL_MS = 60_000L;

    private final Map<String, SseHub> hubs = new ConcurrentHashMap<>();
    private final Map<String, Long> finishedAt = new ConcurrentHashMap<>();
    private final CancellationRegistry cancellationRegistry;
    private final AgentRuntimeProperties props;

    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-hub-cleaner");
        t.setDaemon(true);
        return t;
    });

    /**
     * 获取或创建 hub。factory 保证同一 requestId 只创建一次、只注册一个取消令牌。
     * （synchronized 于 lambda 之外：getOrCreate 语义要求幂等）
     */
    public synchronized SseHub getOrCreate(String requestId) {
        SseHub hub = hubs.get(requestId);
        if (hub != null) {
            return hub;
        }
        CancellationToken token = cancellationRegistry.register(requestId);
        hub = new SseHub(requestId, token, props, cleaner);
        hubs.put(requestId, hub);
        finishedAt.remove(requestId);
        return hub;
    }

    public SseHub find(String requestId) {
        return hubs.get(requestId);
    }

    public void markFinished(String requestId) {
        finishedAt.putIfAbsent(requestId, System.currentTimeMillis());
    }

    @jakarta.annotation.PostConstruct
    public void start() {
        cleaner.scheduleWithFixedDelay(this::cleanup, CLEAN_INTERVAL_MS, CLEAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        cleaner.shutdownNow();
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        finishedAt.forEach((id, t) -> {
            if (now - t > RETAIN_MS) {
                hubs.remove(id);
                finishedAt.remove(id);
                log.debug("SseHub 释放: {}", id);
            }
        });
    }
}
