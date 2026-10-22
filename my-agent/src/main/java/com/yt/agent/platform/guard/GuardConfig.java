package com.yt.agent.platform.guard;

import com.yt.agent.config.AgentConfig;
import com.yt.agent.platform.metrics.AgentMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 护栏装配。
 *
 * <p>规则的顺序在这里显式声明，便于评审：长度 → 敏感信息 → 注入 → PII。
 * 新增规则只需在列表里插入一个实现，不需要改 {@link GuardChain}。</p>
 */
@Configuration
@EnableConfigurationProperties(GuardProperties.class)
public class GuardConfig {

    @Bean
    public GuardChain guardChain(GuardProperties properties, AgentConfig agentConfig, AgentMetrics metrics) {
        List<InputGuard> guards = List.of(
                new LengthGuard(properties.getMaxInputChars()),
                new SecretGuard(agentConfig.getSensitivePatterns()),
                new PromptInjectionGuard(properties.getInjectionThreshold(), properties.isBlockOnInjection()),
                new PiiMaskGuard(properties.isMaskPii(), properties.isMaskPhone())
        );
        return new GuardChain(properties, guards, metrics);
    }
}
