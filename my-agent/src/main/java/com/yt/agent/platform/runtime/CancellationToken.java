package com.yt.agent.platform.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消令牌 —— 让"停止生成"真正生效。
 *
 * <p>这是 Agent 与普通 CRUD 接口最大的体验差异之一：一次任务可能跑几分钟、烧几万 token。
 * 如果用户点了"停止"或直接关掉页面，而服务端还在继续跑，就是纯粹的资源浪费与成本泄漏。
 * 原实现没有任何取消机制：SSE 断开只是断了输出通道，Agent 仍会把 40 步全部跑完。</p>
 *
 * <p>实现选择 {@code AtomicBoolean} + 协作式检查，而不是 {@code Thread.interrupt()}：
 * 中断会打断 HTTP 客户端/数据库驱动的内部状态，容易留下半开连接；
 * 协作式检查在"每一步之间"退出，语义清晰、资源释放可控。</p>
 */
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final long createdAt = System.currentTimeMillis();
    private volatile String reason = "";

    /** @return 是否是本次调用真正触发了取消（幂等） */
    public boolean cancel(String reason) {
        this.reason = reason == null ? "cancelled" : reason;
        return cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String reason() {
        return reason;
    }

    public long createdAt() {
        return createdAt;
    }

    /** 在 Agent 主循环的每个检查点调用；已取消则抛出，交由上层统一收尾 */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new AgentCancelledException(reason);
        }
    }
}
