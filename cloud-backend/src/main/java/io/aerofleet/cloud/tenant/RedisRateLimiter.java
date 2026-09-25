package io.aerofleet.cloud.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

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

    /**
     * Lua 脚本：原子化 INCR + EXPIRE，避免竞态条件导致 key 永不过期。
     * 当 INCR 返回 1（首次递增）时设置 EXPIRE，整个过程在 Redis 单线程中原子执行。
     */
    private static final String RATE_LIMIT_SCRIPT =
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then " +
            "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
            "end " +
            "return current";

    private static final DefaultRedisScript<Long> RATE_LIMIT_REDIS_SCRIPT =
            new DefaultRedisScript<>(RATE_LIMIT_SCRIPT, Long.class);

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
            Long current = redisTemplate.execute(
                    RATE_LIMIT_REDIS_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(WINDOW_TTL.getSeconds())
            );

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