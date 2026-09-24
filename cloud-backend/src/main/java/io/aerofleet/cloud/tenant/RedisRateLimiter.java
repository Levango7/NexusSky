package io.aerofleet.cloud.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 基于 Redis 的分布式限流器。
 * <p>
 * 使用 Redis INCR + EXPIRE 实现固定窗口限流（每分钟 N 次）。
 * Key 格式：{@code rate_limit:{tenantId}:{minuteBucket}}，其中 minuteBucket 为当前分钟的时间戳。
 * <p>
 * 当 Redis 不可用（StringRedisTemplate 未注入或操作异常）时，返回 true（允许通过），
 * 由调用方回退到内存限流。
 *
 * @author AeroFleet Cloud Team
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String KEY_PREFIX = "rate_limit:";
    private static final Duration WINDOW_TTL = Duration.ofMinutes(1);

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${aerofleet.tenant.rate-limit:100}")
    private int defaultLimitPerMinute;

    /**
     * 尝试获取限流许可。
     * <p>
     * 使用 Redis INCR 原子递增当前分钟窗口的计数器，首次递增时设置 EXPIRE。
     * 当 Redis 不可用时返回 true（允许通过），由调用方回退到内存限流。
     *
     * @param tenantId       租户 ID
     * @param limitPerMinute 每分钟允许的最大请求数
     * @return true 表示允许通过，false 表示已超限
     */
    public boolean tryAcquire(String tenantId, int limitPerMinute) {
        if (redisTemplate == null) {
            return true;
        }

        try {
            String key = buildKey(tenantId);
            Long current = redisTemplate.opsForValue().increment(key);

            if (current != null && current == 1L) {
                // 首次请求，设置过期时间（1 分钟后自动清理）
                redisTemplate.expire(key, WINDOW_TTL);
            }

            return current != null && current <= limitPerMinute;
        } catch (Exception e) {
            log.warn("Redis 限流操作异常，允许通过（将回退到内存限流）：{}", e.getMessage());
            return true;
        }
    }

    /**
     * 使用默认限流阈值尝试获取许可。
     *
     * @param tenantId 租户 ID
     * @return true 表示允许通过，false 表示已超限
     */
    public boolean tryAcquire(String tenantId) {
        return tryAcquire(tenantId, defaultLimitPerMinute);
    }

    /**
     * 构建限流 Key：{@code rate_limit:{tenantId}:{minuteBucket}}。
     * <p>
     * minuteBucket 为当前分钟的时间戳（epoch 秒 / 60），确保同一分钟内的请求落在同一窗口。
     *
     * @param tenantId 租户 ID
     * @return Redis Key
     */
    private String buildKey(String tenantId) {
        long minuteBucket = Instant.now().getEpochSecond() / 60;
        return KEY_PREFIX + tenantId + ":" + minuteBucket;
    }
}