package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.api.CellTowerTopologyService;
import io.aerofleet.cloud.api.EmergencyOrchService;
import io.aerofleet.cloud.api.HardwareDataController;
import io.aerofleet.cloud.api.MeshTopologyService;
import io.aerofleet.cloud.api.SatLinkMonitorService;
import io.aerofleet.cloud.api.TerrainMapService;
import io.aerofleet.cloud.telemetry.AlertBus;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.cloud.vision.RadarController;
import io.aerofleet.cloud.vision.RotorController;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.Attitude;
import io.aerofleet.mavlink.messages.CellHandoverMsg;
import io.aerofleet.mavlink.messages.CellTowerStatusMsg;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.FlightRestrictionMsg;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.GpsRawInt;
import io.aerofleet.mavlink.messages.GroundTerminalRegisterMsg;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.ImuDataMsg;
import io.aerofleet.mavlink.messages.LidarDataMsg;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MeshHeartbeatMsg;
import io.aerofleet.mavlink.messages.MeshNeighborTableMsg;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionCurrent;
import io.aerofleet.mavlink.messages.MissionItemInt;
import io.aerofleet.mavlink.messages.MissionRequest;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import io.aerofleet.mavlink.messages.RadarScanMsg;
import io.aerofleet.mavlink.messages.RadarTargetMsg;
import io.aerofleet.mavlink.messages.RadioStatus;
import io.aerofleet.mavlink.messages.RotorTelemetryMsg;
import io.aerofleet.mavlink.messages.SatLinkStatusMsg;
import io.aerofleet.mavlink.messages.SatPassScheduleMsg;
import io.aerofleet.mavlink.messages.HierarchicalRouteDecisionMsg;
import io.aerofleet.mavlink.messages.Statustext;
import io.aerofleet.mavlink.messages.SysStatus;
import io.aerofleet.mavlink.messages.TerrainTypeMapMsg;
import io.aerofleet.mavlink.messages.TerrainUpdateMsg;
import io.aerofleet.mavlink.messages.VfrHud;
import io.aerofleet.mavlink.messages.EnvironmentAlert;
import io.aerofleet.mavlink.messages.EnvironmentStatus;
import io.aerofleet.mavlink.messages.EmergencyMissionPlanMsg;
import io.aerofleet.mavlink.messages.CoverageOptimizationMsg;
import io.aerofleet.mavlink.messages.EmergencyPriorityMsg;
import io.aerofleet.mavlink.messages.TaskAssignmentMsg;
import io.aerofleet.mavlink.messages.ConflictAlertMsg;
import io.aerofleet.mavlink.messages.TaskStatusMsg;
import io.aerofleet.mavlink.messages.DecisionEventMsg;
import io.aerofleet.mavlink.messages.AdaptivePathMsg;
import io.aerofleet.mavlink.messages.EdgeTaskStatusMsg;
import io.aerofleet.mavlink.messages.SensorFusionDataMsg;
import io.aerofleet.mavlink.messages.TwinStateSyncMsg;
import io.aerofleet.mavlink.messages.PredictionResultMsg;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.cloud.api.TelemetryWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import static io.aerofleet.mavlink.enums.MavEnums.MAV_MODE_FLAG_SAFETY_ARMED;

/**
 * Central MAVLink frame dispatcher: feeds the device registry with decoded
 * telemetry and routes command/mission responses into PendingAcks so
 * DroneCommandService futures can complete.
 */
@Service
public class TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

    private final DeviceRegistry registry;
    private final PendingAcks pendings;
    private final AlertBus alerts;
    private final RadarController radarController;
    private final RotorController rotorController;
    private final HardwareDataController hardwareDataController;
    private final MeshTopologyService meshTopologyService;
    private final SatLinkMonitorService satLinkMonitorService;
    private final TerrainMapService terrainMapService;
    private final CellTowerTopologyService cellTowerTopologyService;
    private final EmergencyOrchService emergencyOrchService;
    private final TelemetryWebSocketHandler wsHandler;
    private final ObjectMapper objectMapper;

    public TelemetryIngestService(DeviceRegistry registry, PendingAcks pendings, AlertBus alerts,
                                  @Lazy RadarController radarController, @Lazy RotorController rotorController,
                                  @Lazy HardwareDataController hardwareDataController,
                                  @Lazy MeshTopologyService meshTopologyService,
                                  @Lazy SatLinkMonitorService satLinkMonitorService,
                                  @Lazy TerrainMapService terrainMapService,
                                  @Lazy CellTowerTopologyService cellTowerTopologyService,
                                  @Lazy EmergencyOrchService emergencyOrchService,
                                  @Lazy TelemetryWebSocketHandler wsHandler,
                                  ObjectMapper objectMapper) {
        this.registry = registry;
        this.pendings = pendings;
        this.alerts = alerts;
        this.radarController = radarController;
        this.rotorController = rotorController;
        this.hardwareDataController = hardwareDataController;
        this.meshTopologyService = meshTopologyService;
        this.satLinkMonitorService = satLinkMonitorService;
        this.terrainMapService = terrainMapService;
        this.cellTowerTopologyService = cellTowerTopologyService;
        this.emergencyOrchService = emergencyOrchService;
        this.wsHandler = wsHandler;
        this.objectMapper = objectMapper;
    }

    /** Called by the UDP transport for every CRC-valid frame. Never throws. */
    public void handle(MavlinkFrame frame) {
        try {
            MavlinkMessage msg = MavlinkMessage.decode(frame);
            if (msg == null) {
                return; // not one of our message types: ignore at scaffold stage
            }
            int sysid = frame.getSystemId();
            switch (frame.getMessageId()) {
                case Heartbeat.ID -> onHeartbeat(sysid, (Heartbeat) msg);
                case SysStatus.ID -> onSysStatus(sysid, (SysStatus) msg);
                case GpsRawInt.ID -> onGps(sysid, (GpsRawInt) msg);
                case Attitude.ID -> onAttitude(sysid, (Attitude) msg);
                case GlobalPositionInt.ID -> onPosition(sysid, (GlobalPositionInt) msg);
                case VfrHud.ID -> onVfrHud(sysid, (VfrHud) msg);
                case MissionCurrent.ID -> onMissionCurrent(sysid, (MissionCurrent) msg);
                case Statustext.ID -> onStatustext(sysid, (Statustext) msg);
                case RadioStatus.ID -> onRadioStatus(sysid, (RadioStatus) msg);
                case CommandAck.ID -> pendings.offer(CommandAck.ID, msg, sysid);
                case MissionRequestInt.ID -> pendings.offer(MissionRequestInt.ID, msg, sysid);
                case MissionRequest.ID -> pendings.offer(MissionRequest.ID, msg, sysid);
                case MissionAckMsg.ID -> pendings.offer(MissionAckMsg.ID, msg, sysid);
                // Mission-download direction: the drone replying to our pull.
                case MissionCountMsg.ID -> pendings.offer(MissionCountMsg.ID, msg, sysid);
                case MissionItemInt.ID -> pendings.offer(MissionItemInt.ID, msg, sysid);
                // M0b 环境气象消息（FR-23/24）：环境告警接入 AlertBus，环境状态更新视图
                case EnvironmentAlert.ID -> onEnvironmentAlert(sysid, (EnvironmentAlert) msg);
                case EnvironmentStatus.ID -> onEnvironmentStatus(sysid, (EnvironmentStatus) msg);
                // M4 硬件抽象遥测路由（msgId 437-441，FR-18~FR-22）：
                // 解码后分发至对应 controller 回调，驱动雷达状态/目标缓存、旋翼遥测、LiDAR/IMU 缓存。
                case RadarScanMsg.ID -> radarController.onRadarScan((RadarScanMsg) msg);
                case RadarTargetMsg.ID -> radarController.onRadarTarget((RadarTargetMsg) msg);
                case RotorTelemetryMsg.ID -> rotorController.onRotorTelemetry((RotorTelemetryMsg) msg);
                case LidarDataMsg.ID -> hardwareDataController.onLidarData((LidarDataMsg) msg);
                case ImuDataMsg.ID -> hardwareDataController.onImuData((ImuDataMsg) msg);
                // M5 mesh 拓扑上报路由（msgId 450/454，FR-27）：
                // MeshHeartbeat → 更新节点在线状态；MeshNeighborTable → 更新拓扑快照。
                case MeshHeartbeatMsg.ID -> onMeshHeartbeat(sysid, (MeshHeartbeatMsg) msg);
                case MeshNeighborTableMsg.ID -> meshTopologyService.onNeighborTable(sysid, (MeshNeighborTableMsg) msg);
                // M7 sat-relay 消息路由（msgId 459-461，FR-5.4/5.3）：
                // SatLinkStatus → 链路状态快照；SatPassSchedule → 过境计划；HierarchicalRouteDecision → 路由决策历史。
                case SatLinkStatusMsg.ID -> satLinkMonitorService.onSatLinkStatus(sysid, (SatLinkStatusMsg) msg);
                case SatPassScheduleMsg.ID -> satLinkMonitorService.onSatPassSchedule(sysid, (SatPassScheduleMsg) msg);
                case HierarchicalRouteDecisionMsg.ID -> satLinkMonitorService.onHierarchicalRouteDecision(sysid, (HierarchicalRouteDecisionMsg) msg);
                // M8 复杂地形适配消息路由（msgId 462-464，FR-31）：
                // TerrainTypeMap → 地形图快照；TerrainUpdate → 变更历史；FlightRestriction → 限制区列表。
                case TerrainTypeMapMsg.ID -> terrainMapService.onTerrainTypeMap(sysid, (TerrainTypeMapMsg) msg);
                case TerrainUpdateMsg.ID -> terrainMapService.onTerrainUpdate(sysid, (TerrainUpdateMsg) msg);
                case FlightRestrictionMsg.ID -> terrainMapService.onFlightRestriction(sysid, (FlightRestrictionMsg) msg);
                // M6 移动基站载荷消息路由（msgId 455-458，FR-MSG-02/04/05）：
                // CellTowerStatus → 基站状态快照；GroundTerminalRegister → 终端注册；CellHandover → 漫游切换。
                case CellTowerStatusMsg.ID -> cellTowerTopologyService.onCellTowerStatus(sysid, (CellTowerStatusMsg) msg);
                case GroundTerminalRegisterMsg.ID -> cellTowerTopologyService.onTerminalRegister(sysid, (GroundTerminalRegisterMsg) msg);
                case CellHandoverMsg.ID -> cellTowerTopologyService.onHandover(sysid, (CellHandoverMsg) msg);
                // M9 应急任务编排消息路由（msgId 465-467，FR-30）：
                // EmergencyMissionPlan → 计划状态更新；CoverageOptimization → 覆盖部署方案；EmergencyPriority → 优先级调度事件。
                case EmergencyMissionPlanMsg.ID -> onEmergencyMissionPlan(sysid, (EmergencyMissionPlanMsg) msg);
                case CoverageOptimizationMsg.ID -> onCoverageOptimization(sysid, (CoverageOptimizationMsg) msg);
                case EmergencyPriorityMsg.ID -> onEmergencyPriority(sysid, (EmergencyPriorityMsg) msg);
                // M10-M13 自定义扩展消息路由（msgId 468-476）：解码后转发至 WebSocket 供前端实时展示。
                case TaskAssignmentMsg.ID -> forwardToWs(sysid, "task-assignment", msg);
                case ConflictAlertMsg.ID -> forwardToWs(sysid, "conflict-alert", msg);
                case TaskStatusMsg.ID -> forwardToWs(sysid, "task-status", msg);
                case DecisionEventMsg.ID -> forwardToWs(sysid, "decision-event", msg);
                case AdaptivePathMsg.ID -> forwardToWs(sysid, "adaptive-path", msg);
                case EdgeTaskStatusMsg.ID -> forwardToWs(sysid, "edge-task-status", msg);
                case SensorFusionDataMsg.ID -> forwardToWs(sysid, "sensor-fusion", msg);
                case TwinStateSyncMsg.ID -> forwardToWs(sysid, "twin-state-sync", msg);
                case PredictionResultMsg.ID -> forwardToWs(sysid, "prediction-result", msg);
                default -> { /* SYSTEM_TIME / HOME_POSITION etc.: not needed yet */ }
            }
        } catch (RuntimeException e) {
            // Decode errors must never kill the UDP receive loop
            log.debug("Failed to process frame msgId={} from sysid={}: {}",
                    frame.getMessageId(), frame.getSystemId(), e.getMessage());
        }
    }

    private void onHeartbeat(int sysid, Heartbeat hb) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.lastHeartbeatMs = System.currentTimeMillis();
        s.customMode = hb.customMode;
        s.baseMode = hb.baseMode;
        s.systemStatus = hb.systemStatus;
        s.armed = (hb.baseMode & MAV_MODE_FLAG_SAFETY_ARMED) != 0;
        s.mode = px4NavStateLabel(hb.customMode);
        if (!s.online) {
            s.online = true;
            log.info("Drone back online: sysid={}", sysid);
        }
    }

    /** PX4 main-nav-state labels (custom_mode of a PX4 heartbeat). */
    static String px4NavStateLabel(int customMode) {
        return switch (customMode) {
            case 0 -> "MANUAL";
            case 1 -> "ALTITUDE";
            case 2 -> "POSITION";
            case 3 -> "MISSION";
            case 4 -> "RTL";
            case 5, 14, 15, 16, 17, 18, 19, 20, 21 -> "HOLD";
            case 8 -> "ACRO";
            case 9, 10, 11 -> "STABILIZED";
            case 12 -> "DESCENT/LAND";
            case 13 -> "STANDBY";
            default -> "UNKNOWN";
        };
    }

    private void onSysStatus(int sysid, SysStatus st) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.voltage = st.voltageBattery;
        s.current = st.currentBattery;
        s.battery = st.batteryRemaining;
        s.load = st.load;
    }

    /**
     * RADIO_STATUS (E1): SiK raw rssi (~2x dB) -> dBm, mirrored remrssi
     * means a symmetric link. Drives the signal bar and link-quality alert.
     */
    private void onRadioStatus(int sysid, RadioStatus rs) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        if (rs.rssi != RadioStatus.INVALID) {
            s.rssiDbm = RadioEnvironmentDbm.fromSik(rs.rssi);
        }
        if (rs.remrssi != RadioStatus.INVALID) {
            s.remRssiDbm = RadioEnvironmentDbm.fromSik(rs.remrssi);
        }
    }

    private void onGps(int sysid, GpsRawInt g) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        boolean wasHealthy = s.gpsHealthy;
        s.fixType = g.fixType;
        s.satellites = g.satellitesVisible;
        s.eph = g.eph;
        s.gpsHealthy = g.fixType >= 3 && g.satellitesVisible >= 6;
        if (wasHealthy && !s.gpsHealthy) {
            AlertEntry entry = new AlertEntry(2, "GPS fix degraded (fixType="
                    + g.fixType + ", sats=" + g.satellitesVisible + ")",
                    System.currentTimeMillis());
            s.alerts.add(entry);
            alerts.publish(sysid, entry);
            log.warn("GPS degraded: sysid={} fixType={} sats={}", sysid, g.fixType, g.satellitesVisible);
        } else if (!wasHealthy && s.gpsHealthy) {
            AlertEntry entry = new AlertEntry(5, "GPS fix restored",
                    System.currentTimeMillis());
            s.alerts.add(entry);
            alerts.publish(sysid, entry);
            log.info("GPS restored: sysid={}", sysid);
        }
    }

    private void onAttitude(int sysid, Attitude a) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.roll = Math.toDegrees(a.roll);
        s.pitch = Math.toDegrees(a.pitch);
        s.yaw = Math.toDegrees(a.yaw);
    }

    private void onPosition(int sysid, GlobalPositionInt p) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.lat = p.lat();
        s.lon = p.lon();
        s.relativeAlt = p.relativeAltM();
        s.amslAlt = p.altMm / 1000.0;
        s.vx = p.vx / 100.0;
        s.vy = p.vy / 100.0;
        s.vz = p.vz / 100.0;
        if (p.hdg != MavEnums.HDG_UNKNOWN) {
            s.heading = p.hdg / 100.0;
        }
        if (p.latE7 != 0 || p.lonE7 != 0) {
            s.track.add(new TrackPoint(p.lat(), p.lon(), p.relativeAltM(),
                    System.currentTimeMillis()));
        }
    }

    private void onVfrHud(int sysid, VfrHud h) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.groundspeed = h.groundspeed;
        s.airspeed = h.airspeed;
        s.climb = h.climb;
        if (h.heading >= 0) {
            s.heading = h.heading;
        }
        s.throttle = h.throttle;
    }

    private void onMissionCurrent(int sysid, MissionCurrent m) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.missionSeq = m.seq;
        s.missionTotal = m.total;
        s.missionState = m.missionState;
    }

    private void onStatustext(int sysid, Statustext t) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        AlertEntry entry = new AlertEntry(t.severity, t.text, System.currentTimeMillis());
        s.alerts.add(entry);
        alerts.publish(sysid, entry);
        log.info("STATUSTEXT sysid={} sev={} text={}", sysid, t.severity, t.text);
    }

    /**
     * M0b 环境告警接入（FR-23）：结构化告警直接发布到 AlertBus，
     * 不依赖 STATUSTEXT 映射（EnvironmentAlert 已含 type/severity/value/threshold/text）。
     */
    private void onEnvironmentAlert(int sysid, EnvironmentAlert msg) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        AlertEntry entry = new AlertEntry(msg.severity, msg.text, System.currentTimeMillis());
        s.alerts.add(entry);
        alerts.publish(sysid, entry);
        log.info("ENV_ALERT sysid={} type={} sev={} text={}", sysid, msg.alertType, msg.severity, msg.text);
    }

    /**
     * M0b 环境状态视图更新（FR-24）：更新 DroneSnapshot 环境字段。
     * 单位还原：temperature c°C→°C / windSpeed cm/s→m/s / windDirection cdeg→deg / gust cm/s→m/s。
     */
    private void onEnvironmentStatus(int sysid, EnvironmentStatus msg) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.envTemperature = msg.temperature / 100.0;
        s.envHumidity = msg.humidity;
        s.envWindSpeed = msg.windSpeed / 100.0;
        s.envWindDirection = msg.windDirection / 100.0;
        s.envGust = msg.gust / 100.0;
        s.envWeather = msg.weather;
        s.envVisibility = msg.visibility;
        s.envRainRate = msg.rainRate;
    }

    /**
     * M5 mesh 心跳处理（FR-27）：更新节点在线状态与位置/电量/邻居数。
     */
    private void onMeshHeartbeat(int sysid, MeshHeartbeatMsg msg) {
        DroneSnapshot s = registry.registerIfAbsent(sysid);
        s.lastHeartbeatMs = System.currentTimeMillis();
        if (!s.online) {
            s.online = true;
            log.info("Mesh node online: sysid={}", sysid);
        }
        log.debug("MESH_HEARTBEAT sysid={} neighbors={} battery={}%", sysid, msg.neighborCount, msg.batteryPercent);
    }

    /**
     * M9 应急任务编排：EMERGENCY_MISSION_PLAN (465) 处理（FR-30）。
     * 转发至 {@link EmergencyOrchService} 更新计划阶段/状态/覆盖/连通率。
     */
    private void onEmergencyMissionPlan(int sysid, EmergencyMissionPlanMsg msg) {
        emergencyOrchService.onEmergencyMissionPlan(
                msg.planId, msg.scenarioType, msg.phase, msg.phaseStatus,
                msg.droneCount, msg.coverageRate, msg.connectRate, msg.priority);
        log.debug("EMERGENCY_MISSION_PLAN sysid={} planId={} phase={} status={}",
                sysid, msg.planId, msg.phase, msg.phaseStatus);
    }

    /**
     * M9 应急任务编排：COVERAGE_OPTIMIZATION (466) 处理（FR-30）。
     * 转发至 {@link EmergencyOrchService} 记录单架无人机覆盖部署方案。
     */
    private void onCoverageOptimization(int sysid, CoverageOptimizationMsg msg) {
        emergencyOrchService.onCoverageOptimization(
                msg.planId, msg.droneId, msg.cellType, msg.relayRole,
                msg.txPower, msg.expectedCoverage, msg.batteryBudget);
        log.debug("COVERAGE_OPTIMIZATION sysid={} planId={} drone={} cell={} cov={}%",
                sysid, msg.planId, msg.droneId, msg.cellType, msg.expectedCoverage);
    }

    /**
     * M9 应急任务编排：EMERGENCY_PRIORITY (467) 处理（FR-30）。
     * 转发至 {@link EmergencyOrchService} 记录优先级调度事件。
     */
    private void onEmergencyPriority(int sysid, EmergencyPriorityMsg msg) {
        emergencyOrchService.onEmergencyPriority(
                msg.planId, msg.taskId, msg.priority, msg.action,
                msg.preemptedTaskId, msg.reason);
        log.debug("EMERGENCY_PRIORITY sysid={} planId={} task={} pri={} action={}",
                sysid, msg.planId, msg.taskId, msg.priority, msg.action);
    }

    /**
     * M10-M13 自定义扩展消息 WebSocket 转发（msgId 468-476）。
     * <p>
     * 将解码后的消息以 JSON 帧广播到所有连接的 /ws/telemetry 客户端，供前端实时展示。
     * 无 WS 连接时降级为日志输出，不阻塞 UDP 接收循环。
     * <p>
     * 帧格式：{"type":"&lt;type&gt;","sysid":N,"data":&lt;msg&gt;,"timestamp":T}
     */
    private void forwardToWs(int sysid, String type, MavlinkMessage msg) {
        if (wsHandler == null || wsHandler.connectionCount() == 0) {
            log.debug("WS forward (no clients): sysid={} type={}", sysid, type);
            return;
        }
        try {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", type);
            frame.put("sysid", sysid);
            frame.put("data", msg);
            frame.put("timestamp", System.currentTimeMillis());
            wsHandler.broadcast(objectMapper.writeValueAsString(frame), objectMapper);
        } catch (Exception e) {
            log.warn("WS forward failed: sysid={} type={}: {}", sysid, type, e.getMessage());
        }
    }
}
