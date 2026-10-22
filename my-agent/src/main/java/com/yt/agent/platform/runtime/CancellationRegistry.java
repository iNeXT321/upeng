package com.yt.agent.platform.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取消令牌注册表：把 HTTP 层的 requestId 与执行线程的取消信号关联起来。
 *
 * <p>没有它就无法实现 {@code POST /ai/stop/{requestId}}：控制器与执行线程之间
 * 需要一个按 requestId 寻址的共享状态。生命周期严格跟随请求（register → remove），
 * 避免 Map 随请求量无限增长。</p>
 */
@Slf4j
@Component
public class CancellationRegistry {

    private final Map<String, CancellationToken> tokens = new ConcurrentHashMap<>();

    public CancellationToken register(String requestId) {
        CancellationToken token = new CancellationToken();
        // 同一个 requestId 重复提交（前端重试）时，以最新一次为准
        tokens.put(requestId, token);
        return token;
    }

    public Optional<CancellationToken> get(String requestId) {
        return Optional.ofNullable(tokens.get(requestId));
    }

    public boolean cancel(String requestId, String reason) {
        CancellationToken token = tokens.get(requestId);
        if (token == null) {
            return false;
        }
        boolean changed = token.cancel(reason);
        if (changed) {
            log.info("[Cancellation] requestId={} 取消成功, reason={}", requestId, reason);
        }
        return changed;
    }

    /** 请求结束时务必调用，防止内存泄漏 */
    public void remove(String requestId) {
        tokens.remove(requestId);
    }

    public int active() {
        return tokens.size();
    }
}
