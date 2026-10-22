package com.yt.agent.utils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 线程工具。
 *
 * <p>原实现是 {@code new ThreadPoolExecutor(50, 1000, 60s, new SynchronousQueue<>(), 空拒绝策略)}：
 * 队列容量为 0、最大线程 1000、拒绝处理器什么都不做——并发一高，任务被<b>静默丢弃</b>，
 * 表现为"请求无响应且没有任何错误日志"。这是最难排查的一类线上问题。</p>
 *
 * <p>改为虚拟线程执行器后，线程数量不再是瓶颈（Agent 全部是阻塞 I/O），
 * 也不再需要"用 SynchronousQueue 硬抗 + 空拒绝"这种反模式。
 * 真正的并发上限交给 AgentRuntime 的信号量做显式限流，超出时应快速失败并给出明确错误。</p>
 */
public class ThreadUtil {

    private static volatile ExecutorService executor;

    private ThreadUtil() {
    }

    /** 兼容旧调用；虚拟线程模式下 poolSize 无意义，保留签名避免大范围改动 */
    public static synchronized void initPool(int poolSize) {
        if (executor == null) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    private static ExecutorService executor() {
        ExecutorService current = executor;
        if (current == null) {
            synchronized (ThreadUtil.class) {
                current = executor;
                if (current == null) {
                    current = Executors.newVirtualThreadPerTaskExecutor();
                    executor = current;
                }
            }
        }
        return current;
    }

    public static void execute(Runnable runnable) {
        executor().execute(runnable);
    }

    public static CountDownLatch getCountDownLatch(int count) {
        return new CountDownLatch(count);
    }

    /**
     * 带超时的等待。
     *
     * @return true 表示所有任务在超时前完成
     */
    public static boolean await(CountDownLatch latch, long timeoutMs) {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 兼容旧签名，但内部加默认上限（10 分钟）：无参 await 会永久阻塞，
     * 是"请求线程泄漏"的典型成因，不允许再出现。
     */
    public static void await(CountDownLatch latch) {
        await(latch, TimeUnit.MINUTES.toMillis(10));
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
