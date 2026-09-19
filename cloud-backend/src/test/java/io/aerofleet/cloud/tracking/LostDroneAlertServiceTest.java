package io.aerofleet.cloud.tracking;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LostDroneAlertService} 单元测试：失联检测/告警生成/辅助查找信息。
 * <p>
 * 直接实例化（无 Spring 上下文），用 AssertJ 断言。
 * DeviceRegistry 无参构造器下 heartbeatTimeoutSeconds=0，
 * 任何 lastHeartbeatMs>0 的在线设备都会被 sweepOffline 标记离线。
 */
@DisplayName("LostDroneAlertService 失联告警+辅助查找")
class LostDroneAlertServiceTest {

    private LostDroneAlertService newService(DeviceRegistry registry, FlightTrackStore store) {
        LostDroneAlertService svc = new LostDroneAlertService(registry, store);
        svc.setMinSearchRadius(50.0);
        svc.setDefaultWindSpeed(5.0);
        return svc;
    }

    private FlightTrackStore.TrackPoint tp(int sysid, long ts, double lat, double lon, double alt,
                                           double vx, double vy, double heading, double battery) {
        return new FlightTrackStore.TrackPoint(sysid, ts, lat, lon, alt,
                vx, vy, 0.0, heading, battery);
    }

    // ------------------------------------------------------------------
    // 失联检测
    // ------------------------------------------------------------------

    @Test
    @DisplayName("checkLostDrones 无设备返回空列表")
    void checkLostDrones_empty_returnsEmpty() {
        DeviceRegistry registry = new DeviceRegistry();
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);

        List<Integer> result = svc.checkLostDrones();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("checkLostDrones 心跳超时设备被标记失联")
    void checkLostDrones_staleDrone_markedLost() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L; // 100s 前
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);

        List<Integer> result = svc.checkLostDrones();

        assertThat(result).contains(1);
        assertThat(svc.isLost(1)).isTrue();
    }

    @Test
    @DisplayName("checkLostDrones 刚注册设备（无心跳）不标记失联")
    void checkLostDrones_freshDrone_notLost() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1); // lastHeartbeatMs=0
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);

        List<Integer> result = svc.checkLostDrones();

        assertThat(result).isEmpty();
        assertThat(svc.isLost(1)).isFalse();
    }

    @Test
    @DisplayName("checkLostDrones 重复扫描不重复生成告警")
    void checkLostDrones_repeatScan_noDuplicate() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);

        svc.checkLostDrones();
        // 第二次扫描：设备已离线，sweepOffline 不会再返回它
        List<Integer> second = svc.checkLostDrones();

        assertThat(second).isEmpty();
        assertThat(svc.getLostAlerts()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 告警生成
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getLostAlerts 返回所有失联告警（按 sysid 升序）")
    void getLostAlerts_sortedBySysid() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s1 = registry.registerIfAbsent(3);
        s1.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        DroneSnapshot s2 = registry.registerIfAbsent(1);
        s2.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);

        svc.checkLostDrones();
        List<LostDroneAlertService.LostAlert> alerts = svc.getLostAlerts();

        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(0).sysid).isEqualTo(1);
        assertThat(alerts.get(1).sysid).isEqualTo(3);
    }

    @Test
    @DisplayName("LostAlert 包含最后位置/电量/航向信息")
    void lostAlert_containsLastKnownInfo() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        s.lat = 22.5;
        s.lon = 113.9;
        s.relativeAlt = 80.0;
        s.heading = 45.0;
        s.battery = 60;
        s.vx = 3.0;
        s.vy = 4.0;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);

        svc.checkLostDrones();
        LostDroneAlertService.LostAlert alert = svc.getLostAlerts().get(0);

        assertThat(alert.sysid).isEqualTo(1);
        assertThat(alert.batteryPct).isEqualTo(60.0);
        assertThat(alert.lastKnownPos).isNotNull();
        assertThat(alert.lastKnownPos.lat).isEqualTo(22.5);
        assertThat(alert.lastKnownPos.lon).isEqualTo(113.9);
    }

    @Test
    @DisplayName("LostAlert 优先使用轨迹末点位置（更精确）")
    void lostAlert_prefersTrackPoint() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        s.lat = 22.50; // 快照位置
        s.lon = 113.90;
        s.relativeAlt = 50.0;
        FlightTrackStore store = new FlightTrackStore();
        // 轨迹末点位置不同
        store.addPoint(1, tp(1, System.currentTimeMillis() - 50_000L,
                22.55, 113.95, 75.0, 5.0, 0.0, 90.0, 80.0));
        LostDroneAlertService svc = newService(registry, store);

        svc.checkLostDrones();
        LostDroneAlertService.LostAlert alert = svc.getLostAlerts().get(0);

        assertThat(alert.lastKnownPos.lat).isEqualTo(22.55);
        assertThat(alert.lastKnownPos.lon).isEqualTo(113.95);
        assertThat(alert.lastKnownPos.alt).isEqualTo(75.0);
        assertThat(alert.lastKnownPos.source).isEqualTo("track");
    }

    // ------------------------------------------------------------------
    // 辅助查找信息
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getSearchGuide 未注册无人机返回 null")
    void getSearchGuide_unregistered_returnsNull() {
        DeviceRegistry registry = new DeviceRegistry();
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);

        assertThat(svc.getSearchGuide(99)).isNull();
    }

    @Test
    @DisplayName("getSearchGuide 返回最后位置+电量+坠落范围")
    void getSearchGuide_returnsFullInfo() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis() - 1000L;
        s.lat = 22.5;
        s.lon = 113.9;
        s.relativeAlt = 100.0;
        s.heading = 90.0;
        s.battery = 50;
        s.vx = 5.0;
        s.vy = 0.0;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);

        LostDroneAlertService.SearchGuide guide = svc.getSearchGuide(1);

        assertThat(guide).isNotNull();
        assertThat(guide.sysid).isEqualTo(1);
        assertThat(guide.batteryPct).isEqualTo(50.0);
        assertThat(guide.lastKnownPos).isNotNull();
        assertThat(guide.lastKnownPos.lat).isEqualTo(22.5);
        assertThat(guide.fallEstimate).isNotNull();
        assertThat(guide.fallEstimate.lastAlt).isEqualTo(100.0);
        assertThat(guide.fallEstimate.searchRadius).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("getSearchGuide 坠落范围：高度 100m + 风速 5m/s → 搜索半径 >= 50m 兜底")
    void getSearchGuide_fallRange_respectsMinRadius() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis();
        s.lat = 22.5;
        s.lon = 113.9;
        s.relativeAlt = 10.0; // 低高度
        s.heading = 0.0;
        s.battery = 80;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);
        svc.setMinSearchRadius(50.0);
        svc.setDefaultWindSpeed(2.0); // 低风速

        LostDroneAlertService.SearchGuide guide = svc.getSearchGuide(1);

        // 10m 高度，t=sqrt(2*10/9.81)≈1.43s，风偏 2*1.43≈2.86m，远小于 50m 兜底
        assertThat(guide.fallEstimate.searchRadius).isGreaterThanOrEqualTo(50.0);
    }

    @Test
    @DisplayName("getSearchGuide 坠落范围：高度 500m + 风速 10m/s → 搜索半径显著增大")
    void getSearchGuide_fallRange_highAltLargeRadius() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis();
        s.lat = 22.5;
        s.lon = 113.9;
        s.relativeAlt = 500.0;
        s.heading = 0.0;
        s.battery = 30;
        s.vx = 10.0;
        s.vy = 0.0;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);
        svc.setMinSearchRadius(50.0);
        svc.setDefaultWindSpeed(10.0);

        LostDroneAlertService.SearchGuide guide = svc.getSearchGuide(1);

        // t=sqrt(2*500/9.81)≈10.1s，风偏 10*10.1≈101m，惯性 10*10.1≈101m，合计 ~202m
        assertThat(guide.fallEstimate.fallTimeSec).isGreaterThan(9.0);
        assertThat(guide.fallEstimate.searchRadius).isGreaterThan(150.0);
    }

    @Test
    @DisplayName("getSearchGuide 预测落点：沿航向偏移")
    void getSearchGuide_predictedLandingOffset() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis();
        s.lat = 22.0;
        s.lon = 113.0;
        s.relativeAlt = 200.0;
        s.heading = 0.0; // 正北
        s.battery = 50;
        s.vx = 5.0;
        s.vy = 0.0;
        s.envWindSpeed = 5.0;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);

        LostDroneAlertService.SearchGuide guide = svc.getSearchGuide(1);

        // 航向 0°（正北），预测落点纬度应大于起始纬度
        assertThat(guide.fallEstimate.predictedLat).isGreaterThan(22.0);
        assertThat(guide.fallEstimate.predictedLon).isCloseTo(113.0, within(0.001));
    }

    // ------------------------------------------------------------------
    // 告警清理
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearAlert 清除指定无人机告警")
    void clearAlert_removesAlert() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);
        svc.checkLostDrones();
        assertThat(svc.isLost(1)).isTrue();

        svc.clearAlert(1);

        assertThat(svc.isLost(1)).isFalse();
        assertThat(svc.getLostAlerts()).isEmpty();
    }

    @Test
    @DisplayName("clearAllAlerts 清除所有告警")
    void clearAllAlerts_removesAll() {
        DeviceRegistry registry = new DeviceRegistry();
        for (int i = 1; i <= 3; i++) {
            DroneSnapshot s = registry.registerIfAbsent(i);
            s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        }
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);
        svc.checkLostDrones();
        assertThat(svc.getLostAlerts()).hasSize(3);

        svc.clearAllAlerts();

        assertThat(svc.getLostAlerts()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 边界场景
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getSearchGuide 无位置数据时仍返回坠落估算（高度 0）")
    void getSearchGuide_noPosition_fallEstimateZeroAlt() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis();
        // lat/lon 保持 NaN（未收到 GPS）
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService svc = newService(registry, store);

        LostDroneAlertService.SearchGuide guide = svc.getSearchGuide(1);

        assertThat(guide).isNotNull();
        assertThat(guide.lastKnownPos).isNull();
        assertThat(guide.fallEstimate.lastAlt).isZero();
        assertThat(guide.fallEstimate.searchRadius).isGreaterThanOrEqualTo(50.0); // 兜底
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}