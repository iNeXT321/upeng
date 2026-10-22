package com.yt.agent.platform.llm;

/**
 * 模型调用异常。
 *
 * <p>显式区分 {@code retryable}：限流(429)、超时、连接重置可以重试；
 * 鉴权失败(401)、参数非法(400)、模型不存在则不应重试——盲目重试只会放大故障并烧钱。
 * 这个标记由 {@link MeteredChatModel} 根据错误信息做保守判定。</p>
 */
public class ModelCallException extends RuntimeException {

    private final boolean retryable;
    private final String provider;
    private final String model;

    public ModelCallException(String message, boolean retryable, String provider, String model, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.provider = provider;
        this.model = model;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }
}
