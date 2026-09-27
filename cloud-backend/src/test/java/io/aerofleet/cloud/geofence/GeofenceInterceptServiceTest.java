package io.aerofleet.cloud.geofence;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeofenceInterceptService} 拦截链与拦截服务测试。
 * <p>
 * 测试覆盖：
 * <ul>
 *   <li>KEEP_OUT 围栏内 → DENY(IN_KEEP_OUT_ZONE)</li>
 *   <li>KEEP_IN 围栏外 → DENY(OUTSIDE_KEEP_IN_ZONE)</li>
 *   <li>安全区域 → ALLOW</li>
 *   <li>位置 NaN → DENY(POSITION_UNKNOWN)</li>
 *   <li>无围栏配置 → ALLOW</li>
 *   <li>拦截异常 → DENY(INTERCEPT_ERROR)（故障安全）</li>
 *   <li>限飞区缓存中的限飞区命中 → DENY(IN_KEEP_OUT_ZONE)</li>
 *   <li>多围栏命中 → zoneInfo 包含命中围栏信息</li>
 * </ul>
 * <p>
 * 直接实例化（无 Spring 上下文），用 AssertJ 断言。
 * 风格与 {@link GeofenceMonitorTest} 一致。
 */
@DisplayName("GeofenceInterceptService 拦截链与拦截服务")
class GeofenceInterceptServiceTest {

    private GeofenceStore store;
    private GeofenceMonitor monitor;
    private DeviceRegistry registry;
    private GeofenceInterceptService interceptService;

    @BeforeEach
    void setUp() {
        store = new GeofenceStore();
        registry = new DeviceRegistry();
        monitor = new GeofenceMonitor(store, registry);
        interceptService = new GeofenceInterceptService(store, monitor, registry);
    }

    /** KEEP_OUT 圆形围栏（中心 22.5,113.9；半径 500m）。 */
    private GeofenceZone keepOutZone() {
        return GeofenceZone.circleZone(20, "keepout-zone", 22.5, 113.9, 500.0,
                GeofenceZone.Action.WARN, FenceType.KEEP_OUT, 100);
    }

    /** KEEP_IN 圆形围栏（中心 22.5,113.9；半径 500m）。 */
    private GeofenceZone keepInZone() {
        return GeofenceZone.circleZone(21, "keepin-zone", 22.5, 113.9, 500.0,
                GeofenceZone.Action.WARN);
    }

    // ------------------------------------------------------------------
    // 基本拦截场景
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testInKeepOutZone: 无人机在 keep-out 围栏内 → DENY(IN_KEEP_OUT_ZONE)")
    void testInKeepOutZone() {
        store.addZone(keepOutZone());

        // 无人机位于禁飞区中心
        InterceptVerdict verdict = interceptService.check(1, "ARM", 22.5, 113.9);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason()).isEqualTo(InterceptVerdict.DenyReason.IN_KEEP_OUT_ZONE);
        assertThat(verdict.getZoneInfo()).isNotNull();
        assertThat(verdict.getZoneInfo().zoneId()).isEqualTo(20);
        assertThat(verdict.getZoneInfo().name()).isEqualTo("keepout-zone");
        assertThat(verdict.getZoneInfo().fenceType()).isEqualTo(FenceType.KEEP_OUT);
        assertThat(verdict.getZoneInfo().isKeepOut()).isTrue();
    }

    @Test
    @DisplayName("testOutsideKeepInZone: 无人机在 keep-in 围栏外 → DENY(OUTSIDE_KEEP_IN_ZONE)")
    void testOutsideKeepInZone() {
        store.addZone(keepInZone());

        // 无人机位于允许活动区外（距中心约 1km，远超 500m 半径）
        double outsideLat = 22.5 + 1000.0 / 111_319.9;
        InterceptVerdict verdict = interceptService.check(1, "TAKEOFF", outsideLat, 113.9);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason()).isEqualTo(InterceptVerdict.DenyReason.OUTSIDE_KEEP_IN_ZONE);
        assertThat(verdict.getZoneInfo()).isNotNull();
        assertThat(verdict.getZoneInfo().zoneId()).isEqualTo(21);
        assertThat(verdict.getZoneInfo().name()).isEqualTo("keepin-zone");
        assertThat(verdict.getZoneInfo().fenceType()).isEqualTo(FenceType.KEEP_IN);
    }

    @Test
    @DisplayName("testSafeArea: 无人机在安全区域 → ALLOW")
    void testSafeArea() {
        store.addZone(keepOutZone());
        store.addZone(keepInZone());

        // 无人机在 KEEP_OUT 围栏外 + KEEP_IN 围栏内 → 安全区域
        // 两个围栏中心相同，在围栏内意味着也在 KEEP_OUT 外？不对，中心相同意味着在围栏内同时在 KEEP_OUT 内。
        // 需要分开设置：KEEP_OUT 围栏在区域 A，KEEP_IN 围栏在区域 B，无人机在 B 内、A 外。
        monitor.clearAllState();
        store = new GeofenceStore();
        monitor = new GeofenceMonitor(store, registry);
        interceptService = new GeofenceInterceptService(store, monitor, registry);

        // KEEP_OUT 围栏在深圳（22.5, 113.9）
        store.addZone(GeofenceZone.circleZone(20, "sz-keepout", 22.5, 113.9, 500.0,
                GeofenceZone.Action.WARN, FenceType.KEEP_OUT, 100));
        // KEEP_IN 围栏在广州（23.1, 113.3）
        store.addZone(GeofenceZone.circleZone(21, "gz-keepin", 23.1, 113.3, 500.0,
                GeofenceZone.Action.WARN));

        // 无人机在广州 KEEP_IN 围栏内、深圳 KEEP_OUT 围栏外 → ALLOW
        InterceptVerdict verdict = interceptService.check(1, "ARM", 23.1, 113.3);

        assertThat(verdict.isAllowed()).isTrue();
        assertThat(verdict.getDenyReason()).isNull();
    }

    @Test
    @DisplayName("testPositionNaN: 无人机位置 NaN → DENY(POSITION_UNKNOWN)")
    void testPositionNaN() {
        store.addZone(keepOutZone());

        InterceptVerdict verdict = interceptService.check(1, "ARM", Double.NaN, 113.9);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason()).isEqualTo(InterceptVerdict.DenyReason.POSITION_UNKNOWN);
        assertThat(verdict.getZoneInfo()).isNull();
    }

    @Test
    @DisplayName("testPositionBothNaN: 无人机 lat 和 lon 均为 NaN → DENY(POSITION_UNKNOWN)")
    void testPositionBothNaN() {
        store.addZone(keepInZone());

        InterceptVerdict verdict = interceptService.check(1, "TAKEOFF", Double.NaN, Double.NaN);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason()).isEqualTo(InterceptVerdict.DenyReason.POSITION_UNKNOWN);
    }

    @Test
    @DisplayName("testNoZonesConfigured: 无围栏配置 → ALLOW")
    void testNoZonesConfigured() {
        // 不添加任何围栏
        InterceptVerdict verdict = interceptService.check(1, "ARM", 22.5, 113.9);

        assertThat(verdict.isAllowed()).isTrue();
        assertThat(verdict.getDenyReason()).isNull();
    }

    // ------------------------------------------------------------------
    // 故障安全
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testInterceptError: 拦截校验异常 → DENY(INTERCEPT_ERROR)（故障安全）")
    void testInterceptError() {
        // 使用一个会在 isInsideZone 时抛异常的 mock store
        GeofenceStore errorStore = new GeofenceStore() {
            @Override
            public List<GeofenceZone> getAllZones() {
                throw new RuntimeException("模拟数据源故障");
            }
        };
        GeofenceMonitor errorMonitor = new GeofenceMonitor(errorStore, registry);
        GeofenceInterceptService errorService = new GeofenceInterceptService(errorStore, errorMonitor, registry);

        InterceptVerdict verdict = errorService.check(1, "ARM", 22.5, 113.9);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason()).isEqualTo(InterceptVerdict.DenyReason.INTERCEPT_ERROR);
        assertThat(verdict.getZoneInfo()).isNull();
    }

    // ------------------------------------------------------------------
    // 限飞区缓存场景
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testRestrictionZoneHit: 限飞区缓存中的限飞区命中 → DENY(IN_KEEP_OUT_ZONE)")
    void testRestrictionZoneHit() {
        // 模拟限飞区缓存中的限飞区被加载到 GeofenceStore 中作为 KEEP_OUT 围栏
        // 场景：MockRestrictionSource 返回北京首都机场禁飞区，加载到 store 后拦截服务应拒绝
        store.addZone(GeofenceZone.circleZone(30, "BJ-PEK-001 北京首都机场禁飞区",
                40.0801, 116.5846, 5000.0,
                GeofenceZone.Action.LOCK_RTH, FenceType.KEEP_OUT, 100));

        // 无人机在禁飞区内
        InterceptVerdict verdict = interceptService.check(1, "ARM", 40.0801, 116.5846);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason()).isEqualTo(InterceptVerdict.DenyReason.IN_KEEP_OUT_ZONE);
        assertThat(verdict.getZoneInfo()).isNotNull();
        assertThat(verdict.getZoneInfo().zoneId()).isEqualTo(30);
        assertThat(verdict.getZoneInfo().isKeepOut()).isTrue();
    }

    // ------------------------------------------------------------------
    // 多围栏命中
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testMultipleZonesHit: 多围栏命中 → DENY 携带命中围栏信息")
    void testMultipleZonesHit() {
        // 设置两个 KEEP_OUT 围栏，无人机同时位于两个围栏内
        store.addZone(GeofenceZone.circleZone(40, "keepout-A", 22.5, 113.9, 1000.0,
                GeofenceZone.Action.WARN, FenceType.KEEP_OUT, 100));
        store.addZone(GeofenceZone.circleZone(41, "keepout-B", 22.5, 113.9, 2000.0,
                GeofenceZone.Action.WARN, FenceType.KEEP_OUT, 100));

        // 无人机在两个围栏的中心 → 两个围栏都命中
        InterceptVerdict verdict = interceptService.check(1, "ARM", 22.5, 113.9);

        // 拦截服务应拒绝，且 zoneInfo 包含命中围栏信息
        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason()).isEqualTo(InterceptVerdict.DenyReason.IN_KEEP_OUT_ZONE);
        assertThat(verdict.getZoneInfo()).isNotNull();
        // 当前实现返回第一个命中的围栏信息
        assertThat(verdict.getZoneInfo().zoneId()).isIn(40, 41);
        assertThat(verdict.getZoneInfo().isKeepOut()).isTrue();
    }

    @Test
    @DisplayName("testDisabledZoneSkipped: 禁用的围栏不参与拦截校验")
    void testDisabledZoneSkipped() {
        // 添加一个禁用的 KEEP_OUT 围栏
        GeofenceZone disabled = GeofenceZone.circleZone(50, "disabled-keepout",
                22.5, 113.9, 500.0, GeofenceZone.Action.WARN, FenceType.KEEP_OUT, 100)
                .withEnabled(false);
        store.addZone(disabled);

        // 无人机在禁用的围栏内 → 应 ALLOW（禁用围栏不拦截）
        InterceptVerdict verdict = interceptService.check(1, "ARM", 22.5, 113.9);

        assertThat(verdict.isAllowed()).isTrue();
    }
}