package com.yt.agent.platform.rate;

/**
 * 触发限流（HTTP 语义 429 Too Many Requests）。
 */
public class RateLimitedException extends RuntimeException {
    public RateLimitedException(String message) {
        super(message);
    }
}
