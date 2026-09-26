package io.aerofleet.cloud.api.pusher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.vision.RadarController;
import io.aerofleet.cloud.vision.RotorController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 硬件状态 WebSocket 推送（M4 硬件抽象，FR-30/DFX 4.4）。
 * <p>
 * 1Hz @Scheduled 推送各机雷达扫描状态 + 动力遥测，
 * 复用 {@link TelemetryWebSocketHandler#broadcast}（既有不变）。
 * <p>
 * 推送格式：
 * <pre>
 * {"type":"hardware","radar":[{"sysid":1,"mode":0,"beamAzim":45.0,...},...],
 *  "rotor":[{"sysid":1,"rpm":5000.0,"thrust":15.0,...},...]}
 * </pre>
 */
@Component
public class HardwarePusher {

    private static final Logger log = LoggerFactory.getLogger(HardwarePusher.class);

    private final TelemetryWebSocketHandler handler;
    private final RadarController radarController;
    private final RotorController rotorController;
    private final ObjectMapper mapper;

    public HardwarePusher(TelemetryWebSocketHandler handler,
                          RadarController radarController,
                          RotorController rotorController,
                          ObjectMapper mapper) {
        this.handler = handler;
        this.radarController = radarController;
        this.rotorController = rotorController;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 1000)
    public void pushOnce() {
        if (handler.connectionCount() == 0) {
            return;
        }
        List<Map<String, Object>> radarList = new ArrayList<>();
        for (Map.Entry<Integer, RadarController.RadarScanStatus> e
                : radarController.allStatuses().entrySet()) {
            Map<String, Object> d = new HashMap<>();
            d.put("sysid", e.getKey());
            d.put("mode", e.getValue().mode);
            d.put("beamAzim", e.getValue().beamAzim);
            d.put("beamElev", e.getValue().beamElev);
            d.put("targetCount", e.getValue().targetCount);
            d.put("lastScanTime", e.getValue().lastScanTime);
            radarList.add(d);
        }
        List<Map<String, Object>> rotorList = new ArrayList<>();
        for (Map.Entry<Integer, RotorController.RotorTelemetry> e
                : rotorController.allTelemetries().entrySet()) {
            Map<String, Object> d = new HashMap<>();
            d.put("sysid", e.getKey());
            d.put("rotorIndex", e.getValue().rotorIndex);
            d.put("rpm", e.getValue().rpm);
            d.put("thrust", e.getValue().thrust);
            d.put("power", e.getValue().power);
            d.put("totalThrust", e.getValue().totalThrust);
            d.put("totalPower", e.getValue().totalPower);
            d.put("lastUpdateTime", e.getValue().lastUpdateTime);
            rotorList.add(d);
        }
        if (radarList.isEmpty() && rotorList.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> frame = new HashMap<>();
            frame.put("type", "hardware");
            frame.put("radar", radarList);
            frame.put("rotor", rotorList);
            handler.broadcast(mapper.writeValueAsString(frame), mapper);
        } catch (Exception e) {
            log.warn("hardware push failed: {}", e.getMessage());
        }
    }
}