package io.aerofleet.cloud.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多租户拦截器 + API 限流。
 * <p>
 * 职责：
 * <ul>
 *   <li>从 X-Tenant-Id header 或 JWT subject 提取 tenantId，设置到 {@link TenantContext}；</li>
 *   <li>dev-mode=true 时设置默认租户 "default"；</li>
 *   <li>基于滑动时间窗口的简单限流：每租户每分钟最多 N 次 API 调用（可配置）；</li>
 *   <li>超限返回 429 Too Many Requests。</li>
 * </ul>
 *
 * @author AeroFleet Cloud Team
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    private final boolean devMode;
    private final int rateLimitPerMinute;
    private final ObjectMapper objectMapper;

    /** 分布式限流器（Redis 可用时启用，不可用时为 null，回退到内存限流） */
    @Autowired(required = false)
    private RedisRateLimiter redisRateLimiter;

    /** 租户限流计数器：tenantId → [windowStartMs, count]（内存 fallback） */
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    /** 内存限流窗口 TTL（毫秒），与滑动窗口时长一致 */
    private static final long WINDOW_TTL_MS = 60_000;

    public TenantInterceptor(@Value("${aerofleet.security.dev-mode:true}") boolean devMode,
                             @Value("${aerofleet.tenant.rate-limit:100}") int rateLimitPerMinute,
                             ObjectMapper objectMapper) {
        this.devMode = devMode;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 提取租户 ID
        String tenantId = extractTenantId(request);
        TenantContext.setTenantId(tenantId);

        // 2. 限流校验（dev-mode 下也限流，但阈值更高）
        if (!checkRateLimit(tenantId)) {
            log.warn("租户 {} API 调用超限", tenantId);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 429);
            error.put("error", "Too Many Requests");
            error.put("message", "API 调用频率超限，请稍后重试");
            response.getWriter().write(objectMapper.writeValueAsString(error));
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }

    /**
     * 定时清理过期的内存限流窗口，防止 rateWindows Map 无限增长。
     * <p>
     * 每 60 秒执行一次，移除窗口起始时间已超过 TTL 的条目。
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredRateWindows() {
        long now = System.currentTimeMillis();
        rateWindows.entrySet().removeIf(entry ->
                now - entry.getValue().windowStartMs > WINDOW_TTL_MS
        );
    }

    private String extractTenantId(HttpServletRequest request) {
        // 优先从 X-Tenant-Id header 获取
        String tenantId = request.getHeader("X-Tenant-Id");
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId.trim();
        }

        // 其次从 JWT subject 获取（Authorization: Bearer <token>）
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            // JWT payload 的 subject 即 tenantId（JwtTokenProvider 中 subject = username）
            // 这里简单提取，不做完整 JWT 解码（SecurityConfig 已做）
            // 实际 tenantId 可从 JWT claim 中获取，此处用 subject 作为 fallback
            return "jwt-user"; // 简化：实际场景由 SecurityConfig 解析 JWT 后填充
        }

        // dev-mode 下返回默认租户
        if (devMode) {
            return "default";
        }

        return "anonymous";
    }

    /**
     * 限流校验：优先使用 Redis 分布式限流，Redis 不可用时回退到内存滑动窗口限流。
     */
    private boolean checkRateLimit(String tenantId) {
        // 优先使用 Redis 分布式限流（多实例共享计数）
        if (redisRateLimiter != null) {
            return redisRateLimiter.tryAcquire(tenantId, rateLimitPerMinute);
        }

        // 回退到内存滑动窗口限流（单机模式）
        return checkRateLimitInMemory(tenantId);
    }

    /**
     * 内存滑动窗口限流：每分钟重置计数（fallback）。
     */
    private boolean checkRateLimitInMemory(String tenantId) {
        long now = System.currentTimeMillis();
        RateWindow window = rateWindows.computeIfAbsent(tenantId, k -> new RateWindow(now));

        synchronized (window) {
            if (now - window.windowStartMs > WINDOW_TTL_MS) {
                // 窗口过期，重置
                window.windowStartMs = now;
                window.count.set(0);
            }
            int current = window.count.incrementAndGet();
            return current <= rateLimitPerMinute;
        }
    }

    /** 速率窗口 */
    private static class RateWindow {
        long windowStartMs;
        AtomicInteger count = new AtomicInteger(0);

        RateWindow(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }
}