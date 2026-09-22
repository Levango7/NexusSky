package io.aerofleet.cloud.mission.formation;

import io.aerofleet.cloud.mission.squad.SquadRoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编队状态 WebSocket 推送（FR-13，DFX 4.1 ≤1Hz，DFX 4.5 既有遥测推送不变）。
 *
 * 1Hz 推送编队状态 + 各机灯光状态，复用既有 {@link TelemetryWebSocketHandler#broadcast}，
 * 编队帧 {@code {"type":"formation",...}} 与既有遥测帧 {@code {"type":"telemetry",...}} 共存，
 * 前端按 type 字段分发。
 *
 * 无 WebSocket 连接时不推送（节省 CPU）。
 */
@Component
public class FormationPusher {

    private static final Logger log = LoggerFactory.getLogger(FormationPusher.class);

    private final FormationService formationService;
    private final TelemetryWebSocketHandler wsHandler;
    private final ObjectMapper mapper;
    private final DeviceRegistry registry;
    private final SquadRoleService roles;

    public FormationPusher(FormationService formationService,
                           TelemetryWebSocketHandler wsHandler,
                           ObjectMapper mapper,
                           DeviceRegistry registry,
                           SquadRoleService roles) {
        this.formationService = formationService;
        this.wsHandler = wsHandler;
        this.mapper = mapper;
        this.registry = registry;
        this.roles = roles;
    }

    /**
     * 1Hz 推送编队状态（FR-13）。
     * 无连接时直接返回；有连接时遍历所有编队构造状态帧广播。
     */
    @Scheduled(fixedDelay = 1000)
    public void pushOnce() {
        if (wsHandler.connectionCount() == 0) {
            return;  // 无连接不推送
        }
        List<Formation> formations = formationService.allFormations();
        if (formations.isEmpty()) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(buildFrame(formations));
            wsHandler.broadcast(json, mapper);
        } catch (Exception e) {
            log.warn("formation push failed: {}", e.getMessage());
        }
    }

    /** 构造编队状态帧 JSON 视图。 */
    private Map<String, Object> buildFrame(List<Formation> formations) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "formation");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Formation f : formations) {
            list.add(formationView(f));
        }
        frame.put("formations", list);
        return frame;
    }

    private Map<String, Object> formationView(Formation f) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formationId", f.formationId);
        out.put("state", f.state.name());
        out.put("shape", f.shape.name());
        out.put("leader", f.leaderSysid);
        out.put("version", f.version.get());

        List<Map<String, Object>> members = new ArrayList<>();
        for (int sysid : f.sortedMembers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sysid", sysid);
            DroneSnapshot snap = registry.get(sysid);
            m.put("online", snap != null && snap.online);
            m.put("role", roles.roleOf(sysid).name());
            Formation.GeoPos target = f.targetPositions.get(sysid);
            if (target != null) {
                m.put("targetLat", target.lat());
                m.put("targetLon", target.lon());
                m.put("targetAlt", target.alt());
            }
            // 灯光状态从 lastLightCommand 读取
            LedControlCommand led = f.lastLightCommand;
            if (led != null) {
                m.put("ledOn", led.on);
                m.put("ledPattern", led.pattern);
                m.put("ledBrightness", led.brightness);
            } else {
                m.put("ledOn", false);
            }
            members.add(m);
        }
        out.put("members", members);
        return out;
    }
}