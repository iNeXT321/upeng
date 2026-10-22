package com.yt.agent.platform.auth;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版用户存储（默认实现）。
 * 生产切换数据库：实现 UserStore 接口并注册为 Bean 即可覆盖本实现。
 */
@Slf4j
@Component
public class InMemoryUserStore implements UserStore {

    private final Map<String, UserAccount> users = new ConcurrentHashMap<>();
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    public InMemoryUserStore(PasswordEncoder passwordEncoder, AuthProperties authProperties) {
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
    }

    /** 启动时播种引导账号，保证系统永远存在一个可登录的管理员 */
    @PostConstruct
    void seedDefaultUser() {
        String username = authProperties.getDefaultUsername();
        if (username != null && !username.isBlank() && !users.containsKey(username)) {
            users.put(username, UserAccount.builder()
                    .username(username)
                    .passwordHash(passwordEncoder.encode(authProperties.getDefaultPassword()))
                    .displayName("管理员")
                    .roles(Set.of("USER", "ADMIN"))
                    .build());
            log.info("[Auth] 引导账号已创建: {}（生产环境请立即修改默认密码）", username);
        }
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(users.get(username));
    }

    @Override
    public boolean exists(String username) {
        return users.containsKey(username);
    }

    @Override
    public void save(UserAccount account) {
        // 入库前统一做哈希，防御未来调用方误传明文
        if (account.getPasswordHash() != null && !account.getPasswordHash().startsWith("$2")) {
            account.setPasswordHash(passwordEncoder.encode(account.getPasswordHash()));
        }
        users.put(account.getUsername(), account);
        log.info("[Auth] 用户注册: {} roles={}", account.getUsername(), account.getRoles());
    }
}
