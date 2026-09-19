package io.aerofleet.cloud.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.mission.DroneCommandService;
import io.aerofleet.cloud.mission.MissionItemRequest;
import io.aerofleet.cloud.mission.MissionUploadResult;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.mavlink.messages.MissionItemInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * Fleet REST API. 认证由 {@link io.aerofleet.cloud.security.SecurityConfig}
 * 统一处理：开发模式放行所有请求，生产模式要求 JWT 认证。
 */
@RestController
@RequestMapping("/api/v1/drones")
public class DroneController {

    private static final Logger log = LoggerFactory.getLogger(DroneController.class);

    private final DeviceRegistry registry;
    private final DroneCommandService commands;
    private final io.aerofleet.cloud.flightlog.FlightLogService flightLog;

    public DroneController(DeviceRegistry registry, DroneCommandService commands,
                           io.aerofleet.cloud.flightlog.FlightLogService flightLog) {
        this.registry = registry;
        this.commands = commands;
        this.flightLog = flightLog;
    }

    /** Fleet list: online/offline, battery, mode, position, last heartbeat. */
    @GetMapping
    public List<Map<String, Object>> listDrones() {
        return registry.all().stream().map(DroneViews::summary).toList();
    }

    /** Single drone detail incl. the latest 20 alert lines. */
    @GetMapping("/{sysid}")
    public Map<String, Object> getDrone(@PathVariable("sysid") int sysid) {
        return DroneViews.detail(require(sysid));
    }

    /** Current snapshot as full JSON. */
    @GetMapping("/{sysid}/telemetry")
    public Map<String, Object> getTelemetry(@PathVariable("sysid") int sysid) {
        return DroneViews.telemetry(require(sysid));
    }

    /** Flight track: [{lat,lon,alt,ts}, ...] oldest-first, last 200 points. */
    @GetMapping("/{sysid}/track")
    public List<Map<String, Object>> getTrack(@PathVariable("sysid") int sysid) {
        return DroneViews.track(require(sysid).track.toList());
    }

    /**
     * Read back the mission currently stored on the drone (mission download):
     * MISSION_REQUEST_LIST -> COUNT -> per-seq REQUEST_INT -> ITEM_INT -> ACK.
     */
    @GetMapping("/{sysid}/mission")
    public Map<String, Object> downloadMission(@PathVariable("sysid") int sysid) {
        try {
            java.util.List<io.aerofleet.mavlink.messages.MissionItemInt> items =
                    commands.downloadMission(sysid);
            List<Map<String, Object>> view = new java.util.ArrayList<>();
            for (var it : items) {
                view.add(Map.of(
                        "seq", it.seq,
                        "command", it.command,
                        // MISSION_ITEM_INT carries lat/lon as 1e7 degrees int
                        "lat", it.x / 1e7,
                        "lon", it.y / 1e7,
                        "alt", it.z,
                        "holdTime", (double) it.param1,
                        "frame", it.frame));
            }
            flightLog.mission(sysid, "mission download: " + items.size() + " items read back");
            return Map.of("status", "ok", "count", items.size(), "items", view);
        } catch (DroneCommandService.CommandException e) {
            log.warn("Mission download failed for sysid={}: {}", sysid, e.getMessage());
            return Map.of("status", "error", "result", e.getMessage());
        }
    }

    /**
     * Virtual joystick: MANUAL_CONTROL passthrough. Axes -1000..1000
     * (throttle 0..1000), fire-and-forget at ~10 Hz while a stick is held.
     */
    @PostMapping("/{sysid}/joystick")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> joystick(@PathVariable("sysid") int sysid,
                                        @RequestBody JsonNode body) {
        int x = body.path("x").asInt(0);
        int y = body.path("y").asInt(0);
        int z = body.path("z").asInt(500);
        int r = body.path("r").asInt(0);
        // FR: joystick 轴值范围校验（MANUAL_CONTROL 协议约束）
        if (x < -1000 || x > 1000) {
            throw new BadRequestException("joystick x must be in [-1000, 1000]");
        }
        if (y < -1000 || y > 1000) {
            throw new BadRequestException("joystick y must be in [-1000, 1000]");
        }
        if (z < 0 || z > 1000) {
            throw new BadRequestException("joystick z (throttle) must be in [0, 1000]");
        }
        if (r < -1000 || r > 1000) {
            throw new BadRequestException("joystick r (yaw) must be in [-1000, 1000]");
        }
        commands.manualControl(sysid, x, y, z, r);
        return Map.of("status", "ok");
    }

    /**
     * Upload a mission: {"items":[{"cmd":"waypoint","lat":22.59,"lon":113.93,
     * "alt":50,"holdTime":2}, ...]}. Runs the full MAVLink mission protocol.
     */
    @PostMapping("/{sysid}/mission")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> uploadMission(@PathVariable("sysid") int sysid,
                                             @RequestBody JsonNode body) {
        require(sysid);   // 404 for unknown device
        // FR: 解析前校验 items 数量上限（先检查再逐项解析，防止超大 payload
        // 在 parseMissionItems 中全量转换耗尽内存）。
        JsonNode rawItems = body.path("items");
        if (!rawItems.isArray() || rawItems.isEmpty()) {
            throw new BadRequestException("body must contain a non-empty 'items' array");
        }
        if (rawItems.size() > 1000) {
            throw new BadRequestException("mission items count " + rawItems.size()
                    + " exceeds maximum of 1000");
        }
        List<MissionItemRequest> items = parseMissionItems(body);
        List<MissionItemInt> mavItems = commands.toMissionItems(items, sysid);
        log.info("Uploading mission to sysid={}: {} items", sysid, mavItems.size());
        MissionUploadResult result = commands.uploadMission(sysid, mavItems);
        flightLog.mission(sysid, "mission upload: " + result.status()
                + " (" + mavItems.size() + " items)");
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("status", result.status());
        resp.put("uploaded", result.uploaded());
        if (result.error() != null) {
            resp.put("error", result.error());
        }
        return resp;
    }

    /**
     * Send a flight command: {"type":"arm"|"disarm"|"start_mission"|"rtl"|"takeoff",
     * "alt":50} - alt is only used by takeoff.
     */
    @PostMapping("/{sysid}/commands")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> sendCommand(@PathVariable("sysid") int sysid,
                                           @RequestBody JsonNode body) {
        require(sysid);
        String type = body.path("type").asText("");
        double alt = body.path("alt").asDouble(0);
        log.info("Command '{}' for sysid={} (alt={})", type, sysid, alt);
        String result;
        try {
            result = switch (type) {
                case "arm" -> {
                    commands.arm(sysid);
                    yield "ACCEPTED";
                }
                case "disarm" -> {
                    commands.disarm(sysid);
                    yield "ACCEPTED";
                }
                case "start_mission" -> {
                    commands.startMission(sysid);
                    yield "ACCEPTED";
                }
                case "rtl" -> {
                    commands.rtl(sysid);
                    yield "ACCEPTED";
                }
                case "takeoff" -> {
                    if (alt <= 0) {
                        throw new BadRequestException("takeoff requires alt > 0");
                    }
                    commands.takeoff(sysid, alt);
                    yield "ACCEPTED";
                }
                case "raw" -> {
                    // Passthrough for any MAV_CMD (camera protocol session
                    // commands 518/520/521 etc.): cmd + p1..p7 numeric.
                    int cmdId = body.path("cmd").asInt(-1);
                    if (cmdId < 0 || cmdId > 65535) {
                        throw new BadRequestException("raw command 'cmd' id must be in [0, 65535]");
                    }
                    int res = commands.command(sysid, cmdId,
                            (float) body.path("p1").asDouble(0),
                            (float) body.path("p2").asDouble(0),
                            (float) body.path("p3").asDouble(0),
                            (float) body.path("p4").asDouble(0),
                            (float) body.path("p5").asDouble(0),
                            (float) body.path("p6").asDouble(0),
                            (float) body.path("p7").asDouble(0));
                    yield "MAV_RESULT_" + res;
                }
                default -> throw new BadRequestException(
                        "unsupported command type '" + type + "'");
            };
        } catch (DroneCommandService.CommandException e) {
            log.warn("Command {} failed for sysid={}: {}", type, sysid, e.getMessage());
            flightLog.mission(sysid, "command " + type + " failed: " + e.getMessage());
            return Map.of("status", "error", "result", e.getMessage());
        }
        flightLog.mission(sysid, "command " + type + " accepted");
        return Map.of("status", "ok", "result", result);
    }

    // ------------------------------------------------------------------

    private DroneSnapshot require(int sysid) {
        DroneSnapshot s = registry.get(sysid);
        if (s == null) {
            throw new NotFoundException("unknown drone sysid " + sysid);
        }
        return s;
    }

    private static List<MissionItemRequest> parseMissionItems(JsonNode body) {
        JsonNode items = body.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new BadRequestException("body must contain a non-empty 'items' array");
        }
        List<MissionItemRequest> out = new ArrayList<>(items.size());
        for (JsonNode n : items) {
            if (n.path("cmd").asText("").isBlank()) {
                throw new BadRequestException("each item needs a 'cmd' field");
            }
            out.add(new MissionItemRequest(
                    n.path("cmd").asText(),
                    n.path("lat").asDouble(0),
                    n.path("lon").asDouble(0),
                    n.path("alt").asDouble(0),
                    n.path("holdTime").asDouble(0)));
        }
        return out;
    }
}
