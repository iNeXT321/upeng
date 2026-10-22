package com.yt.agent.platform.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 用户账号（领域对象）。
 * 密码只存 BCrypt 哈希，任何代码路径都不允许出现明文密码的持久化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAccount {

    private String username;

    /** BCrypt 哈希 */
    private String passwordHash;

    private String displayName;

    /** 角色集合，如 USER / ADMIN */
    @Builder.Default
    private Set<String> roles = Set.of("USER");
}
