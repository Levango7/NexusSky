package io.aerofleet.cloud.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.security.TenantContext;
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
 * API 限流拦截器（按租户 / 客户端 IP 滑动窗口）。
 * <p>
 * 职责：
 * <ul>
 *   <li>限流 key 从<b>已认证</b>的租户上下文解析（{@link TenantContext#getEffectiveTenantId()}，
 *       由 security 包的 TenantFilter / ApiKeyFilter 在更早的过滤器链阶段写入，本类只读不写）：
 *       有真实租户归属 → 按租户分桶（同一租户的所有凭证共享每分钟 N 次额度）；
 *       其余（未认证、全局管理员、NO_ACCESS、dev-mode 跳过租户上下文）→ 按客户端 IP 分桶；</li>
 *   <li>Redis 可用时经 {@link RedisRateLimiter} 分布式限流，不可用时回退内存滑动窗口；</li>
 *   <li>超限返回 429 Too Many Requests。</li>
 * </ul>
 * <p>
 * 本类<b>不负责</b>租户数据隔离：隔离统一由 security 包的 {@link TenantContext}
 * 三态租户域（TenantFilter / ApiKeyFilter 写入，业务层消费）承担。
 * 历史上本类曾从客户端可控的 {@code X-Tenant-Id} header 提取租户并写入一个
 * 无人消费的 String ThreadLocal——既不参与隔离，又允许轮换 header 无限获取
 * 新限流桶绕过限流，该路径已移除。
 *
 * @author AeroFleet Cloud Team
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    private final int rateLimitPerMinute;
    private final ObjectMapper objectMapper;

    /** 分布式限流器（Redis 可用时启用，不可用时为 null，回退到内存限流） */
    @Autowired(required = false)
    private RedisRateLimiter redisRateLimiter;

    /** 限流计数器：限流 key → [windowStartMs, count]（内存 fallback） */
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    /** 内存限流窗口 TTL（毫秒），与滑动窗口时长一致 */
    private static final long WINDOW_TTL_MS = 60_000;

    public TenantInterceptor(@Value("${aerofleet.tenant.rate-limit:100}") int rateLimitPerMinute,
                             ObjectMapper objectMapper) {
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 限流校验（key = 真实租户桶或客户端 IP 桶）
        String key = resolveRateLimitKey(request);
        if (!checkRateLimit(key)) {
            log.warn("限流 key [{}] API 调用超限", key);
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

    /**
     * 限流 key：真实租户归属 → 租户桶；否则 → 客户端 IP 桶。
     * <p>
     * 拦截器在过滤器链之后执行，TenantFilter / ApiKeyFilter 写入的租户上下文此时已就绪，
     * 本方法只读。null（全局管理员 / 未认证 / dev-mode）与 {@link TenantContext#NO_ACCESS}
     * 都不是「真实租户」，一律落 IP 桶；remoteAddr 缺失时兜底为 "ip:unknown"。
     */
    private String resolveRateLimitKey(HttpServletRequest request) {
        Integer tenantId = TenantContext.getEffectiveTenantId();
        if (tenantId != null && !TenantContext.NO_ACCESS.equals(tenantId)) {
            return "tenant:" + tenantId;
        }
        String ip = request.getRemoteAddr();
        if (ip == null || ip.isBlank()) {
            return "ip:unknown";
        }
        return "ip:" + ip;
    }

    /**
     * 限流校验：优先使用 Redis 分布式限流，Redis 不可用时回退到内存滑动窗口限流。
     */
    private boolean checkRateLimit(String key) {
        // 优先使用 Redis 分布式限流（多实例共享计数）
        if (redisRateLimiter != null) {
            return redisRateLimiter.tryAcquire(key, rateLimitPerMinute);
        }

        // 回退到内存滑动窗口限流（单机模式）
        return checkRateLimitInMemory(key);
    }

    /**
     * 内存滑动窗口限流：每分钟重置计数（fallback）。
     */
    private boolean checkRateLimitInMemory(String key) {
        long now = System.currentTimeMillis();
        RateWindow window = rateWindows.computeIfAbsent(key, k -> new RateWindow(now));

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
