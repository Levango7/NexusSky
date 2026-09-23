package io.aerofleet.cloud.mission.emergency;

import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.alarm.AlarmLinkageEngine;
import io.aerofleet.cloud.alarm.AlarmLinkageRule;
import io.aerofleet.cloud.api.service.EmergencyOrchService;
import io.aerofleet.cloud.surveillance.OnvifClient;
import io.aerofleet.cloud.surveillance.SurveillanceDevice;
import io.aerofleet.cloud.surveillance.SurveillanceDeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 空地协同指挥服务。
 * <p>
 * 整合安防报警与无人机侦察，实现空地协同指挥三大核心能力：
 * <ol>
 *   <li>{@link #triggerReconFromAlarm(AlarmEvent)} — 安防报警触发无人机自动侦察</li>
 *   <li>{@link #triggerPtzTracking(GeoTarget)} — 无人机发现目标触发安防 PTZ 联动</li>
 *   <li>{@link #fuseAirGroundSituation()} — 空地态势融合（统一态势感知）</li>
 * </ol>
 * <p>
 * 协同流程：
 * <pre>
 * 安防设备检测异常 → AlarmEvent → triggerReconFromAlarm → EmergencyOrchService 启动无人机侦察
 * 无人机航拍发现目标 → GeoTarget → triggerPtzTracking → OnvifClient PTZ 转向联动
 * 安防视频流 + 无人机航拍 + 地图 → fuseAirGroundSituation → 统一态势感知视图
 * </pre>
 * <p>
 * 依赖注入采用 {@code @Autowired(required=false)} + null 检查模式，
 * 确保在安防/无人机子系统未部署时仍可降级运行。
 */
@Service
public class AirGroundCoordinationService {

    private static final Logger log = LoggerFactory.getLogger(AirGroundCoordinationService.class);

    /** 经纬度（度）→ 1E7 度定点整数（与 AlarmToOrchBridge/OneClickEmergencyResponse 一致）。 */
    private static final double LATLON_SCALE = 1e7;

    /** PTZ 联动搜索半径（米）：查找目标位置附近的安防设备。 */
    private static final double PTZ_SEARCH_RADIUS_M = 500.0;

    /** 地球纬度方向每度对应的米数（近似）。 */
    private static final double M_PER_DEG_LAT = 111_320.0;

    /** 应急编排服务。 */
    private final EmergencyOrchService orchService;
    /** 安防设备注册表（可选依赖）。 */
    private final SurveillanceDeviceRegistry deviceRegistry;
    /** ONVIF 客户端（可选依赖，用于 PTZ 控制）。 */
    private final OnvifClient onvifClient;
    /** 报警联动引擎（可选依赖，用于规则匹配）。 */
    private final AlarmLinkageEngine linkageEngine;

    /**
     * 构造空地协同服务。
     *
     * @param orchService     应急编排服务（必需）
     * @param deviceRegistry  安防设备注册表（可选）
     * @param onvifClient     ONVIF 客户端（可选，PTZ 控制需要）
     * @param linkageEngine   报警联动引擎（可选，规则匹配需要）
     */
    @Autowired
    public AirGroundCoordinationService(EmergencyOrchService orchService,
                                        @Autowired(required = false) SurveillanceDeviceRegistry deviceRegistry,
                                        @Autowired(required = false) OnvifClient onvifClient,
                                        @Autowired(required = false) AlarmLinkageEngine linkageEngine) {
        this.orchService = orchService;
        this.deviceRegistry = deviceRegistry;
        this.onvifClient = onvifClient;
        this.linkageEngine = linkageEngine;
    }

    // =====================================================================
    // 核心能力 1：安防报警 → 无人机自动侦察
    // =====================================================================

    /**
     * 安防报警触发无人机自动侦察。
     * <p>
     * 流程：
     * <ol>
     *   <li>根据报警事件类型映射应急场景</li>
     *   <li>将报警位置经纬度转换为 1E7 度定点整数</li>
     *   <li>调用 {@link EmergencyOrchService#start} 启动无人机编排</li>
     *   <li>返回编排计划 ID（planId）</li>
     * </ol>
     * <p>
     * 若报警联动引擎可用，优先通过联动规则匹配后委托 {@link AlarmLinkageEngine} 处理，
     * 否则直接调用编排服务启动侦察任务。
     *
     * @param alarmEvent 安防报警事件
     * @return 编排计划 ID；若启动失败返回 -1
     */
    public long triggerReconFromAlarm(AlarmEvent alarmEvent) {
        if (alarmEvent == null) {
            log.warn("triggerReconFromAlarm: alarmEvent is null");
            return -1;
        }

        log.info("空地协同：安防报警触发无人机侦察 eventId={} type={} severity={} device={}",
                alarmEvent.getId(), alarmEvent.getEventType(), alarmEvent.getSeverity(),
                alarmEvent.getSourceDeviceId());

        // 优先通过联动引擎处理（匹配规则后执行联动动作）
        if (linkageEngine != null) {
            try {
                AlarmLinkageEngine.ProcessResult result = linkageEngine.processEvent(alarmEvent);
                log.info("空地协同：联动引擎处理完成 eventId={} matched={}",
                        alarmEvent.getId(), result.getMatchedCount());
                // 若有 DEPLOY_DRONE 类型的执行结果，返回其 planId
                for (AlarmLinkageEngine.ActionExecution exec : result.getExecutions()) {
                    if ("DEPLOY_DRONE".equals(exec.getActionType()) && exec.getPlanId() > 0) {
                        return exec.getPlanId();
                    }
                }
                // 联动引擎处理了但未启动无人机编排，继续走直接启动路径
            } catch (Exception e) {
                log.warn("空地协同：联动引擎处理失败，降级为直接启动编排 eventId={} error={}",
                        alarmEvent.getId(), e.getMessage());
                // 降级：继续走直接启动路径
            }
        }

        // 直接调用编排服务启动侦察任务
        int scenarioType = mapScenarioType(alarmEvent.getEventType());
        int centerLat = toE7(clamp(alarmEvent.getLat(), -90.0, 90.0));
        int centerLon = toE7(clamp(alarmEvent.getLon(), -180.0, 180.0));
        int radius = 1000; // 默认侦察半径 1km
        List<Integer> droneIds = List.of(1, 2); // 默认 2 架无人机

        try {
            long planId = orchService.start(scenarioType, centerLat, centerLon, radius, droneIds);
            log.info("空地协同：无人机侦察已启动 eventId={} planId={} scenario={}",
                    alarmEvent.getId(), planId, scenarioType);
            return planId;
        } catch (Exception e) {
            log.error("空地协同：无人机侦察启动失败 eventId={}", alarmEvent.getId(), e);
            return -1;
        }
    }

    // =====================================================================
    // 核心能力 2：无人机发现目标 → 安防 PTZ 联动
    // =====================================================================

    /**
     * 无人机发现目标触发安防 PTZ 联动。
     * <p>
     * 流程：
     * <ol>
     *   <li>验证目标置信度是否达到联动阈值（{@link GeoTarget#isReliable()}）</li>
     *   <li>查找目标位置附近最近的安防设备（具备 PTZ 能力）</li>
     *   <li>通过 {@link OnvifClient} 控制安防设备 PTZ 转向目标方向</li>
     * </ol>
     * <p>
     * PTZ 转向策略：根据安防设备与目标的相对方位，选择 up/down/left/right 命令。
     * 简化实现：仅发送方向命令，真实实现需持续跟踪。
     *
     * @param geoTarget 无人机侦察发现的目标
     * @return PTZ 联动结果（"TRACKING"/"NO_DEVICE"/"LOW_CONFIDENCE"/"FAILED"）
     */
    public String triggerPtzTracking(GeoTarget geoTarget) {
        if (geoTarget == null) {
            log.warn("triggerPtzTracking: geoTarget is null");
            return "FAILED";
        }

        // 置信度检查
        if (!geoTarget.isReliable()) {
            log.info("空地协同：目标置信度不足，跳过 PTZ 联动 target={} confidence={}",
                    geoTarget, geoTarget.confidence);
            return "LOW_CONFIDENCE";
        }

        log.info("空地协同：无人机目标触发 PTZ 联动 target={} sysid={}",
                geoTarget, geoTarget.sourceSysid);

        // 查找最近的安防设备
        if (deviceRegistry == null) {
            log.warn("空地协同：安防设备注册表未注入，无法执行 PTZ 联动");
            return "NO_DEVICE";
        }

        SurveillanceDevice nearestDevice = findNearestPtzDevice(geoTarget.lat, geoTarget.lon);
        if (nearestDevice == null) {
            log.info("空地协同：目标位置附近无可用的 PTZ 安防设备");
            return "NO_DEVICE";
        }

        // PTZ 控制
        if (onvifClient == null) {
            log.warn("空地协同：ONVIF 客户端未注入，无法执行 PTZ 控制");
            return "NO_DEVICE";
        }

        try {
            String ptzCmd = computePtzDirection(nearestDevice, geoTarget);
            String result = onvifClient.ptzControl(
                    nearestDevice.ip, nearestDevice.port,
                    nearestDevice.username, nearestDevice.password, ptzCmd);
            log.info("空地协同：PTZ 联动执行成功 device={} cmd={} result={}",
                    nearestDevice.id, ptzCmd, result);
            return "TRACKING";
        } catch (Exception e) {
            log.error("空地协同：PTZ 联动执行失败 device={}", nearestDevice.id, e);
            return "FAILED";
        }
    }

    // =====================================================================
    // 核心能力 3：空地态势融合
    // =====================================================================

    /**
     * 空地态势融合：生成统一态势感知视图。
     * <p>
     * 融合三类数据源：
     * <ul>
     *   <li>安防设备状态（来自 {@link SurveillanceDeviceRegistry}）</li>
     *   <li>无人机状态（来自 {@link EmergencyOrchService} 的编排计划）</li>
     *   <li>报警事件（来自 {@link AlarmLinkageEngine} 的联动日志）</li>
     * </ul>
     * <p>
     * 若某个数据源不可用（依赖未注入），对应列表为空，不影响其他数据源的融合。
     *
     * @return 空地态势融合视图
     */
    public AirGroundSituation fuseAirGroundSituation() {
        log.debug("空地协同：生成态势融合视图");

        // 安防设备状态
        List<SurveillanceDevice> devices = deviceRegistry == null
                ? List.of()
                : deviceRegistry.listDevices();

        // 无人机状态（从编排计划中提取）
        List<AirGroundSituation.DroneStatus> droneStatuses = collectDroneStatuses();

        // 报警事件（从联动引擎日志中提取）
        List<AlarmEvent> alarmEvents = collectAlarmEvents();

        // mesh 拓扑（简化：基于在线无人机数量估算）
        AirGroundSituation.MeshTopology mesh = buildMeshTopology(droneStatuses);

        AirGroundSituation situation = new AirGroundSituation(
                devices, droneStatuses, alarmEvents, mesh, System.currentTimeMillis());

        log.info("空地协同：态势融合完成 devices={} drones={} alarms={} mesh={}",
                devices.size(), droneStatuses.size(), alarmEvents.size(), mesh);
        return situation;
    }

    // =====================================================================
    // 内部辅助
    // =====================================================================

    /**
     * 查找目标位置附近最近的具备 PTZ 能力的安防设备。
     * <p>
     * 搜索半径 {@link #PTZ_SEARCH_RADIUS_M} 米，在注册表中遍历所有设备，
     * 筛选在线且具备 PTZ 能力的设备，按距离排序返回最近的。
     *
     * @param lat 目标纬度
     * @param lon 目标经度
     * @return 最近的 PTZ 设备；若无可用的返回 null
     */
    private SurveillanceDevice findNearestPtzDevice(double lat, double lon) {
        if (deviceRegistry == null) {
            return null;
        }

        List<SurveillanceDevice> allDevices = deviceRegistry.listDevices();
        SurveillanceDevice nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (SurveillanceDevice device : allDevices) {
            // 仅选择在线且具备 PTZ 能力的设备
            if (device.status != SurveillanceDevice.Status.ONLINE) {
                continue;
            }
            if (!device.getCapabilities().contains("PTZ")) {
                continue;
            }
            // 安防设备位置未知时跳过（无经纬度信息）
            // SurveillanceDevice 无 lat/lon 字段，此处简化：所有 PTZ 设备均候选
            // 真实实现需从设备注册信息中获取经纬度
            double distance = estimateDistance(lat, lon, device);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = device;
            }
        }

        return nearest;
    }

    /**
     * 估算目标与安防设备的距离（简化实现）。
     * <p>
     * 由于 {@link SurveillanceDevice} 不含经纬度字段，此处返回固定值 0，
     * 表示所有设备等距。真实实现应从设备注册信息或数据库中获取设备位置。
     *
     * @param targetLat 目标纬度
     * @param targetLon 目标经度
     * @param device    安防设备
     * @return 估算距离（米）
     */
    private double estimateDistance(double targetLat, double targetLon, SurveillanceDevice device) {
        // 简化实现：SurveillanceDevice 无经纬度，返回 0 使第一个在线 PTZ 设备被选中
        // 生产环境应从设备实体或外部配置中获取设备部署位置
        return 0;
    }

    /**
     * 计算安防设备 PTZ 转向方向。
     * <p>
     * 简化策略：根据目标类型选择默认转向方向。
     * 真实实现需根据设备与目标的相对方位角计算精确的 PTZ 方向。
     *
     * @param device    安防设备
     * @param geoTarget 地理目标
     * @return PTZ 命令（up/down/left/right/zoomIn）
     */
    private String computePtzDirection(SurveillanceDevice device, GeoTarget geoTarget) {
        // 简化实现：根据目标类型选择 PTZ 方向
        // 真实实现需根据设备朝向与目标方位的差值计算
        return switch (geoTarget.targetType) {
            case PERSON -> "zoomIn";   // 人员目标：放大查看
            case VEHICLE -> "zoomIn";  // 车辆目标：放大查看
            case FIRE_SOURCE -> "up";  // 火源目标：上仰查看烟雾
            case STRUCTURE -> "left";  // 建筑目标：左转扫描
            case UNKNOWN -> "right";   // 未知目标：右转扫描
        };
    }

    /**
     * 从编排服务中收集无人机状态信息。
     * <p>
     * 简化实现：当前 EmergencyOrchService 不直接提供无人机状态列表，
     * 此处返回空列表。真实实现应从遥测服务或编排计划中提取无人机实时状态。
     *
     * @return 无人机状态列表
     */
    private List<AirGroundSituation.DroneStatus> collectDroneStatuses() {
        // 简化实现：EmergencyOrchService 的 plans map 不直接暴露无人机状态
        // 生产环境应从 TelemetryIngestService 或专用无人机状态服务获取
        return List.of();
    }

    /**
     * 从联动引擎中收集报警事件。
     * <p>
     * 若联动引擎可用，从联动日志中提取关联的报警事件 ID。
     * 简化实现：返回空列表，真实实现应从 AlarmEventStore 中查询。
     *
     * @return 报警事件列表
     */
    private List<AlarmEvent> collectAlarmEvents() {
        if (linkageEngine == null) {
            return List.of();
        }
        // 简化实现：联动日志不直接包含 AlarmEvent 对象
        // 生产环境应从 AlarmEventStore 中查询最近事件
        return List.of();
    }

    /**
     * 基于无人机状态构建 mesh 拓扑信息。
     * <p>
     * 简化估算：节点数 = 在线无人机数，连接数 = 节点数 - 1（链式），
     * 覆盖率 = 在线节点数 / 总节点数 * 100。
     *
     * @param droneStatuses 无人机状态列表
     * @return mesh 拓扑信息
     */
    private AirGroundSituation.MeshTopology buildMeshTopology(
            List<AirGroundSituation.DroneStatus> droneStatuses) {
        int onlineCount = (int) droneStatuses.stream()
                .filter(AirGroundSituation.DroneStatus::isOnline)
                .count();
        int linkCount = Math.max(0, onlineCount - 1);
        int coverageRate = droneStatuses.isEmpty() ? 0
                : (int) ((double) onlineCount / droneStatuses.size() * 100);

        List<Integer> relayNodes = new ArrayList<>();
        for (AirGroundSituation.DroneStatus d : droneStatuses) {
            if (d.isOnline()) {
                relayNodes.add(d.sysid);
            }
        }

        return new AirGroundSituation.MeshTopology(onlineCount, linkCount, coverageRate, relayNodes);
    }

    /** 事件类型 → EmergencyOrchService 场景类型映射。 */
    private static int mapScenarioType(AlarmEvent.EventType eventType) {
        return switch (eventType) {
            case FIRE -> 2;       // 火灾
            case INTRUSION -> 3;  // 自定义（安防入侵）
            case MOTION -> 3;     // 自定义（移动侦测）
            case DOOR -> 3;       // 自定义（门禁异常）
            case CUSTOM -> 3;     // 自定义
        };
    }

    /** 经纬度（度）→ 1E7 度定点整数。 */
    private static int toE7(double deg) {
        long scaled = Math.round(deg * LATLON_SCALE);
        if (scaled > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (scaled < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) scaled;
    }

    /** 将值钳位到 [min, max] 范围。 */
    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}