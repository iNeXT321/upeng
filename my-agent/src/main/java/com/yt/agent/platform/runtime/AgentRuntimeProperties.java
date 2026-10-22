package com.yt.agent.platform.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行时配置。
 *
 * <pre>
 * agent:
 *   runtime:
 *     request-timeout-ms: 900000     # 单次任务 wall-clock 预算（15 分钟）
 *     max-concurrent-requests: 64    # 全局并发上限（信号量）
 *     acquire-timeout-ms: 500        # 排队等待上限，超时直接拒绝
 *     sse-timeout-ms: 1800000        # SSE 连接存活上限
 *     heartbeat-interval-ms: 15000
 * </pre>
 *
 * <p>为什么要有并发上限：虚拟线程让"线程数"不再是瓶颈，但模型侧的并发配额、
 * 数据库连接数、下游工具服务都是有硬上限的。没有限流时，突发流量会把下游打挂，
 * 表现为"所有人都变慢"，而不是"部分人失败"。快速失败 + 明确提示是更好的降级方式。</p>
 */
@Data
@ConfigurationProperties(prefix = "agent.runtime")
public class AgentRuntimeProperties {

    /** 单次任务的总体时间预算 */
    private long requestTimeoutMs = 15 * 60 * 1000L;

    /** 全局并发请求上限 */
    private int maxConcurrentRequests = 64;

    /** 获取并发许可的等待时间，超时即拒绝（避免请求堆积） */
    private long acquireTimeoutMs = 500L;

    /** SSE 连接最长存活时间 */
    private long sseTimeoutMs = 30 * 60 * 1000L;

    /** 心跳间隔 */
    private long heartbeatIntervalMs = 15_000L;

    /**
     * 客户端断开后的宽限期（毫秒）：期内重连则继续执行，超时无人认领才取消 Agent。
     * 网络抖动 ≠ 用户放弃，立刻取消会让断线重连变成"重新计费"。
     */
    private long disconnectGraceMs = 30_000L;
}
