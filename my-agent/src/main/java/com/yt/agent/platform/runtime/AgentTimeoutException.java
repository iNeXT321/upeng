package com.yt.agent.platform.runtime;

/**
 * 请求超过整体预算（wall-clock deadline）。
 *
 * <p>与"单次模型调用超时"不同：模型每次都能在 60s 内返回，但 40 步累计可能跑 40 分钟。
 * 用户体验与成本都需要一个"整轮任务预算"——到点必须收尾并返回已有结果，
 * 而不是无限继续。</p>
 */
public class AgentTimeoutException extends RuntimeException {

    public AgentTimeoutException(long budgetMs) {
        super("任务超过时间预算 " + budgetMs + "ms，已终止");
    }
}
