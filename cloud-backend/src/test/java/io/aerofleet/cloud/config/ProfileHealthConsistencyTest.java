package io.aerofleet.cloud.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * profile 配置的**自洽性**：声称不依赖 Redis 的 profile，不该因为 Redis 而不健康。
 *
 * <h2>这个缺陷是怎么发现的</h2>
 *
 * <p>本机实跑 SDK 集成 E2E（{@code scripts/e2e-sdk-integration.ps1}）时撞到的：
 * {@code application-dev.properties} 注释写着「Redis：开发环境禁用」、
 * {@code spring.cache.type=simple}，但 {@code /actuator/health} 始终 DOWN。
 *
 * <p>原因是 Spring Boot 只要存在 {@code RedisConnectionFactory} 就自动注册
 * {@code RedisHealthIndicator}，它<b>不</b>看缓存用不用 Redis。启动等待、e2e 脚本、
 * 任何以 {@code health == UP} 为门禁的本地流程都会卡住 —— 实测卡了 200 秒才被我
 * 手动放弃。这与"dev 不依赖 redis"的声明直接矛盾。
 *
 * <p>实测对照（同一台机器，redis 容器全程停着）：
 * <pre>
 *   修复前：/actuator/health 200s 仍 DOWN，组件里 redis=DOWN
 *   修复后：/actuator/health  55s  UP，且组件列表里已无 redis
 * </pre>
 *
 * <h2>为什么不能只写死 dev</h2>
 *
 * <p>把规则写成"凡是缓存不依赖 redis 的 profile 都必须关掉 redis 健康指标"，
 * 这样将来新增 profile 也会自动被检查到，而不是等人再卡一次 200 秒。
 *
 * <h2>反向的那一半同样重要</h2>
 *
 * <p>prod / staging 用的是真 Redis 缓存，<b>必须保留</b> redis 健康指标 ——
 * redis 真挂了却报 UP，比 redis 指标永远 DOWN 严重得多。这条反向断言专门防止
 * 有人"顺手"把 prod 也加上 {@code management.health.redis.enabled=false}。
 */
@DisplayName("profile 自洽：不依赖 Redis 的 profile 不该因 Redis 而不健康")
class ProfileHealthConsistencyTest {

    /** 需要检查的 profile 文件；不含 application.properties（它是基线，另行断言）。 */
    private static final List<String> PROFILES = List.of(
            "application-dev.properties",
            "application-test.properties",
            "application-prod.properties",
            "application-staging.properties");

    /**
     * 从**源码树**读，而不是 {@code getResourceAsStream("/application.properties")}。
     *
     * <p>踩过的坑：classpath 上 {@code target/test-classes/application.properties}
     * （来自 {@code src/test/resources} 的测试覆盖文件，只含安全相关的几个键）
     * 排在 {@code target/classes} 之前，所以 classpath 读到的**不是**主配置。
     * 第一版就是这么写的，于是 {@code spring.cache.type} 读出来是 null，
     * 差点把"基线没配缓存类型"当成事实。
     *
     * <p>Surefire 的工作目录是模块根，所以相对路径可用；文件不存在时直接断言失败，
     * 不静默放过 —— 免得工作目录一改就变成"检查了个空集"。
     */
    private static Properties load(String resource) throws IOException {
        java.io.File f = new java.io.File("src/main/resources/" + resource);
        assertThat(f).as("应能从模块根读到主配置 %s", resource).isFile();
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(
                new java.io.FileInputStream(f), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static String cacheType(Properties base, Properties profile) {
        String v = profile.getProperty("spring.cache.type");
        if (v == null) {
            v = base.getProperty("spring.cache.type");
        }
        return v;
    }

    private static boolean redisHealthDisabled(Properties p) {
        return "false".equalsIgnoreCase(String.valueOf(p.getProperty("management.health.redis.enabled")));
    }

    /**
     * 主规则：缓存不依赖 redis 的 profile，必须同时关掉 redis 健康指标。
     */
    @Test
    @DisplayName("缓存非 redis 的 profile 必须禁用 redis 健康指标")
    void nonRedisCacheProfileDisablesRedisHealth() throws IOException {
        Properties base = load("application.properties");
        List<String> offenders = new ArrayList<>();
        List<String> checked = new ArrayList<>();

        for (String name : PROFILES) {
            Properties p = load(name);
            String cache = cacheType(base, p);
            checked.add(name + " -> cache=" + cache);
            if (cache != null && !"redis".equalsIgnoreCase(cache) && !redisHealthDisabled(p)) {
                offenders.add(name + "（cache=" + cache + " 但未设 management.health.redis.enabled=false）");
            }
        }

        assertThat(checked).as("本测试应当真的逐个读到了 profile").hasSize(PROFILES.size());
        assertThat(offenders).as(
                "这些 profile 声称不用 redis，却会因 redis 不可达而 health=DOWN，"
                        + "导致启动等待 / e2e 脚本 / 任何以 health==UP 为门禁的本地流程卡住")
                .isEmpty();
    }

    /**
     * 反向规则：真用 Redis 的 profile **必须保留** redis 健康指标。
     * redis 挂了却报 UP，比 redis 指标永远 DOWN 危险得多。
     */
    @Test
    @DisplayName("缓存用 redis 的 profile 必须保留 redis 健康指标")
    void redisCacheProfileKeepsRedisHealth() throws IOException {
        Properties base = load("application.properties");
        assertThat(base.getProperty("spring.cache.type"))
                .as("基线配置以 redis 为缓存").isEqualTo("redis");

        for (String name : List.of("application-prod.properties", "application-staging.properties")) {
            Properties p = load(name);
            String cache = cacheType(base, p);
            assertThat(cache).as("%s 的缓存类型", name).isEqualTo("redis");
            assertThat(redisHealthDisabled(p))
                    .as("%s 用真 redis 缓存，关掉 redis 健康指标会让 redis 真挂了也报 UP", name)
                    .isFalse();
        }
    }

    /**
     * dev 必须能脱离 Redis 独立起来 —— 这是 {@code application-dev.properties} 注释里
     * 明写的前置条件（"开发环境禁用 Redis"）。所以除了健康指标，也不能有别的
     * 强制依赖 redis 的开关（如某个 bean 的 @ConditionalOnRedis 之外强 connect）。
     * 这里钉住可见的那一半：dev 与 test 都不得声明 redis 为必需。
     */
    @Test
    @DisplayName("dev/test 的缓存都不依赖 redis（与注释声明一致）")
    void devAndTestAreRedisIndependent() throws IOException {
        Properties base = load("application.properties");
        for (String name : List.of("application-dev.properties", "application-test.properties")) {
            String cache = cacheType(base, load(name));
            assertThat(cache)
                    .as("%s 应当声明自己不用 redis 缓存", name)
                    .isIn("simple", "none");
        }
    }
}