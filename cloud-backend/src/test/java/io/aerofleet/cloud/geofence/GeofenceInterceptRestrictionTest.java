package io.aerofleet.cloud.geofence;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限飞区（数据源口径）参与命令拦截的测试。
 *
 * <p><b>为什么单独一个类</b>：2026-10-21 之前，拦截链只遍历**用户自建围栏**
 * （{@code GeofenceStore}），限飞区数据（{@link RestrictionCacheManager}）**根本没被查过** ——
 * 平台能指挥无人机飞进民航禁飞区。而当时那个叫 {@code testRestrictionZoneHit} 的测试
 * 声称验证的是"限飞区缓存中的限飞区命中"，实际却把区域加进了**用户围栏表** ⇒
 * **限飞区缓存从头到尾没被碰过**（测试名字在骗人）。本类用**打桩的限飞区缓存**
 * 走真正的那条路。
 *
 * <p>覆盖面：
 * <ul>
 *   <li>圆形限飞区命中 → DENY(IN_RESTRICTION_ZONE)，带限飞区信息</li>
 *   <li>多边形限飞区命中 → DENY(IN_RESTRICTION_ZONE)</li>
 *   <li>限飞区外 → ALLOW</li>
 *   <li>限飞区数据不可用 + fail-closed → DENY(NO_RESTRICTION_DATA)</li>
 *   <li>限飞区数据不可用 + fail-open → ALLOW，但**必须带"未检查"标记**</li>
 *   <li>缓存 stale 但仍有内容 → 仍按最后成功加载的内容判定</li>
 *   <li>用户自建围栏仍然照常生效（回归）</li>
 * </ul>
 */
@DisplayName("GeofenceInterceptService 限飞区拦截")
class GeofenceInterceptRestrictionTest {

    private GeofenceStore store;
    private GeofenceMonitor monitor;
    private DeviceRegistry registry;
    private RestrictionCacheManager cache;

    @BeforeEach
    void setUp() {
        store = new GeofenceStore();
        registry = new DeviceRegistry();
        monitor = new GeofenceMonitor(store, registry);
        cache = mock(RestrictionCacheManager.class);
        when(cache.getRestrictionZones()).thenReturn(List.of());
        when(cache.isStale()).thenReturn(false);
    }

    private GeofenceInterceptService service(boolean failClosed) {
        return new GeofenceInterceptService(store, monitor, registry, cache, true, failClosed);
    }

    /** 圆形限飞区：中心 40.0801,116.5846（首都机场），半径 5000m。 */
    private RestrictionZone circleRestriction() {
        return RestrictionZone.circleRestriction("BJ-PEK-001", "北京首都机场禁飞区",
                40.0801, 116.5846, 5000.0, "TEST-SOURCE");
    }

    @Test
    @DisplayName("无人机在限飞区（圆形）内 → DENY(IN_RESTRICTION_ZONE)，带限飞区信息")
    void insideCircularRestriction_isDenied() {
        when(cache.getRestrictionZones()).thenReturn(List.of(circleRestriction()));

        InterceptVerdict verdict = service(false).check(1, "ARM", 40.0801, 116.5846);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason())
                .isEqualTo(InterceptVerdict.DenyReason.IN_RESTRICTION_ZONE);
        assertThat(verdict.getRestrictionInfo()).isNotNull();
        assertThat(verdict.getRestrictionInfo().zoneId()).isEqualTo("BJ-PEK-001");
        assertThat(verdict.getRestrictionInfo().source()).isEqualTo("TEST-SOURCE");
    }

    @Test
    @DisplayName("无人机在限飞区（多边形）内 → DENY(IN_RESTRICTION_ZONE)")
    void insidePolygonRestriction_isDenied() {
        List<GeofenceZone.GeoPoint> points = List.of(
                new GeofenceZone.GeoPoint(22.50, 113.90),
                new GeofenceZone.GeoPoint(22.50, 114.00),
                new GeofenceZone.GeoPoint(22.60, 114.00),
                new GeofenceZone.GeoPoint(22.60, 113.90));
        when(cache.getRestrictionZones()).thenReturn(List.of(
                RestrictionZone.polygonRestriction("SZ-001", "深圳禁飞区", points, "TEST-SOURCE")));

        InterceptVerdict verdict = service(false).check(1, "TAKEOFF", 22.55, 113.95);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason())
                .isEqualTo(InterceptVerdict.DenyReason.IN_RESTRICTION_ZONE);
        assertThat(verdict.getRestrictionInfo().zoneId()).isEqualTo("SZ-001");
    }

    @Test
    @DisplayName("无人机在限飞区外 → ALLOW（且不是「未检查」状态）")
    void outsideRestriction_isAllowed() {
        when(cache.getRestrictionZones()).thenReturn(List.of(circleRestriction()));

        // 距禁飞区中心约 50km
        InterceptVerdict verdict = service(false).check(1, "ARM", 40.5301, 116.5846);

        assertThat(verdict.isAllowed()).isTrue();
        assertThat(verdict.getDenyReason()).isNull();
        assertThat(verdict.isRestrictionDataUnavailable())
                .as("有可用数据时不应标记为「未检查」")
                .isFalse();
    }

    @Test
    @DisplayName("限飞区数据不可用 + fail-closed → DENY(NO_RESTRICTION_DATA)")
    void noRestrictionData_failClosed_isDenied() {
        when(cache.getRestrictionZones()).thenReturn(List.of());

        InterceptVerdict verdict = service(true).check(1, "ARM", 22.5, 113.9);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason())
                .isEqualTo(InterceptVerdict.DenyReason.NO_RESTRICTION_DATA);
    }

    @Test
    @DisplayName("限飞区数据不可用 + fail-open → ALLOW，但必须带「未检查限飞区」标记")
    void noRestrictionData_failOpen_allowsButFlagsIt() {
        when(cache.getRestrictionZones()).thenReturn(List.of());

        InterceptVerdict verdict = service(false).check(1, "ARM", 22.5, 113.9);

        assertThat(verdict.isAllowed()).isTrue();
        assertThat(verdict.isRestrictionDataUnavailable())
                .as("放行不是因为检查过，而是因为没数据可查 —— 这个状态必须能被看见")
                .isTrue();
    }

    @Test
    @DisplayName("限飞区缓存 stale 但仍有内容 → 仍按最后成功加载的数据判定（过期的禁飞区仍是禁飞区）")
    void staleCacheWithContent_stillEnforced() {
        when(cache.getRestrictionZones()).thenReturn(List.of(circleRestriction()));
        when(cache.isStale()).thenReturn(true);

        InterceptVerdict verdict = service(false).check(1, "ARM", 40.0801, 116.5846);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason())
                .isEqualTo(InterceptVerdict.DenyReason.IN_RESTRICTION_ZONE);
    }

    @Test
    @DisplayName("用户自建围栏仍然照常生效（回归：加了限飞区别把围栏搞坏）")
    void userFenceStillWorks() {
        store.addZone(GeofenceZone.circleZone(20, "user-keepout", 22.5, 113.9, 500.0,
                GeofenceZone.Action.WARN, FenceType.KEEP_OUT, 100));

        InterceptVerdict verdict = service(false).check(1, "ARM", 22.5, 113.9);

        assertThat(verdict.isDenied()).isTrue();
        assertThat(verdict.getDenyReason())
                .isEqualTo(InterceptVerdict.DenyReason.IN_KEEP_OUT_ZONE);
        assertThat(verdict.getZoneInfo().zoneId()).isEqualTo(20);
    }
}
