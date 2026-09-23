package io.aerofleet.cloud.mission.emergency;

import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.cloud.surveillance.SurveillanceDevice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;

/**
 * 空地协同指挥 REST 端点（空地协同三大核心能力）。
 * <p>
 * 独立路径前缀 /api/v1/air-ground/*，提供空地协同态势感知、报警触发侦察、PTZ 联动追踪。
 * <p>
 * 端点清单：
 * <pre>
 * GET  /api/v1/air-ground/situation   获取空地协同态势融合视图
 * POST /api/v1/air-ground/recon        从安防告警触发无人机自动侦察
 * POST /api/v1/air-ground/ptz-track    无人机发现目标触发安防 PTZ 联动追踪
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/air-ground")
@Tag(name = "AirGroundCoordination", description = "空地协同指挥 REST API：态势融合、报警触发侦察、PTZ 联动追踪")
public class AirGroundCoordinationController {

    private final AirGroundCoordinationService coordinationService;

    public AirGroundCoordinationController(AirGroundCoordinationService coordinationService) {
        this.coordinationService = coordinationService;
    }

    // =====================================================================
    // 端点 1：空地态势融合
    // =====================================================================

    /**
     * 获取空地协同态势融合视图。
     * <p>
     * 融合安防设备状态、无人机状态、报警事件和 mesh 拓扑信息，
     * 生成统一态势感知视图供指挥中心实时掌握空地协同态势。
     */
    @Operation(summary = "获取空地协同态势融合视图", description = "融合安防设备、无人机、报警事件和 mesh 拓扑信息")
    @ApiResponse(responseCode = "200", description = "态势融合视图")
    @GetMapping("/situation")
    public ResponseEntity<Map<String, Object>> getSituation() {
        AirGroundSituation situation = coordinationService.fuseAirGroundSituation();
        return ResponseEntity.ok(situationToMap(situation));
    }

    // =====================================================================
    // 端点 2：安防报警触发无人机侦察
    // =====================================================================

    /**
     * 从安防告警触发无人机自动侦察。
     * <p>
     * 接收安防报警事件，触发无人机编排服务启动侦察任务。
     * 若报警联动引擎可用，优先通过联动规则匹配后处理；
     * 否则直接调用编排服务启动侦察任务。
     * <p>
     * body 示例：
     * <pre>
     * {
     *   "eventType": "FIRE",          // MOTION/INTRUSION/FIRE/DOOR/CUSTOM
     *   "severity": "CRITICAL",       // INFO/WARN/CRITICAL
     *   "sourceDeviceId": "cam-001",
     *   "sourceDeviceName": "前门摄像头",
     *   "description": "火灾报警",
     *   "lat": 30.5,
     *   "lon": 114.3,
     *   "alt": 0,
     *   "timestampMs": 1695000000000
     * }
     * </pre>
     */
    @Operation(summary = "从安防告警触发无人机自动侦察", description = "接收报警事件，启动无人机侦察编排任务")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "侦察任务启动结果（含 planId）"),
            @ApiResponse(responseCode = "400", description = "参数非法")
    })
    @PostMapping("/recon")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> triggerRecon(@RequestBody Map<String, Object> body) {
        AlarmEvent event = parseAlarmEvent(body);
        long planId = coordinationService.triggerReconFromAlarm(event);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", planId > 0 ? "RUNNING" : "FAILED");
        result.put("eventId", event.getId());
        result.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(result);
    }

    // =====================================================================
    // 端点 3：无人机目标触发 PTZ 联动追踪
    // =====================================================================

    /**
     * 无人机发现目标触发安防 PTZ 联动追踪。
     * <p>
     * 接收无人机侦察发现的目标信息，查找目标位置附近最近的安防设备，
     * 通过 ONVIF 控制 PTZ 转向目标方向。
     * <p>
     * body 示例：
     * <pre>
     * {
     *   "lat": 30.5,                 // 目标纬度
     *   "lon": 114.3,                // 目标经度
     *   "alt": 100.0,                // 目标海拔（米）
     *   "targetType": "PERSON",      // PERSON/VEHICLE/STRUCTURE/FIRE_SOURCE/UNKNOWN
     *   "confidence": 0.85,          // 识别置信度（0.0~1.0）
     *   "sourceSysid": 1             // 来源无人机 sysid
     * }
     * </pre>
     */
    @Operation(summary = "无人机目标触发安防 PTZ 联动追踪", description = "根据目标位置控制安防设备 PTZ 转向")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PTZ 联动结果（TRACKING/NO_DEVICE/LOW_CONFIDENCE/FAILED）"),
            @ApiResponse(responseCode = "400", description = "参数非法")
    })
    @PostMapping("/ptz-track")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> triggerPtzTrack(@RequestBody Map<String, Object> body) {
        GeoTarget target = parseGeoTarget(body);
        String trackResult = coordinationService.triggerPtzTracking(target);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", trackResult);
        result.put("targetType", target.targetType.name());
        result.put("confidence", target.confidence);
        result.put("sourceSysid", target.sourceSysid);
        result.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(result);
    }

    // =====================================================================
    // 请求解析与响应转换辅助
    // =====================================================================

    /** 从请求 body 解析 AlarmEvent。 */
    private static AlarmEvent parseAlarmEvent(Map<String, Object> body) {
        String eventId = java.util.UUID.randomUUID().toString();
        String sourceDeviceId = str(body, "sourceDeviceId", "");
        String sourceDeviceName = str(body, "sourceDeviceName", "");
        AlarmEvent.EventType eventType = AlarmEvent.parseEventType(str(body, "eventType", "CUSTOM"));
        AlarmEvent.Severity severity = AlarmEvent.Severity.fromString(str(body, "severity", "WARN"));
        String description = str(body, "description", "");
        double lat = numDouble(body, "lat", 0);
        double lon = numDouble(body, "lon", 0);
        double alt = numDouble(body, "alt", 0);
        long timestampMs = numLong(body, "timestampMs", System.currentTimeMillis());

        // 地理坐标范围校验
        if (lat < -90 || lat > 90) {
            throw new BadRequestException("lat must be in [-90, 90], got: " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new BadRequestException("lon must be in [-180, 180], got: " + lon);
        }

        return new AlarmEvent(eventId, sourceDeviceId, sourceDeviceName,
                eventType, severity, description, lat, lon, alt, timestampMs, false);
    }

    /** 从请求 body 解析 GeoTarget。 */
    private static GeoTarget parseGeoTarget(Map<String, Object> body) {
        double lat = numDouble(body, "lat", 0);
        double lon = numDouble(body, "lon", 0);
        double alt = numDouble(body, "alt", 0);
        GeoTarget.TargetType targetType = parseTargetType(str(body, "targetType", "UNKNOWN"));
        double confidence = numDouble(body, "confidence", 0);
        int sourceSysid = numInt(body, "sourceSysid", 0);

        // 地理坐标范围校验
        if (lat < -90 || lat > 90) {
            throw new BadRequestException("lat must be in [-90, 90], got: " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new BadRequestException("lon must be in [-180, 180], got: " + lon);
        }

        return new GeoTarget(lat, lon, alt, targetType, confidence, sourceSysid);
    }

    /** 解析目标类型字符串。 */
    private static GeoTarget.TargetType parseTargetType(String s) {
        if (s == null || s.isBlank()) {
            return GeoTarget.TargetType.UNKNOWN;
        }
        try {
            return GeoTarget.TargetType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GeoTarget.TargetType.UNKNOWN;
        }
    }

    /** 将 AirGroundSituation 转为响应 Map。 */
    private static Map<String, Object> situationToMap(AirGroundSituation situation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generatedAtMs", situation.getGeneratedAtMs());
        m.put("onlineDeviceCount", situation.onlineDeviceCount());
        m.put("onlineDroneCount", situation.onlineDroneCount());
        m.put("unacknowledgedAlarmCount", situation.unacknowledgedAlarmCount());

        // 安防设备列表
        List<Map<String, Object>> devices = new ArrayList<>();
        for (SurveillanceDevice d : situation.getSurveillanceDevices()) {
            devices.add(deviceToMap(d));
        }
        m.put("surveillanceDevices", devices);

        // 无人机状态列表
        List<Map<String, Object>> drones = new ArrayList<>();
        for (AirGroundSituation.DroneStatus d : situation.getDroneStatuses()) {
            drones.add(droneStatusToMap(d));
        }
        m.put("droneStatuses", drones);

        // 报警事件列表
        List<Map<String, Object>> alarms = new ArrayList<>();
        for (AlarmEvent e : situation.getAlarmEvents()) {
            alarms.add(alarmEventToMap(e));
        }
        m.put("alarmEvents", alarms);

        // mesh 拓扑
        m.put("meshTopology", meshTopologyToMap(situation.getMeshTopology()));

        return m;
    }

    /** 将 SurveillanceDevice 转为响应 Map。 */
    private static Map<String, Object> deviceToMap(SurveillanceDevice d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("name", d.name);
        m.put("vendor", d.vendor.name());
        m.put("ip", d.ip);
        m.put("port", d.port);
        m.put("status", d.status.name());
        m.put("capabilities", new ArrayList<>(d.getCapabilities()));
        m.put("rtspUrl", d.rtspUrl);
        m.put("lastHeartbeatMs", d.lastHeartbeatMs);
        return m;
    }

    /** 将 DroneStatus 转为响应 Map。 */
    private static Map<String, Object> droneStatusToMap(AirGroundSituation.DroneStatus d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sysid", d.sysid);
        m.put("online", d.online);
        m.put("lat", d.lat);
        m.put("lon", d.lon);
        m.put("alt", d.alt);
        m.put("batteryPct", d.batteryPct);
        m.put("missionPhase", d.missionPhase);
        return m;
    }

    /** 将 AlarmEvent 转为响应 Map。 */
    private static Map<String, Object> alarmEventToMap(AlarmEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("sourceDeviceId", e.getSourceDeviceId());
        m.put("sourceDeviceName", e.getSourceDeviceName());
        m.put("eventType", e.getEventType().name());
        m.put("severity", e.getSeverity().name());
        m.put("description", e.getDescription());
        m.put("lat", e.getLat());
        m.put("lon", e.getLon());
        m.put("alt", e.getAlt());
        m.put("timestampMs", e.getTimestampMs());
        m.put("acknowledged", e.isAcknowledged());
        return m;
    }

    /** 将 MeshTopology 转为响应 Map。 */
    private static Map<String, Object> meshTopologyToMap(AirGroundSituation.MeshTopology mesh) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeCount", mesh.nodeCount);
        m.put("linkCount", mesh.linkCount);
        m.put("coverageRate", mesh.coverageRate);
        m.put("relayNodes", new ArrayList<>(mesh.getRelayNodes()));
        return m;
    }

    // =====================================================================
    // 通用请求解析辅助（与项目其他 Controller 一致）
    // =====================================================================

    private static String str(Map<String, Object> body, String key, String def) {
        Object v = body.get(key);
        return v == null ? def : v.toString();
    }

    private static int numInt(Map<String, Object> body, String key, int def) {
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("field '" + key + "' is not a valid integer: " + v);
        }
    }

    private static long numLong(Map<String, Object> body, String key, long def) {
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("field '" + key + "' is not a valid long: " + v);
        }
    }

    private static double numDouble(Map<String, Object> body, String key, double def) {
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("field '" + key + "' is not a valid double: " + v);
        }
    }
}