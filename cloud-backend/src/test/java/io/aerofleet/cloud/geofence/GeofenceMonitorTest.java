package io.aerofleet.cloud.geofence;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link GeofenceMonitor} 单元测试：圆形/多边形围栏越界检测 + 几何计算 + 定期检查。
 * <p>
 * 直接实例化（无 Spring 上下文），用 AssertJ 断言。
 * 风格与 {@code LostDroneAlertServiceTest} 一致。
 */
@DisplayName("GeofenceMonitor 围栏越界检测")
class GeofenceMonitorTest {

    private GeofenceStore store;
    private DeviceRegistry registry;
    private GeofenceMonitor monitor;

    @BeforeEach
    void setUp() {
        store = new GeofenceStore();
        registry = new DeviceRegistry();
        monitor = new GeofenceMonitor(store, registry);
    }

    /** 圆形围栏（中心 22.5,113.9；半径 500m）。 */
    private GeofenceZone baseCircle() {
        return GeofenceZone.circleZone(1, "base-circle", 22.5, 113.9, 500.0,
                GeofenceZone.Action.WARN);
    }

    // ------------------------------------------------------------------
    // 圆形围栏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testCircleInside: 位置在圆内不触发越界")
    void testCircleInside() {
        store.addZone(baseCircle());
        // 中心点，距离 0
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, 22.5, 113.9);

        assertThat(events).isEmpty();
        assertThat(store.getBreachHistory()).isEmpty();
    }

    @Test
    @DisplayName("testCircleOutside: 位置在圆外触发 EXIT 越界")
    void testCircleOutside() {
        store.addZone(baseCircle());
        // 距离中心约 1.4km，远超 500m 半径
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, 22.51, 113.91);

        assertThat(events).hasSize(1);
        GeofenceBreachEvent ev = events.get(0);
        assertThat(ev.getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.EXIT);
        assertThat(ev.getSysid()).isEqualTo(1);
        assertThat(ev.getZoneId()).isEqualTo(1);
        assertThat(store.getBreachHistory()).hasSize(1);
    }

    @Test
    @DisplayName("testCircleBoundary: 边界条件（距离≈半径）")
    void testCircleBoundary() {
        store.addZone(baseCircle());
        // 1° 纬度 ≈ 111319.9m（WGS84）。用 450m/550m 分别构造内/外边界点。
        double metersPerDegLat = 111_319.9;
        double insideLat = 22.5 + 450.0 / metersPerDegLat;   // 距离 ≈ 450m < 500m → 内部
        double outsideLat = 22.5 + 550.0 / metersPerDegLat;  // 距离 ≈ 550m > 500m → 外部

        // 确认几何计算与预期一致
        assertThat(monitor.haversineDistance(22.5, 113.9, insideLat, 113.9))
                .isLessThan(500.0);
        assertThat(monitor.haversineDistance(22.5, 113.9, outsideLat, 113.9))
                .isGreaterThan(500.0);

        // 边界内点不触发越界
        List<GeofenceBreachEvent> inside = monitor.checkPosition(1, insideLat, 113.9);
        assertThat(inside).isEmpty();

        // 边界外点触发 EXIT
        List<GeofenceBreachEvent> outside = monitor.checkPosition(2, outsideLat, 113.9);
        assertThat(outside).hasSize(1);
        assertThat(outside.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.EXIT);
    }

    // ------------------------------------------------------------------
    // 多边形围栏
    // ------------------------------------------------------------------

    /** 矩形围栏：(22.0,113.0)-(22.0,114.0)-(23.0,114.0)-(23.0,113.0)。 */
    private GeofenceZone basePolygon() {
        List<GeofenceZone.GeoPoint> pts = List.of(
                new GeofenceZone.GeoPoint(22.0, 113.0),
                new GeofenceZone.GeoPoint(22.0, 114.0),
                new GeofenceZone.GeoPoint(23.0, 114.0),
                new GeofenceZone.GeoPoint(23.0, 113.0));
        return GeofenceZone.polygonZone(2, "base-polygon", pts, GeofenceZone.Action.WARN);
    }

    @Test
    @DisplayName("testPolygonInside: 多边形内部不触发")
    void testPolygonInside() {
        store.addZone(basePolygon());
        // 中心点
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, 22.5, 113.5);

        assertThat(events).isEmpty();
        assertThat(store.getBreachHistory()).isEmpty();
    }

    @Test
    @DisplayName("testPolygonOutside: 多边形外部触发 EXIT")
    void testPolygonOutside() {
        store.addZone(basePolygon());
        // 在矩形外（纬度低于下边界 22.0）
        List<GeofenceBreachEvent> events = monitor.checkPosition(1, 21.5, 113.5);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.EXIT);
        assertThat(events.get(0).getZoneId()).isEqualTo(2);
    }

    @Test
    @DisplayName("testPolygonConcave: 凹多边形（L 形）内部/凹角外部")
    void testPolygonConcave() {
        // L 形顶点（lat, lon）：
        // A(22.0,113.0) B(22.0,113.3) C(22.1,113.3) D(22.1,113.1)
        // E(22.3,113.1) F(22.3,113.0)
        List<GeofenceZone.GeoPoint> pts = List.of(
                new GeofenceZone.GeoPoint(22.0, 113.0),
                new GeofenceZone.GeoPoint(22.0, 113.3),
                new GeofenceZone.GeoPoint(22.1, 113.3),
                new GeofenceZone.GeoPoint(22.1, 113.1),
                new GeofenceZone.GeoPoint(22.3, 113.1),
                new GeofenceZone.GeoPoint(22.3, 113.0));
        GeofenceZone lShape = GeofenceZone.polygonZone(3, "L-shape", pts, GeofenceZone.Action.WARN);
        store.addZone(lShape);

        // 左上部分（内部）
        assertThat(monitor.isInsidePolygon(22.05, 113.2, pts)).isTrue();
        // 右下部分（内部）
        assertThat(monitor.isInsidePolygon(22.25, 113.05, pts)).isTrue();
        // 凹角处（外部）
        assertThat(monitor.isInsidePolygon(22.25, 113.2, pts)).isFalse();

        // 内部点不触发越界
        assertThat(monitor.checkPosition(1, 22.05, 113.2)).isEmpty();
        // 凹角外部点触发越界
        List<GeofenceBreachEvent> events = monitor.checkPosition(2, 22.25, 113.2);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.EXIT);
    }

    // ------------------------------------------------------------------
    // Haversine 距离
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testHaversineDistance: 距离计算正确性")
    void testHaversineDistance() {
        // 同一点距离 = 0
        assertThat(monitor.haversineDistance(22.0, 113.0, 22.0, 113.0))
                .isCloseTo(0.0, within(0.001));

        // 纬度差 0.001° ≈ 111.3m（1° ≈ 111.32km）
        double dLat = monitor.haversineDistance(22.0, 113.0, 22.001, 113.0);
        assertThat(dLat).isCloseTo(111.32, within(0.5));

        // 经度差 0.001° 在纬度 22° ≈ 111.32 * cos(22°) ≈ 103.25m
        double dLon = monitor.haversineDistance(22.0, 113.0, 22.0, 113.001);
        double expected = 111_319.9 * Math.cos(Math.toRadians(22.0)) * 0.001;
        assertThat(dLon).isCloseTo(expected, within(0.5));

        // 对称性：d(A,B) == d(B,A)
        double dAB = monitor.haversineDistance(22.5, 113.9, 22.51, 113.91);
        double dBA = monitor.haversineDistance(22.51, 113.91, 22.5, 113.9);
        assertThat(dAB).isCloseTo(dBA, within(0.001));

        // 距离非负
        assertThat(dAB).isGreaterThan(0.0);

        // 圆形围栏 isInsideCircle 与 haversine 一致
        assertThat(monitor.isInsideCircle(22.5, 113.9, 22.5, 113.9, 500.0)).isTrue();
        assertThat(monitor.isInsideCircle(22.51, 113.91, 22.5, 113.9, 500.0)).isFalse();
    }

    // ------------------------------------------------------------------
    // 定期检查所有无人机
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testScheduledCheck: 定期检查所有在线无人机")
    void testScheduledCheck() {
        store.addZone(baseCircle());

        // 在线无人机在围栏内（不触发）
        DroneSnapshot inside = registry.registerIfAbsent(1);
        inside.online = true;
        inside.lastHeartbeatMs = System.currentTimeMillis();
        inside.lat = 22.5;
        inside.lon = 113.9;

        // 在线无人机在围栏外（触发 EXIT）
        DroneSnapshot outside = registry.registerIfAbsent(2);
        outside.online = true;
        outside.lastHeartbeatMs = System.currentTimeMillis();
        outside.lat = 22.55;
        outside.lon = 113.95;

        // 离线无人机（不检查）
        DroneSnapshot offline = registry.registerIfAbsent(3);
        offline.online = false;
        offline.lat = 22.55;
        offline.lon = 113.95;

        // 无位置无人机（不检查）
        DroneSnapshot noPos = registry.registerIfAbsent(4);
        noPos.online = true;
        noPos.lastHeartbeatMs = System.currentTimeMillis();
        // lat/lon 保持 NaN

        // 调用 scheduledCheck（内部调用 checkAllDrones）
        monitor.scheduledCheck();

        List<GeofenceBreachEvent> history = store.getBreachHistory();
        // 只有 sysid=2 在围栏外，生成 1 条 EXIT 事件
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getSysid()).isEqualTo(2);
        assertThat(history.get(0).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.EXIT);

        // 再次检查：状态未变，不生成新事件
        monitor.scheduledCheck();
        assertThat(store.getBreachHistory()).hasSize(1);

        // sysid=2 飞回围栏内 → 生成 ENTER 事件（恢复）
        outside.lat = 22.5;
        outside.lon = 113.9;
        monitor.scheduledCheck();
        List<GeofenceBreachEvent> history2 = store.getBreachHistory();
        assertThat(history2).hasSize(2);
        assertThat(history2.get(1).getBreachType()).isEqualTo(GeofenceBreachEvent.BreachType.ENTER);
        assertThat(history2.get(1).getSysid()).isEqualTo(2);
    }
}