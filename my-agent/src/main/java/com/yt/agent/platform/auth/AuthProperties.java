package com.yt.agent.platform.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证配置。
 *
 * <pre>
 * agent:
 *   auth:
 *     secret: ${AGENT_JWT_SECRET:}   # HS256 密钥，生产必须通过环境变量注入（>= 32 字节）
 *     ttl-minutes: 120               # token 有效期
 *     default-username: admin        # 引导账号（首次启动自动创建，生产建议关闭）
 *     default-password: ${AGENT_DEFAULT_PASSWORD:agent123}
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "agent.auth")
public class AuthProperties {

    /** JWT 签名密钥（HS256）。空则启动时生成临时随机密钥（重启后所有 token 失效） */
    private String secret = "";

    /** token 有效期（分钟） */
    private int ttlMinutes = 120;

    /** 引导账号用户名 */
    private String defaultUsername = "admin";

    /** 引导账号密码 */
    private String defaultPassword = "agent123";

    /** 是否允许开放注册（生产建议 false，用户由管理员/SSO 创建） */
    private boolean registrationEnabled = true;
}
