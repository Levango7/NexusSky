package io.aerofleet.cloud.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.telemetry.AlertBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 1Hz WebSocket pusher: once per second, every online drone's full snapshot
 * is broadcast as {"type":"telemetry",...} plus a small "status" frame, so the
 * browser is never flooded by the 10-50Hz raw MAVLink stream.
 * STATUSTEXT alerts are pushed immediately via {@link AlertBus}.
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
        for (DroneSnapshot s : registry.all()) {
            if (!s.online) {
                continue;
            }
            // Persist every online drone even with no WS viewers: the flight
            // log must not depend on somebody having the GCS page open.
            flightLog.telemetry(s);
            if (handler.connectionCount() == 0) {
                continue;
            }
            try {
                handler.broadcast(frame("telemetry", s.sysid, DroneViews.telemetry(s)), mapper);
                handler.broadcast(frame("status", s.sysid, DroneViews.status(s)), mapper);
            } catch (RuntimeException e) {
                log.warn("Failed to serialize telemetry for sysid={}: {}", s.sysid, e.getMessage());
            }
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

    private String frame(String type, int sysid, Object data) {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("type", type);
            m.put("sysid", sysid);
            m.put("data", data);
            return mapper.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("json encode failed", e);
        }
    }
}
