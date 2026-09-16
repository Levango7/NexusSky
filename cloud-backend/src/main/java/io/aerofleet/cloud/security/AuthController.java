package io.aerofleet.cloud.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证端点：登录与令牌刷新。
 * <p>
 * 使用内存用户存储（从配置 {@code aerofleet.security.users} 加载），
 * 格式为 {@code username:password,username2:password2}，默认 {@code admin:admin}。
 * 不接外部 IDP，适用于脚手架阶段和中小规模部署。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final Map<String, String> users; // username -> encoded password
    private final long expirySeconds;

    public AuthController(JwtTokenProvider tokenProvider,
                          PasswordEncoder passwordEncoder,
                          @Value("${aerofleet.security.users:admin:admin}") String usersConfig,
                          @Value("${aerofleet.security.jwt-expiry:3600}") long expirySeconds) {
        this.tokenProvider = tokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.expirySeconds = expirySeconds;
        this.users = parseUsers(usersConfig);
    }

    /**
     * 用户登录，返回 JWT 令牌。
     * <p>
     * POST /api/auth/login {username, password} → {token, expiresIn, username}
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "username and password are required");
        }

        String encodedPassword = users.get(username);
        if (encodedPassword == null || !passwordEncoder.matches(password, encodedPassword)) {
            log.warn("登录失败: username={}", username);
            return errorResponse(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }

        String token = tokenProvider.generateToken(username, Duration.ofSeconds(expirySeconds));
        log.info("用户登录成功: username={}", username);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("expiresIn", expirySeconds);
        resp.put("username", username);
        return ResponseEntity.ok(resp);
    }

    /**
     * 刷新令牌。
     * <p>
     * POST /api/auth/refresh (Authorization: Bearer <token>) → {token, expiresIn}
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        if (!tokenProvider.validateToken(token)) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "invalid or expired token");
        }

        String username = tokenProvider.getUsername(token);
        if (username == null) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "unable to extract username from token");
        }

        String newToken = tokenProvider.generateToken(username, Duration.ofSeconds(expirySeconds));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", newToken);
        resp.put("expiresIn", expirySeconds);
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, String> parseUsers(String usersConfig) {
        Map<String, String> result = new LinkedHashMap<>();
        if (usersConfig == null || usersConfig.isBlank()) {
            return result;
        }
        for (String entry : usersConfig.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length == 2) {
                result.put(parts[0].trim(), passwordEncoder.encode(parts[1].trim()));
            }
        }
        return result;
    }
}