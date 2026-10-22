package com.yt.agent.platform.llm;

/**
 * 模型不可用（没有任何可路由的 ChatModel）。
 *
 * <p>这是一个"配置类错误"而非"运行时抖动"，必须是显式、可读的异常，
 * 而不是让业务侧收到一个含义不明的 NPE——线上排查时，"没配 key"和"模型挂了"是同一种症状，
 * 但处理方式完全不同。</p>
 */
public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
