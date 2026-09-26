package io.aerofleet.cloud.api.pusher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.dto.DroneViews;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.telemetry.AlertBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1Hz WebSocket pusher: once per second, every online drone's full snapshot
 * is broadcast as a single batch frame containing all telemetry+status entries,
 * so the browser is never flooded by the 10-50Hz raw MAVLink stream.
 * STATUSTEXT alerts are pushed immediately via {@link AlertBus}.
 * <p>
 * 批量优化：N 台无人机的 telemetry+status 组装为单个 JSON 批量帧，
 * 一次序列化一次广播（从 2N 次序列化降为 1 次）。
 */
@Component
public class TelemetryPusher {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPusher.class);

    private final TelemetryWebSocketHandler handler;
    private final DeviceRegistry registry;
    private final AlertBus alerts;
    private final ObjectMapper mapper;
    private final io.aerofleet.cloud.flightlog.FlightLogService flightLog;

    public TelemetryPusher(TelemetryWebSocketHandler handler,
                           DeviceRegistry registry,
                           AlertBus alerts,
                           ObjectMapper mapper,
                           io.aerofleet.cloud.flightlog.FlightLogService flightLog) {
        this.handler = handler;
        this.registry = registry;
        this.alerts = alerts;
        this.mapper = mapper;
        this.flightLog = flightLog;
        alerts.subscribe(event -> pushAlert(event.sysid(), event.entry()));
    }

    @Scheduled(fixedDelay = 1000)
    public void pushOnce() {
        // Collect all online drone entries into a single batch frame.
        // Flight log persistence happens regardless of WS viewer count.
        List<Map<String, Object>> items = new ArrayList<>();
        for (DroneSnapshot s : registry.all()) {
            if (!s.online) {
                continue;
            }
            // Persist every online drone even with no WS viewers: the flight
            // log must not depend on somebody having the GCS page open.
            flightLog.telemetry(s);
            if (handler.connectionCount() > 0) {
                items.add(frameMap("telemetry", s.sysid, DroneViews.telemetry(s)));
                items.add(frameMap("status", s.sysid, DroneViews.status(s)));
            }
        }

        // Skip serialization entirely when no WS viewers or no online drones
        if (items.isEmpty()) {
            return;
        }

        try {
            Map<String, Object> batch = new HashMap<>();
            batch.put("type", "batch");
            batch.put("items", items);
            handler.broadcast(mapper.writeValueAsString(batch), mapper);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to serialize batch telemetry frame: {}", e.getMessage());
        }
    }

    private void pushAlert(int sysid, io.aerofleet.cloud.gateway.AlertEntry entry) {
        flightLog.alert(sysid, entry);   // persisted even with no viewers
        if (handler.connectionCount() == 0) {
            return;
        }
        try {
            handler.broadcast(frame("alert", sysid, DroneViews.alert(entry)), mapper);
        } catch (RuntimeException e) {
            log.warn("Failed to serialize alert for sysid={}: {}", sysid, e.getMessage());
        }
    }

    /** Build a single frame as a Map (for batch assembly, avoids intermediate serialization). */
    private Map<String, Object> frameMap(String type, int sysid, Object data) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("sysid", sysid);
        m.put("data", data);
        return m;
    }

    /** Serialize a single frame to JSON string (for immediate alert push). */
    private String frame(String type, int sysid, Object data) {
        try {
            return mapper.writeValueAsString(frameMap(type, sysid, data));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("json encode failed", e);
        }
    }
}
