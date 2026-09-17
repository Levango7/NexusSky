package io.aerofleet.cloud.api;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.cloud.vision.ObstacleAvoidanceController;
import io.aerofleet.cloud.vision.ObstacleConfig;
import io.aerofleet.cloud.vision.ObstacleStatus;
import io.aerofleet.mavlink.enums.AvoidanceMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 避障 REST 端点（M3 感知成像增强，FR-25/FR-26/FR-29）。
 * <p>
 * 独立路径前缀 /api/v1/obstacle/*，既有端点不受影响（DFX 4.5）。
 * <p>
 * 端点清单：
 * <pre>
 * POST /api/v1/obstacle/config          配置避障（FR-25）
 * GET  /api/v1/obstacle/config/{sysid}  查询配置（FR-25）
 * GET  /api/v1/obstacle/status/{sysid}  避障状态查询（FR-26）
 * POST /api/v1/obstacle/{sysid}/release 紧急悬停解除（FR-29）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/obstacle")
public class ObstacleController {

    private static final Logger log = LoggerFactory.getLogger(ObstacleController.class);

    private final ObstacleAvoidanceController controller;

    public ObstacleController(ObstacleAvoidanceController controller) {
        this.controller = controller;
    }

    /** FR-25 配置避障。 */
    @PostMapping("/config")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> configure(@RequestBody Map<String, Object> body) {
        try {
            int sysid = num(body, "sysid").intValue();
            double safetyDistanceM = num(body, "safetyDistanceM").doubleValue();
            double emergencyHoverM = num(body, "emergencyHoverM").doubleValue();
            AvoidanceMode mode = AvoidanceMode.valueOf(str(body, "mode"));
            boolean enabled = body.get("enabled") instanceof Boolean b ? b : false;
            double maxSpeedMs = num(body, "maxSpeedMs", 0.0);

            ObstacleConfig cfg = new ObstacleConfig(sysid, safetyDistanceM, emergencyHoverM,
                    mode, enabled, maxSpeedMs);
            ObstacleConfig stored = controller.configure(cfg);
            return ResponseEntity.ok(configView(stored));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** FR-25 查询配置。 */
    @GetMapping("/config/{sysid}")
    public ResponseEntity<Map<String, Object>> getConfig(@PathVariable("sysid") int sysid) {
        ObstacleConfig cfg = controller.getConfig(sysid);
        if (cfg == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "sysid " + sysid + " not configured"));
        }
        return ResponseEntity.ok(configView(cfg));
    }

    /** FR-26 避障状态查询。 */
    @GetMapping("/status/{sysid}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable("sysid") int sysid) {
        ObstacleStatus s = controller.getStatus(sysid);
        return ResponseEntity.ok(statusView(sysid, s));
    }

    /** FR-29 紧急悬停解除。 */
    @PostMapping("/{sysid}/release")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> release(@PathVariable("sysid") int sysid) {
        controller.release(sysid);
        return ResponseEntity.ok(Map.of("sysid", sysid, "released", true));
    }

    private Map<String, Object> configView(ObstacleConfig cfg) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", cfg.sysid());
        v.put("safetyDistanceM", cfg.safetyDistanceM());
        v.put("emergencyHoverM", cfg.emergencyHoverM());
        v.put("mode", cfg.mode().name());
        v.put("enabled", cfg.enabled());
        v.put("maxSpeedMs", cfg.maxSpeedMs());
        return v;
    }

    private Map<String, Object> statusView(int sysid, ObstacleStatus s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sysid", sysid);
        v.put("currentThreat", s.currentThreat.name());
        v.put("nearestDistance", s.nearestDistance);
        v.put("nearestDirectionDeg", s.nearestDirectionDeg);
        v.put("inEmergencyHover", s.inEmergencyHover);
        v.put("lastReportTime", s.lastReportTime);
        v.put("lastCommandTime", s.lastCommandTime);
        return v;
    }

    private static Number num(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) return n;
        throw new IllegalArgumentException("missing numeric field '" + key + "'");
    }

    private static double num(Map<String, Object> body, String key, double dflt) {
        Object v = body.get(key);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof String s) return s;
        throw new IllegalArgumentException("missing string field '" + key + "'");
    }
}