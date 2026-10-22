package com.yt.agent.platform.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 认证入口：登录 / 注册 / 当前用户信息。
 * 密码错误与用户不存在返回同一文案，避免账号枚举攻击。
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserStore userStore;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        if (username.isEmpty() || password.isEmpty()) {
            return badRequest("用户名和密码不能为空");
        }
        var account = userStore.findByUsername(username);
        if (account.isEmpty() || !passwordEncoder.matches(password, account.get().getPasswordHash())) {
            log.warn("[Auth] 登录失败: {}", username);
            return ResponseEntity.status(401)
                    .body(Map.of("code", "BAD_CREDENTIALS", "message", "用户名或密码错误"));
        }
        String token = jwtService.issue(username, account.get().getDisplayName());
        log.info("[Auth] 登录成功: {}", username);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "tokenType", "Bearer",
                "expiresIn", jwtService.ttlMs() / 1000,
                "username", username,
                "displayName", account.get().getDisplayName() == null ? username : account.get().getDisplayName()
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        if (!authProperties.isRegistrationEnabled()) {
            return ResponseEntity.status(403)
                    .body(Map.of("code", "REGISTRATION_DISABLED", "message", "当前已关闭开放注册"));
        }
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        if (username.length() < 3 || password.length() < 6) {
            return badRequest("用户名至少3位，密码至少6位");
        }
        if (userStore.exists(username)) {
            return ResponseEntity.status(409)
                    .body(Map.of("code", "USER_EXISTS", "message", "用户名已存在"));
        }
        userStore.save(UserAccount.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password)) // 只存哈希
                .displayName(body.getOrDefault("displayName", username))
                .roles(java.util.Set.of("USER"))
                .build());
        return ResponseEntity.ok(Map.of("message", "注册成功，请登录", "time", Instant.now().toString()));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(java.security.Principal principal) {
        return ResponseEntity.ok(Map.of(
                "username", principal.getName(),
                "serverTime", Instant.now().toString()
        ));
    }

    private ResponseEntity<Map<String, String>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", msg));
    }
}
