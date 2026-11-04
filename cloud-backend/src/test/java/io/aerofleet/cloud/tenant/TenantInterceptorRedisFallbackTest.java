package io.aerofleet.cloud.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TenantInterceptor} 限流的 **Redis 故障降级**回归测试。
 *
 * <h2>被钉住的缺陷（安全相关）</h2>
 *
 * {@link RedisRateLimiter#tryAcquire} 的契约是「Redis 不可用（未注入 / 操作异常）时
 * 返回 {@code true} 放行，由调用方回退」。而 {@code TenantInterceptor} 的旧实现是：
 *
 * <pre>
 * if (redisRateLimiter != null) {
 *     return redisRateLimiter.tryAcquire(key, rateLimitPerMinute);   // ← 把「被动放行」当「限流通过」
 * }
 * return checkRateLimitInMemory(key);
 * </pre>
 *
 * 于是 <b>Redis 真正故障时（这才是主要场景）限流被整体绕过</b>——多实例部署下等于全集群
 * 无限流，而注释与类文档都宣称“Redis 不可用时回退内存限流”。只有 Redis bean 压根不存在时
 * 才真的回退。
 *
 * <h2>本类证明的不变式</h2>
 *
 * Redis 不可用（未注入 / 已标记故障）⇒ 必走内存限流，而不是被放行。
 */
@DisplayName("TenantInterceptor 限流：Redis 故障必须回退内存限流")
class TenantInterceptorRedisFallbackTest {

    private static final int LIMIT = 3;

    private TenantInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TenantInterceptor(LIMIT, new ObjectMapper());
    }

    private void injectRedis(RedisRateLimiter limiter) {
        ReflectionTestUtils.setField(interceptor, "redisRateLimiter", limiter);
    }

    private boolean check(String key) throws Exception {
        return (boolean) ReflectionTestUtils.invokeMethod(interceptor, "checkRateLimit", key);
    }

    @Test
    @DisplayName("Redis 未注入：走内存限流，第 LIMIT+1 次被拒")
    void noRedisBean_fallsBackToMemoryLimit() throws Exception {
        injectRedis(null);

        for (int i = 1; i <= LIMIT; i++) {
            assertThat(check("k")).as("第 %d 次应放行", i).isTrue();
        }
        assertThat(check("k")).as("超过上限必须拒绝——不许因为 Redis 不在就无限流").isFalse();
    }

    @Test
    @DisplayName("Redis 已标记故障：同样回退内存限流，不被放行")
    void redisMarkedUnavailable_fallsBackToMemoryLimit() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.isAvailable()).thenReturn(false);
        injectRedis(limiter);

        for (int i = 1; i <= LIMIT; i++) {
            assertThat(check("k")).isTrue();
        }
        assertThat(check("k")).as("Redis 故障不得成为限流绕过后门").isFalse();

        // 且此时不应去问 Redis（分流必须发生在调用前）
        org.mockito.Mockito.verify(limiter, org.mockito.Mockito.never()).tryAcquire(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("Redis 可用：信它的判定（不重复做内存计数）")
    void redisAvailable_usesRedisVerdict() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.isAvailable()).thenReturn(true);
        when(limiter.tryAcquire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);
        injectRedis(limiter);

        assertThat(check("k")).as("Redis 说超限就必须拒").isFalse();
        org.mockito.Mockito.verify(limiter, org.mockito.Mockito.times(1))
                .tryAcquire(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt());
    }
}
