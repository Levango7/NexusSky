package io.aerofleet.cloud.mission.emergency;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.surveillance.SurveillanceDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 空地协同 WebSocket 推送。
 * <p>
 * 由 {@link AirGroundCoordinationService} 在空地协同态势变更时主动调用，
 * 组装 JSON 帧并复用 {@link TelemetryWebSocketHandler#broadcast}。
 * <p>
 * 推送帧类型：
 * <pre>
 * {"type":"air-ground-situation",  "coordinationId":"...", "situation":{...}, "timestamp":T}
 * {"type":"air-ground-alarm",      "coordinationId":"...", "alarm":{...},      "timestamp":T}
 * {"type":"air-ground-progress",   "coordinationId":"...", "phase":"...",      "message":"...", "cmdId":"...", "timestamp":T}
 * {"type":"air-ground-evaluation", "coordinationId":"...", "evaluation":{...}, "timestamp":T}
 * </pre>
 * 连接数为 0 或 handler 为 null（测试场景）时降级为日志输出。
 */
@Component
public class AirGroundCoordinationPusher {

    private static final Logger log = LoggerFactory.getLogger(AirGroundCoordinationPusher.class);

    private final TelemetryWebSocketHandler handler;
    private final ObjectMapper mapper;

    public AirGroundCoordinationPusher(TelemetryWebSocketHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    /** 推送空地协同态势更新（安防设备状态 + 无人机状态 + mesh拓扑 + 报警事件）。 */
    public void pushSituationUpdate(String coordinationId, AirGroundSituation situation) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "air-ground-situation");
        frame.put("coordinationId", coordinationId);
        frame.put("situation", situationToMap(situation));
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 推送报警触发事件。 */
    public void pushAlarmTrigger(String coordinationId, Map<String, Object> alarm) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "air-ground-alarm");
        frame.put("coordinationId", coordinationId);
        frame.put("alarm", alarm);
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 推送指挥进度更新（阶段切换、状态变更等）。 */
    public void pushCoordinationProgress(String coordinationId, String phase,
                                          String message, String cmdId) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "air-ground-progress");
        frame.put("coordinationId", coordinationId);
        frame.put("phase", phase);
        frame.put("message", message);
        frame.put("cmdId", cmdId);
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 推送评估结果。 */
    public void pushEvaluationResult(String coordinationId,
                                      AirGroundCoordinationService.CoordinationEvaluation evaluation) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "air-ground-evaluation");
        frame.put("coordinationId", coordinationId);
        frame.put("evaluation", evaluationToMap(evaluation));
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 广播帧；无连接或无 handler 时降级为日志。 */
    private void broadcast(Map<String, Object> frame) {
        if (handler == null || handler.connectionCount() == 0) {
            log.debug("air-ground push (no ws clients): type={} coordinationId={}",
                    frame.get("type"), frame.get("coordinationId"));
            return;
        }
        try {
            handler.broadcast(mapper.writeValueAsString(frame), mapper);
        } catch (Exception e) {
            log.warn("air-ground push failed: type={} coordinationId={}: {}",
                    frame.get("type"), frame.get("coordinationId"), e.getMessage());
        }
    }

    // =====================================================================
    // 响应转换辅助
    // =====================================================================

    /** 将 AirGroundSituation 转为推送 Map。 */
    private static Map<String, Object> situationToMap(AirGroundSituation situation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generatedAtMs", situation.getGeneratedAtMs());
        m.put("onlineDeviceCount", situation.onlineDeviceCount());
        m.put("onlineDroneCount", situation.onlineDroneCount());
        m.put("unacknowledgedAlarmCount", situation.unacknowledgedAlarmCount());

        // 安防设备列表
        List<Map<String, Object>> devices = new ArrayList<>();
        for (SurveillanceDevice d : situation.getSurveillanceDevices()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", d.id);
            dm.put("name", d.name);
            dm.put("status", d.status.name());
            dm.put("capabilities", new ArrayList<>(d.getCapabilities()));
            devices.add(dm);
        }
        m.put("surveillanceDevices", devices);

        // 无人机状态列表
        List<Map<String, Object>> drones = new ArrayList<>();
        for (AirGroundSituation.DroneStatus d : situation.getDroneStatuses()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("sysid", d.sysid);
            dm.put("online", d.online);
            dm.put("lat", d.lat);
            dm.put("lon", d.lon);
            dm.put("alt", d.alt);
            dm.put("batteryPct", d.batteryPct);
            dm.put("missionPhase", d.missionPhase);
            drones.add(dm);
        }
        m.put("droneStatuses", drones);

        // mesh 拓扑
        Map<String, Object> mesh = new LinkedHashMap<>();
        mesh.put("nodeCount", situation.getMeshTopology().nodeCount);
        mesh.put("linkCount", situation.getMeshTopology().linkCount);
        mesh.put("coverageRate", situation.getMeshTopology().coverageRate);
        mesh.put("relayNodes", new ArrayList<>(situation.getMeshTopology().getRelayNodes()));
        m.put("meshTopology", mesh);

        return m;
    }

    /** 将 CoordinationEvaluation 转为推送 Map。 */
    private static Map<String, Object> evaluationToMap(
            AirGroundCoordinationService.CoordinationEvaluation evaluation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coverageRate", evaluation.coverageRate);
        m.put("deviceCoverageRate", evaluation.deviceCoverageRate);
        m.put("connectivityRate", evaluation.connectivityRate);

        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("receiveToAssessSec", evaluation.timing.receiveToAssessSec);
        timing.put("assessToDeploySec", evaluation.timing.assessToDeploySec);
        timing.put("deployToExecuteSec", evaluation.timing.deployToExecuteSec);
        timing.put("totalResponseSec", evaluation.timing.totalResponseSec);
        m.put("timing", timing);

        Map<String, Object> consumption = new LinkedHashMap<>();
        consumption.put("droneCount", evaluation.consumption.droneCount);
        consumption.put("deviceCount", evaluation.consumption.deviceCount);
        consumption.put("avgBatteryPct", evaluation.consumption.avgBatteryPct);
        consumption.put("meshNodes", evaluation.consumption.meshNodes);
        m.put("consumption", consumption);

        return m;
    }
}