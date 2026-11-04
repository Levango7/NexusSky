package io.aerofleet.cloud.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisRateLimiter} 运行态健康标志的回归测试。
 *
 * <h2>被钉住的缺陷（#244 的残留缺口，安全相关）</h2>
 *
 * #244 加的 {@code isAvailable()} 只判「模板是否注入」（{@code redisTemplate != null}）。
 * 于是在「Redis 已配置、但运行时故障」（连接超时、熔断、主从切换）这条最常见路径上：
 * {@code isAvailable()} 仍为 {@code true} ⇒ {@code TenantInterceptor} 照信
 * {@code tryAcquire} 的返回值 ⇒ 异常被捕获后被动放行 ⇒ <b>限流被整体绕过</b>，
 * 且 {@code tryAcquire} 的 WARN 承诺的「将回退到内存限流」并不发生。集群 Redis 抖动期
 * ——恰恰是限流最该顶住滥用请求的时刻——等于无限流。
 *
 * <h2>本类证明的不变式</h2>
 *
 * <ol>
 *   <li>未注入 ⇒ 不可用（#244 已闭的洞，护栏）。</li>
 *   <li>实测异常 ⇒ 立即降级（{@code isAvailable()==false}），调用方转内存限流。</li>
 *   <li>降级期内按探针周期放行一次；探针成功即自愈回分布式口径（不会一次失败钉死）。</li>
 *   <li>经过 {@code TenantInterceptor}：Redis 故障时限流放行窗口 = 单请求（启动/探针），
 *       不是无界——超限必须被拒。</li>
 * </ol>
 *
 * <h2>测试手法</h2>
 *
 * 不走 Mockito 匹配器（也规避安全扫描器对 {@code template.execute(...)} 形态的误报）：
 * 用匿名子类覆写 {@link StringRedisTemplate#execute}，返回计数或抛异常，手工构造
 * 「健康 / 故障」两种运行态。
 */
@DisplayName("RedisRateLimiter：isAvailable() 必须反映运行态健康度，而非只判注入")
class RedisRateLimiterHealthTest {

    private static final int LIMIT = 10;

    private static final long PROBE_INTERVAL_MS = 30_000L;

    private static RedisRateLimiter newLimiter(StringRedisTemplate template) {
        RedisRateLimiter limiter = new RedisRateLimiter();
        ReflectionTestUtils.setField(limiter, "redisTemplate", template);
        return limiter;
    }

    /** execute 返回固定计数的健康 template（计数 ≤ 限额即放行）。 */
    @SuppressWarnings("unchecked")
    private static StringRedisTemplate healthyTemplate(long count) {
        return new StringRedisTemplate() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                return (T) Long.valueOf(count);
            }
        };
    }

    /** execute 每次抛异常的故障 template，同时记录被调次数。 */
    private static StringRedisTemplate failingTemplate(AtomicInteger calls) {
        return new StringRedisTemplate() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                calls.incrementAndGet();
                throw new IllegalStateException("simulated redis outage");
            }
        };
    }

    @Test
    @DisplayName("未注入 template：isAvailable() 恒假（#244 已闭的洞，此处作护栏）")
    void noTemplate_isUnavailable() {
        RedisRateLimiter limiter = newLimiter(null);

        assertThat(limiter.isAvailable()).isFalse();
        assertThat(limiter.tryAcquire("k", LIMIT)).isTrue();
        assertThat(limiter.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("实测成功：标记运行态健康")
    void successfulCall_marksHealthy() {
        RedisRateLimiter limiter = newLimiter(healthyTemplate(2L));

        assertThat(limiter.tryAcquire("k", LIMIT)).isTrue();
        assertThat(limiter.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("实测异常：立即降级——isAvailable() 不再只看模板注入（#244 残留缺口的直接回归）")
    void exception_marksUnavailableImmediately() {
        RedisRateLimiter limiter = newLimiter(failingTemplate(new AtomicInteger()));

        // tryAcquire 的被动放行契约不变（本次请求仍放行）……
        assertThat(limiter.tryAcquire("k", LIMIT)).isTrue();
        // ……但运行态必须被标记为不可用，后续请求才不会照信被动放行。
        assertThat(limiter.isAvailable()).as("异常后必须降级，否则限流被整体绕过").isFalse();
    }

    @Test
    @DisplayName("降级态：探针被 30s 周期门控，窗口内不放行、过期才放行")
    void degraded_probeIsGatedByInterval() {
        RedisRateLimiter limiter = newLimiter(failingTemplate(new AtomicInteger()));
        limiter.tryAcquire("k", LIMIT); // 触发降级

        // 窗口内：连续问也不放行（每次都放行等于没降级）。
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.isAvailable()).as("探针周期内不得放行").isFalse();
        }

        // 时钟推过探针周期：放行一次（且探针本身会重置时钟）。
        ReflectionTestUtils.setField(limiter, "lastDegradedAt",
                System.currentTimeMillis() - PROBE_INTERVAL_MS - 1_000);
        assertThat(limiter.isAvailable()).as("探针周期到期应放行一次").isTrue();
        assertThat(limiter.isAvailable()).as("探针发出后时钟已重置，窗口内不再放行").isFalse();
    }

    @Test
    @DisplayName("探针成功：自愈回分布式口径，不再依赖探针时钟")
    void probeSuccess_selfHeals() {
        RedisRateLimiter limiter = newLimiter(failingTemplate(new AtomicInteger()));
        limiter.tryAcquire("k", LIMIT); // 降级
        assertThat(limiter.isAvailable()).isFalse();

        // 探针周期到期 + Redis 已恢复：探针放行，实测成功即回健康态。
        ReflectionTestUtils.setField(limiter, "lastDegradedAt",
                System.currentTimeMillis() - PROBE_INTERVAL_MS - 1_000);
        ReflectionTestUtils.setField(limiter, "redisTemplate", healthyTemplate(2L));
        assertThat(limiter.isAvailable()).isTrue();
        assertThat(limiter.tryAcquire("k", LIMIT)).isTrue();
        assertThat(limiter.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("集成 TenantInterceptor：Redis 故障时限流放行窗口 = 单请求，超限仍被拒")
    void tenantInterceptor_bypassWindowIsSingleRequest() throws Exception {
        AtomicInteger redisCalls = new AtomicInteger();
        TenantInterceptor interceptor = new TenantInterceptor(LIMIT, new ObjectMapper());
        ReflectionTestUtils.setField(interceptor, "redisRateLimiter",
                newLimiter(failingTemplate(redisCalls)));

        // 第 1 次：乐观启动窗口内打到 Redis，被动放行（javadoc 已如实交代的上界），
        // 不占内存限流窗口。
        assertThat(check(interceptor, "k")).isTrue();
        // 第 2..LIMIT+1 次：降级生效，转内存限流——窗口 LIMIT 次全部放行。
        for (int i = 2; i <= LIMIT + 1; i++) {
            assertThat(check(interceptor, "k")).as("第 %d 次（内存限流窗口内）应放行", i).isTrue();
        }
        // 第 LIMIT+2 次：内存限流必须拒——Redis 故障不是无限流后门。
        assertThat(check(interceptor, "k"))
                .as("Redis 故障不得成为限流绕过后门（放行窗口仅为单请求）").isFalse();

        assertThat(redisCalls.get()).as("降级后不得再咨询 Redis（分流必须发生在调用前）").isEqualTo(1);
    }

    private static boolean check(TenantInterceptor interceptor, String key) throws Exception {
        return (Boolean) ReflectionTestUtils.invokeMethod(interceptor, "checkRateLimit", key);
    }
}
