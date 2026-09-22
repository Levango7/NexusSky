package io.aerofleet.cloud.tracking;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TrackingController} REST 端点单测。
 * <p>
 * 直接实例化 Controller（无 MockMvc / Spring 上下文），用 AssertJ 断言。
 */
@DisplayName("TrackingController 遗失辅助查找 REST API")
class TrackingControllerTest {

    private TrackingController newController(DeviceRegistry registry,
                                             FlightTrackStore store,
                                             LostDroneAlertService alertService) {
        return new TrackingController(store, alertService, registry);
    }

    private FlightTrackStore.TrackPoint tp(int sysid, long ts, double lat, double lon) {
        return new FlightTrackStore.TrackPoint(sysid, ts, lat, lon, 100.0,
                5.0, 0.0, 0.0, 90.0, 80.0);
    }

    private LostDroneAlertService newAlertService(DeviceRegistry registry, FlightTrackStore store) {
        LostDroneAlertService svc = new LostDroneAlertService(registry, store);
        svc.setMinSearchRadius(50.0);
        svc.setDefaultWindSpeed(5.0);
        return svc;
    }

    // ------------------------------------------------------------------
    // GET /api/v1/tracking/{sysid}/track
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getTrack 未注册 sysid 抛 NotFoundException")
    void getTrack_unregistered_throwsNotFound() {
        DeviceRegistry registry = new DeviceRegistry();
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        assertThatThrownBy(() -> controller.getTrack(99, null))
                .isInstanceOf(io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("getTrack 已注册但无轨迹返回空列表")
    void getTrack_registeredNoTrack_returnsEmpty() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        List<FlightTrackStore.TrackPoint> track = controller.getTrack(1, null);

        assertThat(track).isEmpty();
    }

    @Test
    @DisplayName("getTrack 返回轨迹列表")
    void getTrack_returnsTrackList() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        FlightTrackStore store = new FlightTrackStore();
        store.addPoint(1, tp(1, 1000L, 22.5, 113.9));
        store.addPoint(1, tp(1, 2000L, 22.51, 113.91));
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        List<FlightTrackStore.TrackPoint> track = controller.getTrack(1, null);

        assertThat(track).hasSize(2);
    }

    @Test
    @DisplayName("getTrack limit 参数截取最近 N 条")
    void getTrack_withLimit_truncates() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        FlightTrackStore store = new FlightTrackStore();
        for (int i = 0; i < 10; i++) {
            store.addPoint(1, tp(1, i * 1000L, 22.0 + i * 0.01, 113.0));
        }
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        List<FlightTrackStore.TrackPoint> track = controller.getTrack(1, 3);

        assertThat(track).hasSize(3);
        assertThat(track.get(2).timestampMs).isEqualTo(9000L);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/tracking/{sysid}/replay
    // ------------------------------------------------------------------

    @Test
    @DisplayName("replayTrack 按时间范围查询返回子集（升序）")
    void testReplayEndpoint() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        FlightTrackStore store = new FlightTrackStore();
        // 时间戳：1000, 2000, 3000, 4000, 5000
        for (int i = 1; i <= 5; i++) {
            store.addPoint(1, tp(1, i * 1000L, 22.0 + i * 0.01, 113.0));
        }
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        // 查询 [2000, 4000]，limit=1000（不截断）
        List<FlightTrackStore.TrackPoint> replay = controller.replayTrack(1, 2000L, 4000L, 1000);

        assertThat(replay).hasSize(3);
        assertThat(replay.get(0).timestampMs).isEqualTo(2000L);
        assertThat(replay.get(1).timestampMs).isEqualTo(3000L);
        assertThat(replay.get(2).timestampMs).isEqualTo(4000L);
    }

    @Test
    @DisplayName("replayTrack 不传参数时使用默认值（from=0, to=0, limit=1000）")
    void testReplayDefaultParams() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        FlightTrackStore store = new FlightTrackStore();
        // 添加 5 个点（< 默认 limit 1000）
        for (int i = 1; i <= 5; i++) {
            store.addPoint(1, tp(1, i * 1000L, 22.0, 113.0));
        }
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        // 模拟不传参数：from=0, to=0, limit=1000（Controller 默认值）
        List<FlightTrackStore.TrackPoint> replay = controller.replayTrack(1, 0L, 0L, 1000);

        assertThat(replay).hasSize(5);
        // 验证升序
        assertThat(replay.get(0).timestampMs).isEqualTo(1000L);
        assertThat(replay.get(4).timestampMs).isEqualTo(5000L);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/tracking/{sysid}/last-known
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getLastKnown 未注册 sysid 抛 NotFoundException")
    void getLastKnown_unregistered_throwsNotFound() {
        DeviceRegistry registry = new DeviceRegistry();
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        assertThatThrownBy(() -> controller.getLastKnown(42))
                .isInstanceOf(io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException.class);
    }

    @Test
    @DisplayName("getLastKnown 返回最新轨迹点")
    void getLastKnown_returnsLatest() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        FlightTrackStore store = new FlightTrackStore();
        store.addPoint(1, tp(1, 1000L, 22.50, 113.90));
        FlightTrackStore.TrackPoint latest = tp(1, 2000L, 22.51, 113.91);
        store.addPoint(1, latest);
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        FlightTrackStore.TrackPoint result = controller.getLastKnown(1);

        assertThat(result).isSameAs(latest);
    }

    @Test
    @DisplayName("getLastKnown 无轨迹返回 null")
    void getLastKnown_noTrack_returnsNull() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        assertThat(controller.getLastKnown(1)).isNull();
    }

    // ------------------------------------------------------------------
    // GET /api/v1/tracking/lost
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getLostAlerts 无失联返回空列表")
    void getLostAlerts_empty_returnsEmpty() {
        DeviceRegistry registry = new DeviceRegistry();
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        assertThat(controller.getLostAlerts()).isEmpty();
    }

    @Test
    @DisplayName("getLostAlerts 返回失联告警列表")
    void getLostAlerts_returnsAlerts() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService alerts = newAlertService(registry, store);
        alerts.checkLostDrones();
        TrackingController controller = newController(registry, store, alerts);

        List<LostDroneAlertService.LostAlert> result = controller.getLostAlerts();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sysid).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/tracking/{sysid}/search-guide
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getSearchGuide 未注册 sysid 抛 NotFoundException")
    void getSearchGuide_unregistered_throwsNotFound() {
        DeviceRegistry registry = new DeviceRegistry();
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        assertThatThrownBy(() -> controller.getSearchGuide(77))
                .isInstanceOf(io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException.class);
    }

    @Test
    @DisplayName("getSearchGuide 已注册 sysid 返回查找信息")
    void getSearchGuide_registered_returnsGuide() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis();
        s.lat = 22.5;
        s.lon = 113.9;
        s.relativeAlt = 100.0;
        s.heading = 90.0;
        s.battery = 60;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        LostDroneAlertService.SearchGuide guide = controller.getSearchGuide(1);

        assertThat(guide).isNotNull();
        assertThat(guide.sysid).isEqualTo(1);
        assertThat(guide.batteryPct).isEqualTo(60.0);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/tracking/scan
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scanLostDrones 触发扫描并返回结果 Map")
    void scanLostDrones_returnsResultMap() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(1);
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        Map<String, Object> result = controller.scanLostDrones();

        assertThat(result).containsKey("newlyLost");
        assertThat(result).containsKey("totalLost");
        assertThat(result.get("totalLost")).isEqualTo(1);
    }

    @Test
    @DisplayName("scanLostDrones 无设备返回空结果")
    void scanLostDrones_empty_returnsEmptyResult() {
        DeviceRegistry registry = new DeviceRegistry();
        FlightTrackStore store = new FlightTrackStore();
        LostDroneAlertService alerts = newAlertService(registry, store);
        TrackingController controller = newController(registry, store, alerts);

        Map<String, Object> result = controller.scanLostDrones();

        assertThat(result.get("totalLost")).isEqualTo(0);
    }
}