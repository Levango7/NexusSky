package io.aerofleet.cloud.geofence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RestrictionCacheManager} 限飞区缓存管理器测试。
 * <p>
 * 测试覆盖：
 * <ul>
 *   <li>启动时加载成功 → 缓存非空 + stale=false</li>
 *   <li>启动时加载失败 → 空缓存 + stale=true</li>
 *   <li>定期刷新成功 → 缓存更新 + stale 重置</li>
 *   <li>TTL 过期 → stale=true</li>
 * </ul>
 * <p>
 * 直接实例化（无 Spring 上下文），用 AssertJ 断言。
 */
@DisplayName("RestrictionCacheManager 限飞区缓存管理器")
class RestrictionCacheManagerTest {

    private RestrictionSourceConfig config;

    @BeforeEach
    void setUp() {
        config = new RestrictionSourceConfig();
        config.setSourceType(RestrictionSourceConfig.SourceType.MOCK);
        config.setEnabled(true);
        // 设置短 TTL 便于测试过期场景
        config.setCacheTtlMs(100);
    }

    // ------------------------------------------------------------------
    // 启动时加载
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testInitLoadSuccess: 启动时加载成功 → 缓存非空 + stale=false")
    void testInitLoadSuccess() {
        RestrictionCacheManager manager = new RestrictionCacheManager(config);
        manager.init();

        List<RestrictionZone> zones = manager.getRestrictionZones();

        // MockRestrictionSource 返回 3 个限飞区
        assertThat(zones).isNotEmpty();
        assertThat(zones).hasSize(3);

        // 加载成功 → stale=false
        assertThat(manager.isStale()).isFalse();
    }

    @Test
    @DisplayName("testInitLoadFailure: 启动时加载失败 → 空缓存 + stale=true")
    void testInitLoadFailure() {
        // 使用会抛异常的自定义数据源
        RestrictionDataSource failingSource = new RestrictionDataSource() {
            @Override
            public String getSourceId() {
                return "failing";
            }

            @Override
            public List<RestrictionZone> fetch() throws Exception {
                throw new RuntimeException("模拟数据源故障");
            }
        };

        // 创建一个使用失败数据源的缓存管理器
        RestrictionCacheManager manager = createManagerWithSource(config, failingSource);
        manager.init();

        List<RestrictionZone> zones = manager.getRestrictionZones();

        // 加载失败 → 空缓存
        assertThat(zones).isEmpty();

        // 加载失败 → stale=true
        assertThat(manager.isStale()).isTrue();
    }

    // ------------------------------------------------------------------
    // 定期刷新
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testRefreshSuccess: 定期刷新成功 → 缓存更新 + stale 重置")
    void testRefreshSuccess() {
        // 先用失败数据源让初始加载失败
        RestrictionDataSource failingSource = new RestrictionDataSource() {
            @Override
            public String getSourceId() {
                return "failing";
            }

            @Override
            public List<RestrictionZone> fetch() throws Exception {
                throw new RuntimeException("初始加载失败");
            }
        };

        RestrictionCacheManager manager = createManagerWithSource(config, failingSource);
        manager.init();

        // 初始加载失败 → stale=true, 空缓存
        assertThat(manager.isStale()).isTrue();
        assertThat(manager.getRestrictionZones()).isEmpty();

        // 切换为成功数据源并刷新
        setDataSource(manager, new MockRestrictionSource());
        manager.refresh();

        // 刷新成功 → 缓存非空 + stale=false
        assertThat(manager.getRestrictionZones()).isNotEmpty();
        assertThat(manager.isStale()).isFalse();
    }

    @Test
    @DisplayName("testRefreshFailurePreservesOldCache: 刷新失败 → 保留旧缓存 + stale=true")
    void testRefreshFailurePreservesOldCache() {
        RestrictionCacheManager manager = new RestrictionCacheManager(config);
        manager.init();

        // 初始加载成功 → 缓存有数据 + stale=false
        List<RestrictionZone> initialZones = manager.getRestrictionZones();
        assertThat(initialZones).hasSize(3);
        assertThat(manager.isStale()).isFalse();

        // 切换为失败数据源并刷新
        RestrictionDataSource failingSource = new RestrictionDataSource() {
            @Override
            public String getSourceId() {
                return "failing";
            }

            @Override
            public List<RestrictionZone> fetch() throws Exception {
                throw new RuntimeException("刷新失败");
            }
        };
        setDataSource(manager, failingSource);
        manager.refresh();

        // 刷新失败 → 保留旧缓存 + stale=true
        List<RestrictionZone> zonesAfterFailure = manager.getRestrictionZones();
        assertThat(zonesAfterFailure).hasSize(3); // 旧缓存仍在
        assertThat(manager.isStale()).isTrue();
    }

    // ------------------------------------------------------------------
    // TTL 过期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testTtlExpiry: TTL 过期 → stale=true")
    void testTtlExpiry() throws InterruptedException {
        // 设置极短 TTL（50ms）
        config.setCacheTtlMs(50);

        RestrictionCacheManager manager = new RestrictionCacheManager(config);
        manager.init();

        // 初始加载成功 → stale=false
        assertThat(manager.isStale()).isFalse();

        // 等待 TTL 过期
        Thread.sleep(80);

        // TTL 过期 → stale=true
        assertThat(manager.isStale()).isTrue();
    }

    @Test
    @DisplayName("testTtlNotExpired: TTL 未过期 → stale=false")
    void testTtlNotExpired() {
        // 设置较长 TTL（10秒）
        config.setCacheTtlMs(10_000);

        RestrictionCacheManager manager = new RestrictionCacheManager(config);
        manager.init();

        // 初始加载成功 → stale=false
        assertThat(manager.isStale()).isFalse();
    }

    // ------------------------------------------------------------------
    // 禁用配置
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testDisabledConfig: 配置禁用 → init 不加载，缓存为空")
    void testDisabledConfig() {
        config.setEnabled(false);

        RestrictionCacheManager manager = new RestrictionCacheManager(config);
        manager.init();

        // 禁用时不加载数据源 → 空缓存
        assertThat(manager.getRestrictionZones()).isEmpty();
        // stale 保持初始值 true（从未成功加载）
        assertThat(manager.isStale()).isTrue();
    }

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testGetStatus: getStatus 返回缓存状态信息")
    void testGetStatus() {
        RestrictionCacheManager manager = new RestrictionCacheManager(config);
        manager.init();

        Map<String, Object> status = manager.getStatus();

        assertThat(status).containsKey("zoneCount");
        assertThat(status).containsKey("lastFetchMs");
        assertThat(status).containsKey("sourceType");
        assertThat(status).containsKey("sourceId");
        assertThat(status).containsKey("stale");
        assertThat(status).containsKey("enabled");

        assertThat(status.get("zoneCount")).isEqualTo(3);
        assertThat(status.get("sourceType")).isEqualTo("MOCK");
        assertThat(status.get("sourceId")).isEqualTo("mock");
        assertThat(status.get("stale")).isEqualTo(false);
        assertThat(status.get("enabled")).isEqualTo(true);
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 创建一个使用指定数据源的 RestrictionCacheManager（绕过 init 中的数据源创建逻辑）。 */
    private RestrictionCacheManager createManagerWithSource(RestrictionSourceConfig config,
                                                             RestrictionDataSource source) {
        RestrictionCacheManager manager = new RestrictionCacheManager(config);
        // 在 init 之前通过反射设置 dataSource 字段
        try {
            var field = RestrictionCacheManager.class.getDeclaredField("dataSource");
            field.setAccessible(true);
            field.set(manager, source);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set dataSource field", e);
        }
        return manager;
    }

    /** 通过反射设置 dataSource 字段。 */
    private void setDataSource(RestrictionCacheManager manager, RestrictionDataSource source) {
        try {
            var field = RestrictionCacheManager.class.getDeclaredField("dataSource");
            field.setAccessible(true);
            field.set(manager, source);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set dataSource field", e);
        }
    }
}