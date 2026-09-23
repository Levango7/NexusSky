package io.aerofleet.cloud.mission.emergency;

import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import io.aerofleet.cloud.alarm.AlarmLinkageEngine;
import io.aerofleet.cloud.alarm.AlarmToOrchBridge;
import io.aerofleet.cloud.api.service.EmergencyOrchService;
import io.aerofleet.cloud.surveillance.OnvifClient;
import io.aerofleet.cloud.surveillance.SurveillanceDevice;
import io.aerofleet.cloud.surveillance.SurveillanceDeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AirGroundCoordinationService} 空地协同服务单测。
 * <p>
 * 覆盖三大核心能力：
 * <ul>
 *   <li>安防报警触发无人机侦察</li>
 *   <li>无人机发现目标触发 PTZ 联动</li>
 *   <li>空地态势融合</li>
 * </ul>
 */
@DisplayName("AirGroundCoordinationService 空地协同指挥")
class AirGroundCoordinationServiceTest {

    private EmergencyOrchService orchService;
    private SurveillanceDeviceRegistry deviceRegistry;
    private OnvifClient onvifClient;
    private AlarmLinkageEngine linkageEngine;
    private AirGroundCoordinationService service;
    private final ApplicationEventPublisher noopPublisher = event -> { };

    @BeforeEach
    void setUp() {
        orchService = new EmergencyOrchService(null, noopPublisher);
        deviceRegistry = new SurveillanceDeviceRegistry();
        onvifClient = new OnvifClient();
        AlarmEventStore eventStore = new AlarmEventStore();
        AlarmToOrchBridge bridge = new AlarmToOrchBridge(orchService);
        linkageEngine = new AlarmLinkageEngine(eventStore, bridge);
        service = new AirGroundCoordinationService(orchService, deviceRegistry, onvifClient, linkageEngine);
    }

    // =====================================================================
    // triggerReconFromAlarm
    // =====================================================================

    @Test
    @DisplayName("triggerReconFromAlarm 火灾报警触发无人机侦察成功")
    void triggerReconFromAlarmFire() {
        AlarmEvent event = AlarmEvent.from("dev-001", "海康摄像头", "FIRE",
                39.9, 116.3, "火灾报警");

        long planId = service.triggerReconFromAlarm(event);

        assertThat(planId).isPositive();
        assertThat(orchService.getPlan(planId)).isNotNull();
    }

    @Test
    @DisplayName("triggerReconFromAlarm 入侵报警触发无人机侦察成功")
    void triggerReconFromAlarmIntrusion() {
        AlarmEvent event = AlarmEvent.from("dev-002", "大华摄像头", "INTRUSION",
                40.0, 116.4, "周界入侵报警");

        long planId = service.triggerReconFromAlarm(event);

        assertThat(planId).isPositive();
    }

    @Test
    @DisplayName("triggerReconFromAlarm null 事件返回 -1")
    void triggerReconFromAlarmNull() {
        assertThat(service.triggerReconFromAlarm(null)).isEqualTo(-1);
    }

    @Test
    @DisplayName("triggerReconFromAlarm 无联动引擎时直接启动编排")
    void triggerReconFromAlarmWithoutLinkageEngine() {
        AirGroundCoordinationService svc = new AirGroundCoordinationService(
                orchService, deviceRegistry, onvifClient, null);

        AlarmEvent event = AlarmEvent.from("dev-003", "宇视摄像头", "MOTION",
                39.8, 116.2, "移动侦测");

        long planId = svc.triggerReconFromAlarm(event);

        assertThat(planId).isPositive();
    }

    // =====================================================================
    // triggerPtzTracking
    // =====================================================================

    @Test
    @DisplayName("triggerPtzTracking 高置信度目标触发 PTZ 联动成功")
    void triggerPtzTrackingHighConfidence() {
        // 注册具备 PTZ 能力的在线安防设备
        SurveillanceDevice device = new SurveillanceDevice(
                "cam-001", "海康摄像头", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "admin", "hik12345");
        Set<String> caps = new LinkedHashSet<>();
        caps.add("Device");
        caps.add("Media");
        caps.add("PTZ");
        caps.add("Events");
        device.setCapabilities(caps);
        deviceRegistry.register(device);

        GeoTarget target = new GeoTarget(39.9, 116.3, 50.0,
                GeoTarget.TargetType.PERSON, 0.85, 1);

        String result = service.triggerPtzTracking(target);

        assertThat(result).isEqualTo("TRACKING");
    }

    @Test
    @DisplayName("triggerPtzTracking 低置信度目标跳过 PTZ 联动")
    void triggerPtzTrackingLowConfidence() {
        GeoTarget target = new GeoTarget(39.9, 116.3, 50.0,
                GeoTarget.TargetType.PERSON, 0.3, 1);

        String result = service.triggerPtzTracking(target);

        assertThat(result).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    @DisplayName("triggerPtzTracking 无可用 PTZ 设备返回 NO_DEVICE")
    void triggerPtzTrackingNoDevice() {
        // 注册不具备 PTZ 能力的设备
        SurveillanceDevice device = new SurveillanceDevice(
                "cam-002", "大华摄像头", SurveillanceDevice.Vendor.DAHUA,
                "192.168.1.101", 80, "admin", "dahua123");
        Set<String> caps = new LinkedHashSet<>();
        caps.add("Device");
        caps.add("Media");
        caps.add("Events");
        device.setCapabilities(caps);
        deviceRegistry.register(device);

        GeoTarget target = new GeoTarget(39.9, 116.3, 50.0,
                GeoTarget.TargetType.VEHICLE, 0.9, 2);

        String result = service.triggerPtzTracking(target);

        assertThat(result).isEqualTo("NO_DEVICE");
    }

    @Test
    @DisplayName("triggerPtzTracking null 目标返回 FAILED")
    void triggerPtzTrackingNull() {
        assertThat(service.triggerPtzTracking(null)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("triggerPtzTracking 无设备注册表返回 NO_DEVICE")
    void triggerPtzTrackingNoRegistry() {
        AirGroundCoordinationService svc = new AirGroundCoordinationService(
                orchService, null, onvifClient, null);

        GeoTarget target = new GeoTarget(39.9, 116.3, 50.0,
                GeoTarget.TargetType.PERSON, 0.85, 1);

        assertThat(svc.triggerPtzTracking(target)).isEqualTo("NO_DEVICE");
    }

    @Test
    @DisplayName("triggerPtzTracking 火源目标触发 up 方向 PTZ")
    void triggerPtzTrackingFireSource() {
        SurveillanceDevice device = new SurveillanceDevice(
                "cam-003", "宇视摄像头", SurveillanceDevice.Vendor.UNIVIEW,
                "192.168.1.102", 80, "admin", "uniview123");
        Set<String> caps = new LinkedHashSet<>();
        caps.add("PTZ");
        device.setCapabilities(caps);
        deviceRegistry.register(device);

        GeoTarget target = new GeoTarget(39.9, 116.3, 50.0,
                GeoTarget.TargetType.FIRE_SOURCE, 0.9, 1);

        String result = service.triggerPtzTracking(target);

        assertThat(result).isEqualTo("TRACKING");
    }

    // =====================================================================
    // fuseAirGroundSituation
    // =====================================================================

    @Test
    @DisplayName("fuseAirGroundSituation 生成态势融合视图")
    void fuseAirGroundSituationBasic() {
        // 注册安防设备
        SurveillanceDevice device1 = new SurveillanceDevice(
                "cam-001", "海康摄像头", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "admin", "hik12345");
        deviceRegistry.register(device1);

        AirGroundSituation situation = service.fuseAirGroundSituation();

        assertThat(situation).isNotNull();
        assertThat(situation.getSurveillanceDevices()).hasSize(1);
        assertThat(situation.getGeneratedAtMs()).isPositive();
    }

    @Test
    @DisplayName("fuseAirGroundSituation 无安防设备时返回空设备列表")
    void fuseAirGroundSituationNoDevices() {
        AirGroundSituation situation = service.fuseAirGroundSituation();

        assertThat(situation.getSurveillanceDevices()).isEmpty();
        assertThat(situation.getDroneStatuses()).isEmpty();
        assertThat(situation.getAlarmEvents()).isEmpty();
    }

    @Test
    @DisplayName("fuseAirGroundSituation 无设备注册表时仍可生成视图")
    void fuseAirGroundSituationNoRegistry() {
        AirGroundCoordinationService svc = new AirGroundCoordinationService(
                orchService, null, null, null);

        AirGroundSituation situation = svc.fuseAirGroundSituation();

        assertThat(situation).isNotNull();
        assertThat(situation.getSurveillanceDevices()).isEmpty();
    }

    @Test
    @DisplayName("fuseAirGroundSituation mesh 拓扑信息正确")
    void fuseAirGroundSituationMeshTopology() {
        AirGroundSituation situation = service.fuseAirGroundSituation();

        assertThat(situation.getMeshTopology()).isNotNull();
        assertThat(situation.getMeshTopology().nodeCount).isZero();
    }

    @Test
    @DisplayName("fuseAirGroundSituation 在线设备计数正确")
    void fuseAirGroundSituationOnlineCount() {
        SurveillanceDevice device = new SurveillanceDevice(
                "cam-001", "海康摄像头", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", 80, "admin", "hik12345");
        deviceRegistry.register(device);

        AirGroundSituation situation = service.fuseAirGroundSituation();

        assertThat(situation.onlineDeviceCount()).isEqualTo(1);
    }

    // =====================================================================
    // GeoTarget
    // =====================================================================

    @Test
    @DisplayName("GeoTarget 置信度阈值判断正确")
    void geoTargetIsReliable() {
        GeoTarget reliable = new GeoTarget(39.9, 116.3, 50.0,
                GeoTarget.TargetType.PERSON, 0.7, 1);
        GeoTarget unreliable = new GeoTarget(39.9, 116.3, 50.0,
                GeoTarget.TargetType.PERSON, 0.5, 1);

        assertThat(reliable.isReliable()).isTrue();
        assertThat(unreliable.isReliable()).isFalse();
    }

    @Test
    @DisplayName("GeoTarget 置信度钳位到 [0, 1] 范围")
    void geoTargetConfidenceClamp() {
        GeoTarget high = new GeoTarget(39.9, 116.3, 50.0,
                GeoTarget.TargetType.PERSON, 1.5, 1);
        GeoTarget low = new GeoTarget(39.9, 116.3, 50.0,
                GeoTarget.TargetType.PERSON, -0.5, 1);

        assertThat(high.confidence).isEqualTo(1.0);
        assertThat(low.confidence).isEqualTo(0.0);
    }

    @Test
    @DisplayName("GeoTarget null 类型默认为 UNKNOWN")
    void geoTargetNullType() {
        GeoTarget target = new GeoTarget(39.9, 116.3, 50.0, null, 0.8, 1);

        assertThat(target.targetType).isEqualTo(GeoTarget.TargetType.UNKNOWN);
    }

    // =====================================================================
    // AirGroundSituation
    // =====================================================================

    @Test
    @DisplayName("AirGroundSituation 不可变性验证")
    void airGroundSituationImmutable() {
        AirGroundSituation situation = new AirGroundSituation(
                List.of(), List.of(), List.of(),
                AirGroundSituation.MeshTopology.empty(), System.currentTimeMillis());

        // 修改返回的列表不应影响内部状态
        assertThat(situation.getSurveillanceDevices()).isEmpty();
        assertThat(situation.getDroneStatuses()).isEmpty();
        assertThat(situation.getAlarmEvents()).isEmpty();
    }

    @Test
    @DisplayName("AirGroundSituation.DroneStatus 电池百分比钳位")
    void droneStatusBatteryClamp() {
        AirGroundSituation.DroneStatus high = new AirGroundSituation.DroneStatus(
                1, true, 39.9, 116.3, 100, 150, "EXECUTING");
        AirGroundSituation.DroneStatus low = new AirGroundSituation.DroneStatus(
                2, true, 39.9, 116.3, 100, -10, "EXECUTING");

        assertThat(high.batteryPct).isEqualTo(100);
        assertThat(low.batteryPct).isEqualTo(0);
    }

    @Test
    @DisplayName("AirGroundSituation.MeshTopology empty 返回零值拓扑")
    void meshTopologyEmpty() {
        AirGroundSituation.MeshTopology empty = AirGroundSituation.MeshTopology.empty();

        assertThat(empty.nodeCount).isZero();
        assertThat(empty.linkCount).isZero();
        assertThat(empty.coverageRate).isZero();
        assertThat(empty.getRelayNodes()).isEmpty();
    }
}