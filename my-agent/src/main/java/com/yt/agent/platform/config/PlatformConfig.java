package com.yt.agent.platform.config;

import com.yt.agent.platform.tool.ToolPolicyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 平台基础设施装配。
 *
 * <p>线程模型说明（这是原实现最大的稳定性隐患之一）：原代码用
 * {@code new ThreadPoolExecutor(50, 1000, SynchronousQueue, 空拒绝策略)}，
 * 队列容量为 0、拒绝策略为空实现——一旦并发超过上限，任务会被<b>静默丢弃</b>，
 * 表现为"请求发出去了但永远没有响应"，且没有任何日志。这里统一改为虚拟线程执行器：
 * Java 21 下虚拟线程的创建成本极低，天然适配 Agent 这种"大量阻塞 I/O（模型/数据库/HTTP）"的场景，
 * 不需要靠堆线程数硬抗。</p>
 *
 * <p>注意：这是<b>执行器</b>不是<b>限流器</b>。真正的并发保护由 AgentRuntime 的
 * 信号量（agent.runtime.max-concurrent-requests）负责，两者职责分离。</p>
 */
@Configuration
@EnableConfigurationProperties({ToolPolicyProperties.class, com.yt.agent.platform.runtime.AgentRuntimeProperties.class})
public class PlatformConfig {

    @Bean(name = "agentWorkerExecutor", destroyMethod = "close")
    public ExecutorService agentWorkerExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
