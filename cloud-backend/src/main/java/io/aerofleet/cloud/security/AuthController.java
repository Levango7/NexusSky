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

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证端点：登录与令牌刷新。
 * <p>
 * 使用内存用户存储（从配置 {@code aerofleet.security.users} 加载），
 * 格式为 {@code username:password,username2:password2}，默认 {@code admin:admin}。
 * 不接外部 IDP，适用于脚手架阶段和中小规模部署。
 * <p>
 * 登录频率限制：每 IP 每分钟最多 {@value #MAX_ATTEMPTS_PER_MINUTE} 次，
 * 超过返回 429 Too Many Requests，防止暴力破解。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 每 IP 每分钟最大登录尝试次数。 */
    static final int MAX_ATTEMPTS_PER_MINUTE = 10;
    private static final long WINDOW_MS = 60_000L;
    /** 限制不同 IP 的桶总数，避免窗口内大量新 IP 耗尽内存。 */
    private static final int MAX_RATE_BUCKETS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final Map<String, String> users; // username -> encoded password
    private final long expirySeconds;
    /** 默认只使用直连地址；仅在可信代理覆盖转发头时允许开启。 */
    @Value("${aerofleet.security.trust-forwarded-for:false}")
    private boolean trustForwardedFor;
    /** 登录频率限制：IP -> 窗口内尝试时间戳列表。 */
    private final ConcurrentHashMap<String, RateBucket> loginRateBuckets = new ConcurrentHashMap<>();
    /** 由 isRateLimited 的同步锁保护。 */
    private long lastRateBucketCleanup;

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
     * <p>
     * 频率限制：每 IP 每分钟最多 {@value #MAX_ATTEMPTS_PER_MINUTE} 次，
     * 超过返回 429 Too Many Requests。
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        // 频率限制检查
        String clientIp = extractClientIp(request);
        if (isRateLimited(clientIp)) {
            log.warn("登录频率超限: ip={}", clientIp);
            return errorResponse(HttpStatus.TOO_MANY_REQUESTS,
                    "too many login attempts, please try again later");
        }

        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "username and password are required");
        }

        String encodedPassword = users.get(username);
        if (encodedPassword == null || !passwordEncoder.matches(password, encodedPassword)) {
            log.warn("登录失败: username={} ip={}", username, clientIp);
            return errorResponse(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }

        String token = tokenProvider.generateToken(username, Duration.ofSeconds(expirySeconds));
        log.info("用户登录成功: username={} ip={}", username, clientIp);

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

    // =====================================================================
    // 登录频率限制
    // =====================================================================

    /** 提取客户端 IP；默认忽略可由客户端伪造的转发头。 */
    private String extractClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String clientIp = xff.split(",", 2)[0].trim();
                if (!clientIp.isEmpty()) {
                    return clientIp;
                }
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;
    }

    /** 检查并记录本次尝试；同步保证容量检查与新增桶不会并发突破上限。 */
    private synchronized boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        if (now - lastRateBucketCleanup >= WINDOW_MS) {
            // 同时回收不再访问的 IP；只清理当前 IP 无法解决历史桶持续累积。
            loginRateBuckets.keySet().forEach(key -> removeExpiredRateBucket(key, now));
            lastRateBucketCleanup = now;
        }
        removeExpiredRateBucket(ip, now);
        if (!loginRateBuckets.containsKey(ip) && loginRateBuckets.size() >= MAX_RATE_BUCKETS) {
            return true;
        }
        boolean[] rateLimited = {false};
        loginRateBuckets.compute(ip, (key, bucket) -> {
            RateBucket current = bucket == null ? new RateBucket() : bucket;
            if (current.timestamps.size() >= MAX_ATTEMPTS_PER_MINUTE) {
                rateLimited[0] = true;
            } else {
                current.timestamps.add(now);
            }
            return current;
        });
        return rateLimited[0];
    }

    /** 原子清理并移除空桶，避免移除操作误删并发请求正在使用的桶。 */
    private void removeExpiredRateBucket(String ip, long now) {
        loginRateBuckets.computeIfPresent(ip, (key, bucket) -> {
            bucket.timestamps.removeIf(ts -> now - ts > WINDOW_MS);
            return bucket.timestamps.isEmpty() ? null : bucket;
        });
    }

    /** 频率限制窗口桶：存储窗口内的尝试时间戳。 */
    private static final class RateBucket {
        final java.util.ArrayList<Long> timestamps = new java.util.ArrayList<>();
    }
}