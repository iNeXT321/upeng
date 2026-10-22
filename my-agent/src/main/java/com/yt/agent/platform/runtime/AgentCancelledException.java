package com.yt.agent.platform.runtime;

/**
 * 请求被取消（用户主动停止 / SSE 断开 / 服务优雅停机）。
 *
 * <p>用独立异常类型而不是 {@code InterruptedException}：
 * 上层需要区分"用户取消"（正常结束，记录 info 日志、返回取消事件）与
 * "系统故障"（error 日志、告警）。混在一起会导致监控噪音与误判。</p>
 */
public class AgentCancelledException extends RuntimeException {

    public AgentCancelledException(String reason) {
        super("请求已取消: " + reason);
    }
}
