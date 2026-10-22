package com.yt.agent.platform.persistence;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话持久化配置。
 *
 * <pre>
 * agent:
 *   persistence:
 *     enabled: true
 *     url: jdbc:h2:file:./data/agent;AUTO_SERVER=TRUE   # 生产换 jdbc:postgresql://...
 *     username: sa
 *     password:
 *     driver-class-name: org.h2.Driver
 *     history-turns: 20        # 每次对话回放多少条历史（上下文预算的一部分）
 *     max-turns-per-session: 200
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "agent.persistence")
public class PersistenceProperties {

    /** 是否启用持久化；关闭则退化为内存实现 */
    private boolean enabled = true;

    private String url = "jdbc:h2:file:./data/agent;AUTO_SERVER=TRUE";

    private String username = "sa";

    private String password = "";

    private String driverClassName = "org.h2.Driver";

    /** 表名前缀，多环境共库时用于隔离 */
    private String tablePrefix = "agent_";

    /** 单会话最多保留的轮数 */
    private int maxTurnsPerSession = 200;

    /** 内存实现下最多保留的会话数 */
    private int maxSessions = 500;

    /**
     * 每次请求回放的历史轮数。
     * 注意这是"输入预算"：历史太长会挤压本次任务的推理空间，太小则丢失多轮指代能力。
     * 20 条 ≈ 10 轮问答，配合 ContextCompressor 的压缩阈值使用。
     */
    private int historyTurns = 20;
}
