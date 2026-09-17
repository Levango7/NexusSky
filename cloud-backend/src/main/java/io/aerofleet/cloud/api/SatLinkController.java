package io.aerofleet.cloud.api;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卫星链路 REST 端点（M7 星-空-地多层级中继）。
 * <p>
 * 独立路径前缀 /api/v1/sat-link/*，既有端点不受影响（DFX 4.5）。
 * <p>
 * 端点清单：
 * <pre>
 * GET  /api/v1/sat-link/status           获取所有卫星链路状态
 * GET  /api/v1/sat-link/status/{satId}   获取单星链路状态
 * GET  /api/v1/sat-link/passes           获取所有过境计划
 * GET  /api/v1/sat-link/passes/{satId}   获取单星过境计划
 * GET  /api/v1/sat-link/routes           获取最近路由决策历史
 * GET  /api/v1/sat-link/strategy         获取当前切换策略
 * PUT  /api/v1/sat-link/strategy         设置切换策略（运行时热更新，FR-4.4.2）
 * GET  /api/v1/sat-link/constellation    获取星座配置（FR-4.4.3）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/sat-link")
public class SatLinkController {

    private final SatLinkMonitorService monitorService;

    public SatLinkController(SatLinkMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /** 获取所有卫星链路状态。 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAllStatus() {
        Map<Integer, SatLinkMonitorService.SatLinkSnapshot> all = monitorService.getAllLinkStatuses();
        List<Map<String, Object>> links = new ArrayList<>();
        for (SatLinkMonitorService.SatLinkSnapshot s : all.values()) {
            links.add(snapshotView(s));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", monitorService.currentVersion());
        result.put("satCount", links.size());
        result.put("links", links);
        return ResponseEntity.ok(result);
    }

    /** 获取单星链路状态；不存在返回 404。 */
    @GetMapping("/status/{satId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable("satId") int satId) {
        SatLinkMonitorService.SatLinkSnapshot s = monitorService.getLinkStatus(satId);
        if (s == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "satId " + satId + " not found in sat-link status"));
        }
        return ResponseEntity.ok(snapshotView(s));
    }

    /** 获取所有过境计划。 */
    @GetMapping("/passes")
    public ResponseEntity<Map<String, Object>> getAllPasses() {
        Map<Integer, List<SatLinkMonitorService.SatPassSnapshot>> all = monitorService.getAllPassSchedules();
        List<Map<String, Object>> passes = new ArrayList<>();
        for (Map.Entry<Integer, List<SatLinkMonitorService.SatPassSnapshot>> entry : all.entrySet()) {
            for (SatLinkMonitorService.SatPassSnapshot p : entry.getValue()) {
                passes.add(passView(p));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passCount", passes.size());
        result.put("passes", passes);
        return ResponseEntity.ok(result);
    }

    /** 获取单星过境计划；不存在返回 404。 */
    @GetMapping("/passes/{satId}")
    public ResponseEntity<Map<String, Object>> getPasses(@PathVariable("satId") int satId) {
        List<SatLinkMonitorService.SatPassSnapshot> list = monitorService.getPassSchedules(satId);
        if (list.isEmpty()) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "satId " + satId + " has no pass schedules"));
        }
        List<Map<String, Object>> passes = new ArrayList<>();
        for (SatLinkMonitorService.SatPassSnapshot p : list) {
            passes.add(passView(p));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("satId", satId);
        result.put("passCount", passes.size());
        result.put("passes", passes);
        return ResponseEntity.ok(result);
    }

    /** 获取最近路由决策历史。 */
    @GetMapping("/routes")
    public ResponseEntity<Map<String, Object>> getRoutes() {
        List<SatLinkMonitorService.RouteDecisionSnapshot> decisions = monitorService.getRouteDecisions();
        List<Map<String, Object>> routes = new ArrayList<>();
        for (SatLinkMonitorService.RouteDecisionSnapshot d : decisions) {
            routes.add(routeView(d));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routeCount", routes.size());
        result.put("routes", routes);
        return ResponseEntity.ok(result);
    }

    /** 获取当前切换策略。 */
    @GetMapping("/strategy")
    public ResponseEntity<Map<String, Object>> getStrategy() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", monitorService.getCurrentStrategy());
        result.put("validStrategies", List.of(
                "NEAR_FIRST", "DELAY_OPTIMAL", "BANDWIDTH_OPTIMAL", "RELIABILITY_OPTIMAL"));
        return ResponseEntity.ok(result);
    }

    /** 设置切换策略（运行时热更新，FR-4.4.2）。 */
    @PutMapping("/strategy")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> setStrategy(@RequestBody Map<String, String> body) {
        String strategy = body.get("strategy");
        if (strategy == null || strategy.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "strategy field is required",
                            "validStrategies", List.of(
                                    "NEAR_FIRST", "DELAY_OPTIMAL", "BANDWIDTH_OPTIMAL", "RELIABILITY_OPTIMAL")));
        }
        boolean success = monitorService.setStrategy(strategy);
        if (!success) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "invalid strategy: " + strategy,
                            "validStrategies", List.of(
                                    "NEAR_FIRST", "DELAY_OPTIMAL", "BANDWIDTH_OPTIMAL", "RELIABILITY_OPTIMAL")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", monitorService.getCurrentStrategy());
        result.put("message", "strategy updated successfully");
        return ResponseEntity.ok(result);
    }

    /** 获取星座配置（FR-4.4.3）。 */
    @GetMapping("/constellation")
    public ResponseEntity<Map<String, Object>> getConstellation() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("simulated", true);
        result.put("note", "LEO constellation config is managed by drone-sim SatRelayEngine");
        result.put("linkCount", monitorService.getAllLinkStatuses().size());
        return ResponseEntity.ok(result);
    }

    // ===== 视图辅助 =====

    private Map<String, Object> snapshotView(SatLinkMonitorService.SatLinkSnapshot s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("satId", s.satId());
        v.put("visible", s.visible() != 0);
        v.put("elevationDeg", s.elevationDeg());
        v.put("azimuthDeg", s.azimuthDeg());
        v.put("delayMs", s.delayMs());
        v.put("bandwidthMbps", s.bandwidthMbps());
        v.put("windowEndMs", s.windowEndMs());
        v.put("sharedUsers", s.sharedUsers());
        v.put("timestamp", s.timestamp());
        v.put("simulated", s.simulated());
        return v;
    }

    private Map<String, Object> passView(SatLinkMonitorService.SatPassSnapshot p) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("satId", p.satId());
        v.put("passStartMs", p.passStartMs());
        v.put("passEndMs", p.passEndMs());
        v.put("durationMs", p.passEndMs() - p.passStartMs());
        v.put("maxElevationDeg", p.maxElevationDeg());
        v.put("groundPointId", p.groundPointId());
        v.put("timestamp", p.timestamp());
        return v;
    }

    private Map<String, Object> routeView(SatLinkMonitorService.RouteDecisionSnapshot d) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sourceLayer", d.sourceLayer());
        v.put("targetLayer", d.targetLayer());
        v.put("chosenLayer", d.chosenLayer() == 255 ? null : d.chosenLayer());
        v.put("estimatedDelayMs", d.estimatedDelayMs() == 65535 ? -1 : d.estimatedDelayMs());
        v.put("pathNodes", d.pathNodes());
        v.put("strategy", d.strategy());
        v.put("decisionReason", d.decisionReason());
        v.put("timestamp", d.timestamp());
        return v;
    }
}