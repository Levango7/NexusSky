package io.aerofleet.cloud.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
@RequestMapping("/api/v1/auth")
@RequireRole(Role.OBSERVER)
public class AuthController {

    /** 每 IP 每分钟最大登录尝试次数。 */
    static final int MAX_ATTEMPTS_PER_MINUTE = 10;
    private static final long WINDOW_MS = 60_000L;
    /** 限制不同 IP 的桶总数，避免窗口内大量新 IP 耗尽内存。 */
    private static final int MAX_RATE_BUCKETS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final Map<String, MemoryUser> users; // username -> 编码口令 + 角色
    private final long expirySeconds;
    private final StreamTokenService streamTokenService;
    /** 可选注入：有 Spring 上下文时使用数据库查询，无上下文时降级为内存模式。 */
    private final UserRepository userRepository;
    /** 默认只使用直连地址；仅在可信代理覆盖转发头时允许开启。 */
    @Value("${aerofleet.security.trust-forwarded-for:false}")
    private boolean trustForwardedFor;
    /** 登录频率限制：IP -> 窗口内尝试时间戳列表。 */
    private final ConcurrentHashMap<String, RateBucket> loginRateBuckets = new ConcurrentHashMap<>();


    public AuthController(JwtTokenProvider tokenProvider,
                          PasswordEncoder passwordEncoder,
                          @Value("${aerofleet.security.users}") String usersConfig,
                          @Value("${aerofleet.security.jwt-expiry:3600}") long expirySeconds,
                          StreamTokenService streamTokenService,
                          @Autowired(required = false) UserRepository userRepository) {
        this.tokenProvider = tokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.expirySeconds = expirySeconds;
        this.streamTokenService = streamTokenService;
        this.users = parseUsers(usersConfig);
        this.userRepository = userRepository;
        if (this.users.isEmpty() && userRepository == null) {
            throw new IllegalStateException(
                    "aerofleet.security.users is not configured and UserRepository is not available. "
                    + "Set aerofleet.security.users environment variable (format: username:password[:ROLE])");
        }
    }

    /**
     * 用户登录，返回 JWT 令牌。
     * <p>
     * POST /api/v1/auth/login {username, password} → {token, expiresIn, username}
     * <p>
     * 频率限制：每 IP 每分钟最多 {@value #MAX_ATTEMPTS_PER_MINUTE} 次，
     * 超过返回 429 Too Many Requests。
     */
    @PostMapping("/login")
    @PermitAll
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

        // 优先使用 UserRepository（数据库模式），降级到内存模式
        if (userRepository != null) {
            var userEntityOpt = userRepository.findByUsername(username);
            if (userEntityOpt.isEmpty() || !userEntityOpt.get().isEnabled()) {
                log.warn("登录失败: username={} ip={}", username, clientIp);
                return errorResponse(HttpStatus.UNAUTHORIZED, "invalid credentials");
            }
            UserEntity userEntity = userEntityOpt.get();
            if (!passwordEncoder.matches(password, userEntity.getPasswordHash())) {
                log.warn("登录失败: username={} ip={}", username, clientIp);
                return errorResponse(HttpStatus.UNAUTHORIZED, "invalid credentials");
            }
            User user = userEntity.toUser();
            String token = tokenProvider.generateToken(
                    user.getUsername(), user.getRole(), user.getTenantId(),
                    Duration.ofSeconds(expirySeconds));
            log.info("用户登录成功: username={} ip={}", username, clientIp);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("token", token);
            resp.put("expiresIn", expirySeconds);
            resp.put("username", username);
            return ResponseEntity.ok(resp);
        }

        // 内存模式降级（无 Spring 上下文或无数据库）
        MemoryUser memoryUser = users.get(username);
        if (memoryUser == null || !passwordEncoder.matches(password, memoryUser.passwordHash())) {
            log.warn("登录失败: username={} ip={}", username, clientIp);
            return errorResponse(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }

        // 内存用户同样带 role claim，否则加过 @RequireRole 的端点对内存模式一律 403
        String token = tokenProvider.generateToken(
                username, memoryUser.role(), null, Duration.ofSeconds(expirySeconds));
        log.info("用户登录成功: username={} role={} ip={}", username, memoryUser.role(), clientIp);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("expiresIn", expirySeconds);
        resp.put("username", username);
        return ResponseEntity.ok(resp);
    }

    /**
     * 刷新令牌。
     * <p>
     * POST /api/v1/auth/refresh (Authorization: Bearer <token>) → {token, expiresIn}
     */
    @PostMapping("/refresh")
    @PermitAll
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

        // 数据库模式：刷新时重新查询用户，确保 role/tenant_id 为最新值
        if (userRepository != null) {
            var userEntityOpt = userRepository.findByUsername(username);
            if (userEntityOpt.isEmpty() || !userEntityOpt.get().isEnabled()) {
                return errorResponse(HttpStatus.UNAUTHORIZED, "user no longer exists or disabled");
            }
            User user = userEntityOpt.get().toUser();
            String newToken = tokenProvider.generateToken(
                    user.getUsername(), user.getRole(), user.getTenantId(),
                    Duration.ofSeconds(expirySeconds));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("token", newToken);
            resp.put("expiresIn", expirySeconds);
            return ResponseEntity.ok(resp);
        }

        // 内存模式降级：角色取自配置解析结果；用户已从配置移除则拒绝（与 DB 模式一致）
        MemoryUser refreshed = users.get(username);
        if (refreshed == null) {
            return errorResponse(HttpStatus.UNAUTHORIZED, "user no longer exists or disabled");
        }
        String newToken = tokenProvider.generateToken(
                username, refreshed.role(), null, Duration.ofSeconds(expirySeconds));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", newToken);
        resp.put("expiresIn", expirySeconds);
        return ResponseEntity.ok(resp);
    }

    /**
     * 签发 SSE 流令牌。
     * <p>
     * POST /api/v1/auth/stream-token（需认证）→ {token, expiresIn}
     * <p>
     * 浏览器 EventSource 无法携带 Authorization 头，SSE 端点（报警流/安防事件流）
     * 在生产链上用本端点换取的短命单次用令牌经 {@code ?streamToken=} 参数认证。
     * 令牌绑定签发现场的角色与租户域（三态值原样恢复），不放大权限。
     * <p>
     * 角色取值与 {@link RoleInterceptor} 同口径（JWT role claim 优先，其次
     * API Key 记录的角色）；流令牌本身不在来源里 —— 流令牌不能再签流令牌，
     * 天然 fail-closed。全局容量满时返回 503（客户端应稍后重试）。
     */
    @PostMapping("/stream-token")
    public ResponseEntity<Map<String, Object>> issueStreamToken(HttpServletRequest request) {
        String role = RoleInterceptor.extractRoleClaim(request, tokenProvider.getDecoder());
        if (role == null) {
            role = ApiKeyContext.getRole();
        }
        if (role == null) {
            log.warn("流令牌签发被拒: 当前凭证无可解析角色");
            return errorResponse(HttpStatus.FORBIDDEN, "current credentials carry no role");
        }

        String subject = resolveCurrentSubject();
        Integer tenantScope = TenantContext.getEffectiveTenantId();
        String token = streamTokenService.issue(subject, role, tenantScope);
        if (token == null) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "stream token capacity exceeded, retry later");
        }
        log.debug("流令牌已签发: subject={}, tenantScope={}", subject, tenantScope);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("expiresIn", StreamTokenService.TTL_MILLIS / 1000);
        return ResponseEntity.ok(resp);
    }

    /** 当前认证主体名（JWT 用户名或 API Key ID），仅用于令牌容量记账与日志。 */
    private String resolveCurrentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return authentication.getName();
        }
        return "unknown";
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 解析 {@code aerofleet.security.users}，格式 {@code username:password[:ROLE]}，多项逗号分隔。
     * <p>
     * 未写 ROLE 时默认 {@link Role#OPERATOR}（可控制设备，但不能管理用户/租户/API Key）。
     * 末段只有正好匹配角色枚举时才当作角色，因此口令里含冒号仍可工作（需要显式角色时，
     * 口令请用不含角色名的写法）。
     */
    private Map<String, MemoryUser> parseUsers(String usersConfig) {
        Map<String, MemoryUser> result = new LinkedHashMap<>();
        if (usersConfig == null || usersConfig.isBlank()) {
            return result;
        }
        for (String entry : usersConfig.split(",")) {
            String trimmed = entry.trim();
            int first = trimmed.indexOf(':');
            if (first <= 0) {
                log.warn("忽略非法 aerofleet.security.users 条目（缺 username）: {}", trimmed);
                continue;
            }
            String username = trimmed.substring(0, first).trim();
            String rest = trimmed.substring(first + 1).trim();

            Role role = Role.OPERATOR;
            String password = rest;
            int last = rest.lastIndexOf(':');
            if (last > 0) {
                Role parsed = parseRoleName(rest.substring(last + 1).trim());
                if (parsed != null) {
                    role = parsed;
                    password = rest.substring(0, last).trim();
                }
            }
            result.put(username, new MemoryUser(passwordEncoder.encode(password), role));
        }
        return result;
    }

    /**
     * 把配置里的角色段解析为枚举（大小写不敏感）。
     *
     * @return 匹配的 Role，不匹配返回 null
     */
    private Role parseRoleName(String value) {
        for (Role candidate : Role.values()) {
            if (candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return null;
    }

    /** 内存用户：编码后的口令 + 角色。 */
    private record MemoryUser(String passwordHash, Role role) {
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

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        if (!loginRateBuckets.containsKey(ip) && loginRateBuckets.size() >= MAX_RATE_BUCKETS) {
            return true;
        }
        RateBucket bucket = loginRateBuckets.computeIfAbsent(ip, k -> new RateBucket());
        synchronized (bucket) {
            bucket.timestamps.removeIf(ts -> now - ts > WINDOW_MS);
            if (bucket.timestamps.size() >= MAX_ATTEMPTS_PER_MINUTE) {
                return true;
            }
            bucket.timestamps.add(now);
            return false;
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 60_000)
    public void cleanupRateBuckets() {
        long now = System.currentTimeMillis();
        loginRateBuckets.entrySet().removeIf(entry -> {
            RateBucket bucket = entry.getValue();
            synchronized (bucket) {
                bucket.timestamps.removeIf(ts -> now - ts > WINDOW_MS);
                return bucket.timestamps.isEmpty();
            }
        });
    }

    /** 频率限制窗口桶：存储窗口内的尝试时间戳。 */
    private static final class RateBucket {
        final java.util.ArrayList<Long> timestamps = new java.util.ArrayList<>();
    }
}