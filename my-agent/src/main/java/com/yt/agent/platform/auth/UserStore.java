package com.yt.agent.platform.auth;

import java.util.Optional;

/**
 * 用户存储抽象。
 *
 * <p>默认实现是 {@link InMemoryUserStore}（重启即失效，适合演示/开发）。
 * 接生产数据库时实现 {@code JdbcUserStore}（users 表 + BCrypt 哈希列），
 * 注册为 Bean 覆盖默认实现即可，认证链路其他代码零改动。</p>
 */
public interface UserStore {

    Optional<UserAccount> findByUsername(String username);

    boolean exists(String username);

    void save(UserAccount account);
}
