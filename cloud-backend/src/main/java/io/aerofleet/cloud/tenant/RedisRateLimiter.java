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
 * Key 格式：{@code rate_limit:{key}:{minuteBucket}}，其中 key 为调用方解析的限流 key
 * （真实租户桶 {@code tenant:<id>} 或客户端 IP 桶 {@code ip:<addr>}），
 * minuteBucket 为当前分钟的时间戳。
 * <p>
 * 当 Redis 不可用（StringRedisTemplate 未注入或操作异常）时，返回 true（允许通过），
 * 由调用方回退到内存限流。「可用与否」由 {@link #isAvailable()} 表达，且它反映的是
 * <b>运行态健康度</b>（注入与否 + 实测成败），不只是 bean 注入状态。
 *
 * @author AeroFleet Cloud Team
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String KEY_PREFIX = "rate_limit:";
    private static final Duration WINDOW_TTL = Duration.ofMinutes(1);

    /**
     * 降级后的健康探针间隔：{@link #isAvailable()} 在故障态按此周期放行一次 Redis 调用，
     * 让 Redis 恢复后自动回到分布式口径——否则一次失败会把集群永久钉在单机口径
     * （每实例各计数，集群实际配额 ×N），直到进程重启。
     */
    private static final long HEALTH_PROBE_INTERVAL_MS = 30_000L;

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
     * 运行态健康标志：{@code true} = 最近一次 Redis 操作成功（或尚未失败过，乐观初始），
     * {@code false} = 最近一次操作抛异常。与「bean 是否注入」是两件事——非空模板也可能
     * 处在 Redis 已挂的运行态里，而那才是限流最需要顶住的时刻。
     * <p>
     * volatile：限流跑在每个请求线程上，标志无复合操作，last-write-wins 即可。
     */
    private volatile boolean available = true;

    /**
     * 最近一次降级的时刻（{@link System#currentTimeMillis()}），只作探针节流时钟，
     * 仅在 {@link #available} 为 {@code false} 时被读。
     */
    private volatile long lastDegradedAt = 0L;

    /**
     * Redis 当前是否可用（调用方据此决定信不信 {@link #tryAcquire} 的返回值）。
     * <p>
     * 三个层次，缺一不可：
     * <ul>
     *   <li><b>未注入</b>（{@code redisTemplate == null}）：不可用——单机部署没有 Redis。</li>
     *   <li><b>运行态健康</b>（最近一次操作成功）：可用——信 Redis 的分布式判定。</li>
     *   <li><b>运行态故障</b>（最近一次操作抛异常）：不可用，但每
     *       {@value #HEALTH_PROBE_INTERVAL_MS}ms 放行一次探针调用，让 Redis 恢复后
     *       自动回到分布式口径。</li>
     * </ul>
     * 只看「模板注入与否」会漏判「Redis 已配置但挂了」——那恰恰是限流最需要顶住的时刻
     * （集群 Redis 抖动期），也是本方法与 #244 之前实现的本质区别。
     * <p>
     * 已知窗口（如实交代）：乐观初始 {@code available=true} ⇒ Redis 从进程启动就不可用时，
     * 第一个请求仍会打到 Redis 并被被动放行；此后每个探针周期至多再一次。以「无限流」
     * 为代价分母，这是可交代的上界。
     */
    public boolean isAvailable() {
        if (redisTemplate == null) {
            return false;
        }
        if (available) {
            return true;
        }
        // 故障态：周期性放行一次探针，否则一次失败后即使 Redis 恢复，集群也会永远
        // 卡在单机内存口径（每实例各计数，集群实际配额 ×N），直到进程重启。
        long now = System.currentTimeMillis();
        if (now - lastDegradedAt >= HEALTH_PROBE_INTERVAL_MS) {
            lastDegradedAt = now;
            return true;
        }
        return false;
    }

    /**
     * 尝试获取限流许可。
     * <p>
     * 使用 Redis INCR 原子递增当前分钟窗口的计数器，首次递增时设置 EXPIRE。
     * 当 Redis 不可用时返回 true（允许通过），由调用方回退到内存限流——调用方必须用
     * {@link #isAvailable()} 区分「真限流通过」与这里的被动放行。本方法同时维护运行态
     * 健康标志：成功置真、异常置假（{@link #isAvailable()} 靠它识别「已配置但挂了」）。
     *
     * @param key           限流 key（真实租户桶 {@code tenant:<id>} 或客户端 IP 桶 {@code ip:<addr>}）
     * @param limitPerMinute 每分钟允许的最大请求数
     * @return true 表示允许通过，false 表示已超限
     */
    public boolean tryAcquire(String key, int limitPerMinute) {
        if (redisTemplate == null) {
            return true;
        }

        try {
            String redisKey = buildKey(key);
            Long current = redisTemplate.execute(
                    RATE_LIMIT_REDIS_SCRIPT,
                    Collections.singletonList(redisKey),
                    String.valueOf(WINDOW_TTL.getSeconds())
            );

            if (current != null) {
                // 实测成功：运行态恢复（探针路径下尤其重要——把集群拉回分布式口径）。
                available = true;
                return current <= limitPerMinute;
            }
            // current == null：脚本返回空（RedisTemplate 对 nil 的处理）。不翻转健康标志——
            // 没有证据说 Redis 病了，也没有明确的超限判定，保守放行并维持原口径。
            return true;
        } catch (Exception e) {
            // 实测失败：标记降级 + 重置探针时钟。后续 isAvailable() 为假时调用方走内存
            // 限流（单机口径，好过无限流），直到探针成功自愈。
            available = false;
            lastDegradedAt = System.currentTimeMillis();
            log.warn("Redis 限流操作异常，允许通过（将回退到内存限流）：{}", e.getMessage());
            return true;
        }
    }

    /**
     * 使用默认限流阈值尝试获取许可。
     *
     * @param key 限流 key（真实租户桶或客户端 IP 桶）
     * @return true 表示允许通过，false 表示已超限
     */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, defaultLimitPerMinute);
    }

    /**
     * 构建限流 Key：{@code rate_limit:{key}:{minuteBucket}}。
     * <p>
     * minuteBucket 为当前分钟的时间戳（epoch 秒 / 60），确保同一分钟内的请求落在同一窗口。
     *
     * @param key 限流 key（真实租户桶或客户端 IP 桶）
     * @return Redis Key
     */
    private String buildKey(String key) {
        long minuteBucket = Instant.now().getEpochSecond() / 60;
        return KEY_PREFIX + key + ":" + minuteBucket;
    }
}