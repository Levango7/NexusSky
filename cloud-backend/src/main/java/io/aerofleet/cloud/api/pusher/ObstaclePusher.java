package io.aerofleet.cloud.api.pusher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.vision.ObstacleAvoidanceController;
import io.aerofleet.cloud.vision.ObstacleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 避障状态 WebSocket 推送（M3 感知成像增强，FR-30/DFX 4.4）。
 * <p>
 * 1Hz @Scheduled 推送各机避障状态，复用 {@link TelemetryWebSocketHandler#broadcast}（既有不变）。
 * 推送格式：{"type":"obstacle","drones":[{"sysid":1,"threat":"HIGH",...},...]}
 */
@Component
public class ObstaclePusher {

    private static final Logger log = LoggerFactory.getLogger(ObstaclePusher.class);

    private final TelemetryWebSocketHandler handler;
    private final ObstacleAvoidanceController controller;
    private final ObjectMapper mapper;

    public ObstaclePusher(TelemetryWebSocketHandler handler,
                          ObstacleAvoidanceController controller,
                          ObjectMapper mapper) {
        this.handler = handler;
        this.controller = controller;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 1000)
    public void pushOnce() {
        if (handler.connectionCount() == 0) {
            return;
        }
        List<Map<String, Object>> drones = new ArrayList<>();
        for (Map.Entry<Integer, ObstacleStatus> e : controller.allStatuses().entrySet()) {
            Map<String, Object> d = new HashMap<>();
            d.put("sysid", e.getKey());
            d.put("threat", e.getValue().currentThreat.name());
            d.put("nearestDistance", e.getValue().nearestDistance);
            d.put("inEmergencyHover", e.getValue().inEmergencyHover);
            d.put("lastReportTime", e.getValue().lastReportTime);
            drones.add(d);
        }
        if (drones.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> frame = new HashMap<>();
            frame.put("type", "obstacle");
            frame.put("drones", drones);
            handler.broadcast(mapper.writeValueAsString(frame), mapper);
        } catch (Exception e) {
            log.warn("obstacle push failed: {}", e.getMessage());
        }
    }
}