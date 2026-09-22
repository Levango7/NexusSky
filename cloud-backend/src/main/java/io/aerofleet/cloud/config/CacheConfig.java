package io.aerofleet.cloud.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

/**
 * 缓存配置：根据 spring.cache.type 自动选择 Redis 或 Simple 缓存管理器。
 * <p>
 * - prod profile: RedisCacheManager，带 TTL 和键前缀
 * - dev/test profile: ConcurrentMapCacheManager（无 Redis 依赖）
 */
@Configuration
public class CacheConfig {

    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * Redis 缓存管理器（prod profile 使用）。
     * 当 spring.cache.type=redis 且 RedisConnectionFactory 可用时生效。
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.cache", name = "type", havingValue = "redis")
    public CacheManager redisCacheManager() {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .prefixCacheNameWith("aerofleet::")
                .disableCachingNullValues();

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(config)
                .transactionAware()
                .build();
    }

    /**
     * Simple 缓存管理器（dev/test profile 使用）。
     * 当 spring.cache.type=simple 时生效，无需 Redis 连接。
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring.cache", name = "type", havingValue = "simple", matchIfMissing = true)
    public CacheManager simpleCacheManager() {
        return new ConcurrentMapCacheManager();
    }
}