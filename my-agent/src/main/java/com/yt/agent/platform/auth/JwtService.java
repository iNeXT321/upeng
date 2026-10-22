package com.yt.agent.platform.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与校验（HS256）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>密钥 >= 32 字节（HS256 安全下限），生产从环境变量注入，绝不落仓库；</li>
 *   <li>未配置密钥时启动期生成随机临时密钥——重启即全员掉线，比固定弱密钥安全；</li>
 *   <li>无状态：服务端不存 session，水平扩容只需共享同一个 secret。</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtService {

    private final SecretKey key;
    private final long ttlMs;

    public JwtService(AuthProperties props) {
        String secret = props.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            secret = Base64.getEncoder().encodeToString(random);
            log.warn("[Auth] 未配置 agent.auth.secret（或长度<32字节），使用临时随机密钥，重启后所有 token 失效");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMs = props.getTtlMinutes() * 60_000L;
    }

    public String issue(String username, String displayName) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("name", displayName == null ? username : displayName)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMs))
                .signWith(key)
                .compact();
    }

    public long ttlMs() {
        return ttlMs;
    }

    /** 校验并解析；过期/伪造/格式错误统一返回 empty，调用方按未认证处理 */
    public Optional<Claims> verify(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("[Auth] token 校验失败: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
