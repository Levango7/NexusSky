package io.aerofleet.cloud.scheduling;

import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.enums.ConflictType;
import io.aerofleet.mavlink.messages.ConflictAlertMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M10 机队冲突扫描单测（WS_TYPE_MAP 收口 2026-10-05 边界清零）。
 * <p>
 * 覆盖 ConflictScanService 的航迹构建、冲突对检测与 ConflictAlertMsg(30049)
 * 发布（conflictType/severity 映射、无冲突不发布），
 * 以及 ConflictAvoidanceService.scanAllConflicts 的 sysid 配对正确性。
 */
@DisplayName("ConflictScanService 机队冲突扫描 (30049)")
class ConflictScanServiceTest {

    private StubRegistry registry;
    private ApplicationEventPublisher publisher;
    private TelemetryWebSocketHandler wsHandler;
    private ConflictScanService scanService;
    private final ConflictAvoidanceService conflictService = new ConflictAvoidanceService();

    @BeforeEach
    void setUp() {
        registry = new StubRegistry();
        publisher = mock(ApplicationEventPublisher.class);
        wsHandler = mock(TelemetryWebSocketHandler.class);
        scanService = new ConflictScanService(registry, conflictService, publisher, wsHandler);
    }

    /** 在线 + 有导航数据的无人机。 */
    private static DroneSnapshot flyingDrone(int sysid, double lat, double lon, double heading) {
        DroneSnapshot d = new DroneSnapshot(sysid);
        d.online = true;
        d.lat = lat;
        d.lon = lon;
        d.amslAlt = 100.0;
        d.yaw = heading;
        d.groundspeed = 10.0;
        return d;
    }

    @Test
    @DisplayName("对头航线冲突：发布 30049，HEAD-ON→COLLISION，sysid 对正确")
    void headOnConflictPublishesAlert() {
        // 同高度对头飞行：1 号机向东，2 号机向西，间距 ~300m，10 m/s 相对逼近 20 m/s
        registry.add(
                flyingDrone(1, 30.000000, 120.000000, 90),
                flyingDrone(2, 30.000000, 120.003200, 270));
        List<ConflictAvoidanceService.ConflictPair> pairs = scanService.scanOnce();

        assertThat(pairs).hasSize(1);
        ConflictAvoidanceService.ConflictPair pair = pairs.get(0);
        assertThat(pair.sysid1).isEqualTo(1);
        assertThat(pair.sysid2).isEqualTo(2);
        assertThat(pair.result.conflict).isTrue();

        ArgumentCaptor<MavlinkMessageEvent> captor = ArgumentCaptor.forClass(MavlinkMessageEvent.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        MavlinkMessageEvent event = captor.getValue();
        assertThat(event.getMsgId()).isEqualTo(ConflictAlertMsg.ID);
        assertThat(event.getSysid()).isEqualTo(1);

        ConflictAlertMsg msg = (ConflictAlertMsg) event.getMessage();
        assertThat(msg.sysId).isEqualTo(1);
        assertThat(msg.conflictingSysId).isEqualTo(2);
        assertThat(msg.conflictType).isEqualTo(ConflictType.COLLISION.ordinal());
        assertThat(msg.minDistance).isLessThan(50.0f);
        assertThat(msg.severity).isBetween(1, 4);
    }

    @Test
    @DisplayName("平行航线无冲突：不发布任何帧")
    void parallelRoutesPublishNothing() {
        // 同向平行航线，横向间隔 ~1km，永不接近
        registry.add(
                flyingDrone(3, 30.000000, 120.000000, 0),
                flyingDrone(4, 30.009000, 120.000000, 0));
        List<ConflictAvoidanceService.ConflictPair> pairs = scanService.scanOnce();

        assertThat(pairs).isEmpty();
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("离线/无导航数据的无人机不参与扫描")
    void offlineOrNavlessDronesSkipped() {
        DroneSnapshot offline = new DroneSnapshot(5);
        offline.online = false;
        DroneSnapshot noNav = new DroneSnapshot(6);
        noNav.online = true; // 无 lat/lon/yaw（NaN）
        registry.add(offline, noNav);

        assertThat(scanService.scanOnce()).isEmpty();
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("周期扫描在无 WS 客户端时跳过")
    void periodicScanSkipsWithoutWsClients() {
        when(wsHandler.connectionCount()).thenReturn(0);
        registry.add(
                flyingDrone(7, 30.000000, 120.000000, 90),
                flyingDrone(8, 30.000000, 120.003200, 270));
        scanService.scanPeriodic();

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("4D 几何类型→协议枚举映射：HEAD-ON→COLLISION，CROSSING/OVERTAKE→PATH")
    void conflictTypeMapping() {
        assertThat(ConflictScanService.conflictTypeOf("HEAD-ON")).isEqualTo(ConflictType.COLLISION);
        assertThat(ConflictScanService.conflictTypeOf("CROSSING")).isEqualTo(ConflictType.PATH);
        assertThat(ConflictScanService.conflictTypeOf("OVERTAKE")).isEqualTo(ConflictType.PATH);
    }

    @Test
    @DisplayName("severity 分档：<10s→4，<30s→3，<60s→2，≥60s→1")
    void severityMapping() {
        assertThat(ConflictScanService.severityOf(5)).isEqualTo(4);
        assertThat(ConflictScanService.severityOf(20)).isEqualTo(3);
        assertThat(ConflictScanService.severityOf(45)).isEqualTo(2);
        assertThat(ConflictScanService.severityOf(70)).isEqualTo(1);
    }

    @Test
    @DisplayName("scanAllConflicts 返回带 sysid 的冲突对，checkAllConflicts 保持旧签名")
    void scanAllConflictsCarriesSysidPairs() {
        // 对头冲突航迹（与 headOn 用例同构，直接以航迹调用）
        List<ConflictAvoidanceService.DroneTrajectory> trajs = List.of(
                new ConflictAvoidanceService.DroneTrajectory(11,
                        conflictService.predictTrajectory4D(30.0, 120.0, 100, 10, 90, 60)),
                new ConflictAvoidanceService.DroneTrajectory(12,
                        conflictService.predictTrajectory4D(30.0, 120.0032, 100, 10, 270, 60)));

        List<ConflictAvoidanceService.ConflictPair> pairs = conflictService.scanAllConflicts(trajs);
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).sysid1).isEqualTo(11);
        assertThat(pairs.get(0).sysid2).isEqualTo(12);

        List<ConflictAvoidanceService.ConflictResult> results = conflictService.checkAllConflicts(trajs);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).conflict).isTrue();
    }

    private static class StubRegistry extends DeviceRegistry {
        private final List<DroneSnapshot> drones = new java.util.ArrayList<>();

        void add(DroneSnapshot... snapshots) {
            for (DroneSnapshot s : snapshots) {
                drones.add(s);
            }
        }

        @Override
        public List<DroneSnapshot> all() {
            return new java.util.ArrayList<>(drones);
        }
    }
}
