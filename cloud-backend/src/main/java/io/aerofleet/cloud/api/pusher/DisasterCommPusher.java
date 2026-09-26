package io.aerofleet.cloud.api.pusher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 灾害通信 WebSocket 推送（P2 灾害应急通讯组网扩展）。
 * <p>
 * 由 {@link io.aerofleet.cloud.api.service.DisasterCommService} 在状态变更时主动调用，
 * 组装 JSON 帧并复用 {@link TelemetryWebSocketHandler#broadcast}（既有不变）。
 * <p>
 * 推送帧类型：
 * <pre>
 * {"type":"disaster-status",  "status":{...}, "timestamp":T}
 * {"type":"disaster-cluster", "topology":{...}, "timestamp":T}
 * {"type":"disaster-qos",     "qos":{...}, "timestamp":T}
 * </pre>
 * 连接数为 0 或 handler 为 null（测试场景）时降级为日志输出。
 */
@Component
public class DisasterCommPusher {

    private static final Logger log = LoggerFactory.getLogger(DisasterCommPusher.class);

    private final TelemetryWebSocketHandler handler;
    private final ObjectMapper mapper;

    public DisasterCommPusher(TelemetryWebSocketHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    /** 推送灾害模式状态变更。 */
    public void pushDisasterStatus(Map<String, Object> status) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "disaster-status");
        frame.put("status", status);
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 推送分簇拓扑更新。 */
    public void pushClusterTopology(Map<String, Object> topology) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "disaster-cluster");
        frame.put("topology", topology);
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 推送 QoS 优先级队列状态。 */
    public void pushQoSStatus(Map<String, Object> qosStatus) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "disaster-qos");
        frame.put("qos", qosStatus);
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 广播帧；无连接或无 handler 时降级为日志。 */
    private void broadcast(Map<String, Object> frame) {
        if (handler == null || handler.connectionCount() == 0) {
            log.debug("disaster push (no ws clients): type={}", frame.get("type"));
            return;
        }
        try {
            handler.broadcast(mapper.writeValueAsString(frame), mapper);
        } catch (Exception e) {
            log.warn("disaster push failed: type={}: {}", frame.get("type"), e.getMessage());
        }
    }
}