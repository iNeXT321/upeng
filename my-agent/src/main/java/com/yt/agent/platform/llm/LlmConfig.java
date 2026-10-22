package com.yt.agent.platform.llm;

import com.yt.agent.platform.metrics.AgentMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;

/**
 * 模型网关装配。
 *
 * <p>只暴露 {@link LlmGateway} 一个 Bean，不暴露 ChatModel Bean——
 * 否则会与 Spring AI 各 starter 的 {@code @ConditionalOnMissingBean(ChatModel.class)} 冲突，
 * 导致 provider 自动配置被跳过。这是接入多模型时很容易踩的坑。</p>
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public LlmGateway llmGateway(ApplicationContext applicationContext,
                                 LlmProperties properties,
                                 AgentMetrics metrics,
                                 @Qualifier("agentWorkerExecutor") ExecutorService executor) {
        return new RoutingLlmGateway(applicationContext, properties, metrics, executor);
    }
}
