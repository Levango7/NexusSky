package io.aerofleet.cloud.autodispatch;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.tracking.FlightTrackStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AutoDispatchService} 单元测试（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 覆盖选机策略（距离最近、电量充足、未执行任务）、无可用无人机时的 NO_DRONE 状态、
 * 出警配置边界、出警记录与历史查询、中止出警等。
 */
@DisplayName("AutoDispatchService 自动出警服务 (P0-1)")
class AutoDispatchServiceTest {

    private DeviceRegistry registry;
    private FlightTrackStore trackStore;
    private AutoDispatchConfig config;
    private AutoDispatchService service;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        trackStore = new FlightTrackStore();
        config = new AutoDispatchConfig();
        config.setEnabled(true);
        service = new AutoDispatchService(registry, trackStore, config);
    }

    /**
     * 注册一架在线无人机：位置 (lat, lon)，电量 batteryPct，未 armed。
     */
    private DroneSnapshot registerDrone(int sysid, double lat, double lon, int batteryPct) {
        DroneSnapshot drone = registry.registerIfAbsent(sysid);
        drone.online = true;
        drone.lat = lat;
        drone.lon = lon;
        drone.battery = batteryPct;
        drone.armed = false;
        return drone;
    }

    @Test
    @DisplayName("选机策略：距离最近的无人机优先派遣")
    void dispatchSelectsNearestDrone() {
        // 报警点 (39.9, 116.3)
        registerDrone(1, 39.91, 116.31, 80);  // 较远
        registerDrone(2, 39.901, 116.301, 90); // 较近
        registerDrone(3, 39.905, 116.305, 70); // 中等距离

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);
        assertThat(result.getDispatchedDrones()).hasSize(1);
        assertThat(result.getDispatchedDrones().get(0).getSysid()).isEqualTo(2); // 最近
        assertThat(result.getDispatchedDrones().get(0).isTaskAssigned()).isTrue();
        assertThat(result.getDispatchedDrones().get(0).getEstimatedArrivalSec()).isGreaterThan(0);
    }

    @Test
    @DisplayName("选机策略：电量低于阈值的无人机不参与选机")
    void dispatchSkipsLowBatteryDrone() {
        config.setMinBatteryPct(30);
        registerDrone(1, 39.901, 116.301, 25);  // 电量不足
        registerDrone(2, 39.905, 116.305, 80);  // 电量充足但较远

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);
        assertThat(result.getDispatchedDrones()).hasSize(1);
        assertThat(result.getDispatchedDrones().get(0).getSysid()).isEqualTo(2);
    }

    @Test
    @DisplayName("选机策略：已 armed（执行任务中）的无人机不参与选机")
    void dispatchSkipsArmedDrone() {
        DroneSnapshot d1 = registerDrone(1, 39.901, 116.301, 80);
        d1.armed = true;  // 执行任务中
        registerDrone(2, 39.905, 116.305, 80);

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);
        assertThat(result.getDispatchedDrones().get(0).getSysid()).isEqualTo(2);
    }

    @Test
    @DisplayName("选机策略：距离超过 maxDispatchDistanceM 的无人机不参与选机")
    void dispatchSkipsFarDrone() {
        config.setMaxDispatchDistanceM(1000);  // 1km
        registerDrone(1, 39.901, 116.301, 80);  // ~150m，在范围内
        registerDrone(2, 40.0, 116.4, 80);      // ~14km，超出范围

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);
        assertThat(result.getDispatchedDrones().get(0).getSysid()).isEqualTo(1);
    }

    @Test
    @DisplayName("选机策略：离线无人机不参与选机")
    void dispatchSkipsOfflineDrone() {
        DroneSnapshot d1 = registerDrone(1, 39.901, 116.301, 80);
        d1.online = false;  // 离线
        registerDrone(2, 39.905, 116.305, 80);

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);
        assertThat(result.getDispatchedDrones().get(0).getSysid()).isEqualTo(2);
    }

    @Test
    @DisplayName("无可用无人机时返回 NO_DRONE 状态")
    void noAvailableDroneReturnsNoDrone() {
        // 无任何无人机注册
        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.NO_DRONE);
        assertThat(result.getDispatchedDrones()).isEmpty();
        assertThat(result.getMessage()).isEqualTo("no available drone");
    }

    @Test
    @DisplayName("所有候选无人机都不满足条件时返回 NO_DRONE")
    void allCandidatesFilteredReturnsNoDrone() {
        config.setMinBatteryPct(50);
        registerDrone(1, 39.901, 116.301, 30);  // 电量不足
        registerDrone(2, 39.905, 116.305, 20);  // 电量不足

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.NO_DRONE);
    }

    @Test
    @DisplayName("请求无人机数量超过可用数量时返回 PARTIAL")
    void partialDispatchWhenRequestedExceedsAvailable() {
        registerDrone(1, 39.901, 116.301, 80);
        registerDrone(2, 39.905, 116.305, 90);

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 5);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.PARTIAL);
        assertThat(result.getDispatchedDrones()).hasSize(2);
        assertThat(result.getMessage()).contains("only 2 of 5");
    }

    @Test
    @DisplayName("请求多架无人机时按距离升序选取")
    void dispatchMultipleDronesByDistance() {
        registerDrone(1, 39.91, 116.31, 80);   // 最远
        registerDrone(2, 39.901, 116.301, 90); // 最近
        registerDrone(3, 39.905, 116.305, 70); // 中等

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 2);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);
        assertThat(result.getDispatchedDrones()).hasSize(2);
        assertThat(result.getDispatchedDrones().get(0).getSysid()).isEqualTo(2); // 最近
        assertThat(result.getDispatchedDrones().get(1).getSysid()).isEqualTo(3); // 中等
    }

    @Test
    @DisplayName("自动出警未启用时返回 NO_DRONE 状态")
    void dispatchDisabledReturnsNoDrone() {
        config.setEnabled(false);
        registerDrone(1, 39.901, 116.301, 80);

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.NO_DRONE);
        assertThat(result.getMessage()).isEqualTo("auto dispatch disabled");
    }

    @Test
    @DisplayName("droneCount <= 0 时使用配置默认值")
    void dispatchWithZeroDroneCountUsesDefault() {
        config.setDefaultDroneCount(2);
        registerDrone(1, 39.901, 116.301, 80);
        registerDrone(2, 39.905, 116.305, 90);

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 0);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);
        assertThat(result.getDispatchedDrones()).hasSize(2);
    }

    @Test
    @DisplayName("出警记录存入历史与活跃列表")
    void dispatchRecordStoredInHistoryAndActive() {
        registerDrone(1, 39.901, 116.301, 80);

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(service.historyCount()).isEqualTo(1);
        assertThat(service.activeCount()).isEqualTo(1);
        assertThat(service.getRecord(result.getDispatchId())).isNotNull();
    }

    @Test
    @DisplayName("NO_DRONE 结果不进入活跃列表但进入历史")
    void noDroneResultNotInActiveButInHistory() {
        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.NO_DRONE);
        assertThat(service.activeCount()).isEqualTo(0);
        assertThat(service.historyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("中止出警任务：从活跃列表移除并标记 ABORTED")
    void abortDispatchRemovesFromActiveAndMarksAborted() {
        registerDrone(1, 39.901, 116.301, 80);
        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);
        String dispatchId = result.getDispatchId();

        DispatchRecord record = service.abortDispatch(dispatchId);

        assertThat(record).isNotNull();
        assertThat(record.getStatus()).isEqualTo("ABORTED");
        assertThat(record.getAbortTime()).isGreaterThan(0);
        assertThat(service.activeCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("中止不存在的出警任务返回 null")
    void abortNonExistentReturnsNull() {
        assertThat(service.abortDispatch("nonexistent")).isNull();
    }

    @Test
    @DisplayName("getHistory 返回历史记录列表")
    void getHistoryReturnsRecords() {
        registerDrone(1, 39.901, 116.301, 80);
        service.dispatchDrone(39.9, 116.3, "alarm-1", 1);
        service.dispatchDrone(39.9, 116.3, "alarm-2", 1);

        List<DispatchRecord> history = service.getHistory(10);
        assertThat(history).hasSize(2);
    }

    @Test
    @DisplayName("getActiveRecords 返回进行中的出警任务")
    void getActiveRecordsReturnsActive() {
        registerDrone(1, 39.901, 116.301, 80);
        service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        List<DispatchRecord> active = service.getActiveRecords();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).isActive()).isTrue();
    }

    @Test
    @DisplayName("配置边界：minBatteryPct=100 时仅满电无人机参与选机")
    void configBoundaryFullBatteryRequired() {
        config.setMinBatteryPct(100);
        registerDrone(1, 39.901, 116.301, 99);   // 不满足
        registerDrone(2, 39.905, 116.305, 100);  // 满足

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);
        assertThat(result.getDispatchedDrones().get(0).getSysid()).isEqualTo(2);
    }

    @Test
    @DisplayName("配置边界：maxDispatchDistanceM=0 时所有无人机被过滤")
    void configBoundaryZeroMaxDistance() {
        config.setMaxDispatchDistanceM(0);
        registerDrone(1, 39.901, 116.301, 80);

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.NO_DRONE);
    }

    @Test
    @DisplayName("updateConfig 更新配置字段")
    void updateConfigChangesFields() {
        AutoDispatchConfig updated = service.updateConfig(true, 50, 5000, 3, 80, 600);

        assertThat(updated.isEnabled()).isTrue();
        assertThat(updated.getMinBatteryPct()).isEqualTo(50);
        assertThat(updated.getMaxDispatchDistanceM()).isEqualTo(5000);
        assertThat(updated.getDefaultDroneCount()).isEqualTo(3);
        assertThat(updated.getHoverAltitudeM()).isEqualTo(80);
        assertThat(updated.getHoverDurationSec()).isEqualTo(600);
    }

    @Test
    @DisplayName("updateConfig 忽略非正数字段值")
    void updateConfigIgnoresNonPositiveValues() {
        service.updateConfig(true, 50, 5000, 3, 80, 600);
        AutoDispatchConfig updated = service.updateConfig(false, 0, -1, 0, 0, 0);

        // 非正数不覆盖
        assertThat(updated.getMinBatteryPct()).isEqualTo(50);
        assertThat(updated.getMaxDispatchDistanceM()).isEqualTo(5000);
        assertThat(updated.getDefaultDroneCount()).isEqualTo(3);
        assertThat(updated.getHoverAltitudeM()).isEqualTo(80);
        assertThat(updated.getHoverDurationSec()).isEqualTo(600);
        // enabled 始终更新
        assertThat(updated.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("位置无效（NaN）的无人机不参与选机")
    void dispatchSkipsInvalidPositionDrone() {
        DroneSnapshot d1 = registerDrone(1, 39.901, 116.301, 80);
        d1.lat = Double.NaN;  // 位置无效
        registerDrone(2, 39.905, 116.305, 80);

        DispatchResult result = service.dispatchDrone(39.9, 116.3, "alarm-1", 1);

        assertThat(result.getStatus()).isEqualTo(DispatchResult.Status.SUCCESS);
        assertThat(result.getDispatchedDrones().get(0).getSysid()).isEqualTo(2);
    }
}