package io.aerofleet.cloud.scenario;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * 场景启动 REST API（P0-2 应急救援场景库）。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code POST /api/scenarios/launch/{templateId}} — 一键启动场景（body: {lat, lon, overrides?}）</li>
 *   <li>{@code GET /api/scenarios/launch/active} — 查询进行中的场景</li>
 *   <li>{@code GET /api/scenarios/launch/history} — 查询历史启动记录</li>
 *   <li>{@code POST /api/scenarios/launch/{launchId}/abort} — 中止场景执行</li>
 *   <li>{@code GET /api/scenarios/launch/{launchId}/status} — 查询场景执行状态</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/scenarios/launch")
@Tag(name = "ScenarioLaunch", description = "应急救援场景一键启动、状态查询与中止")
public class ScenarioLaunchController {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLaunchController.class);

    private final ScenarioLauncherService launcherService;
    private final ScenarioTemplateController templateController;

    public ScenarioLaunchController(ScenarioLauncherService launcherService,
                                    ScenarioTemplateController templateController) {
        this.launcherService = launcherService;
        this.templateController = templateController;
    }

    /**
     * 一键启动场景。
     * <p>
     * 根据模板 ID 加载模板，结合请求体中的中心坐标与可选覆盖参数，调用
     * {@link ScenarioLauncherService#launch(ScenarioTemplate, double, double)} 启动。
     *
     * @param templateId 模板 ID
     * @param body       请求体：{lat, lon, overrides?}
     * @return 启动结果
     */
    @PostMapping("/{templateId}")
    @Operation(summary = "一键启动场景", description = "按模板 ID 加载模板并启动场景执行。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "启动结果"),
            @ApiResponse(responseCode = "404", description = "模板不存在")
    })
    public LaunchResult launch(@PathVariable("templateId") String templateId,
                               @RequestBody Map<String, Object> body) {
        ScenarioTemplate template = templateController.get(templateId);
        double lat = numDouble(body, "lat", 0.0);
        double lon = numDouble(body, "lon", 0.0);

        // 应用可选覆盖参数
        @SuppressWarnings("unchecked")
        Map<String, Object> overrides = (Map<String, Object>) body.get("overrides");
        if (overrides != null) {
            template = applyOverrides(template, overrides);
        }

        log.info("Launch requested: templateId={} lat={} lon={}", templateId, lat, lon);
        return launcherService.launch(template, lat, lon);
    }

    /**
     * 查询进行中的场景。
     *
     * @return 进行中的启动记录列表
     */
    @GetMapping("/active")
    @Operation(summary = "查询进行中的场景", description = "返回状态为 RUNNING 的启动记录。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "启动记录列表")
    })
    public List<Map<String, Object>> active() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ScenarioLauncherService.LaunchRecord r : launcherService.getActiveLaunches()) {
            result.add(toMap(r));
        }
        return result;
    }

    /**
     * 查询历史启动记录。
     *
     * @return 全部启动记录列表（按时间倒序）
     */
    @GetMapping("/history")
    @Operation(summary = "查询历史启动记录", description = "返回全部启动记录，按启动时间倒序。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "启动记录列表")
    })
    public List<Map<String, Object>> history() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ScenarioLauncherService.LaunchRecord r : launcherService.getHistory()) {
            result.add(toMap(r));
        }
        return result;
    }

    /**
     * 中止场景执行。
     *
     * @param launchId 启动 ID
     * @return 中止结果
     */
    @PostMapping("/{launchId}/abort")
    @Operation(summary = "中止场景执行", description = "按 launchId 中止进行中的场景。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "中止成功"),
            @ApiResponse(responseCode = "404", description = "启动记录不存在或已结束")
    })
    public ResponseEntity<Map<String, Object>> abort(@PathVariable("launchId") String launchId) {
        boolean ok = launcherService.abort(launchId);
        if (!ok) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "launch " + launchId + " not found or not running"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("launchId", launchId);
        result.put("status", "ABORTED");
        return ResponseEntity.ok(result);
    }

    /**
     * 查询场景执行状态。
     *
     * @param launchId 启动 ID
     * @return 启动记录详情
     */
    @GetMapping("/{launchId}/status")
    @Operation(summary = "查询场景执行状态", description = "按 launchId 查询启动记录详情。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "启动记录详情"),
            @ApiResponse(responseCode = "404", description = "启动记录不存在")
    })
    public ResponseEntity<Map<String, Object>> status(@PathVariable("launchId") String launchId) {
        ScenarioLauncherService.LaunchRecord r = launcherService.getStatus(launchId);
        if (r == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "launch " + launchId + " not found"));
        }
        return ResponseEntity.ok(toMap(r));
    }

    // =====================================================================
    // 辅助方法
    // =====================================================================

    private static double numDouble(Map<String, Object> body, String key, double def) {
        Object v = body.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("field '" + key + "' is not a valid number: " + v);
        }
    }

    private static int numInt(Map<String, Object> body, String key, int def) {
        Object v = body.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("field '" + key + "' is not a valid integer: " + v);
        }
    }

    /** 应用覆盖参数：创建模板副本并覆盖指定字段。 */
    private static ScenarioTemplate applyOverrides(ScenarioTemplate src, Map<String, Object> overrides) {
        ScenarioTemplate t = new ScenarioTemplate(src.getId(), src.getName(),
                src.getDisasterType(), src.getSeverityLevel(), src.getDescription(),
                src.getDroneCount(), src.getRadiusKm(), src.getHoverAltitudeM(),
                src.getDurationMin(), src.getCollaborationStrategy(),
                src.getCommunicationMode());
        t.setPresetWaypoints(src.getPresetWaypoints());
        t.setCreatedAt(src.getCreatedAt());
        t.setUpdatedAt(src.getUpdatedAt());

        if (overrides.containsKey("droneCount")) {
            t.setDroneCount(numInt(overrides, "droneCount", t.getDroneCount()));
        }
        if (overrides.containsKey("radiusKm")) {
            t.setRadiusKm(numDouble(overrides, "radiusKm", t.getRadiusKm()));
        }
        if (overrides.containsKey("hoverAltitudeM")) {
            t.setHoverAltitudeM(numDouble(overrides, "hoverAltitudeM", t.getHoverAltitudeM()));
        }
        if (overrides.containsKey("durationMin")) {
            t.setDurationMin(numInt(overrides, "durationMin", t.getDurationMin()));
        }
        return t;
    }

    /** 将 LaunchRecord 转为 Map（JSON 友好）。 */
    private static Map<String, Object> toMap(ScenarioLauncherService.LaunchRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("launchId", r.launchId);
        m.put("planId", r.planId);
        m.put("templateId", r.templateId);
        m.put("templateName", r.templateName);
        m.put("centerLat", r.centerLat);
        m.put("centerLon", r.centerLon);
        m.put("assignedDrones", r.assignedDrones);
        m.put("roleAssignments", r.roleAssignments);
        m.put("launchStatus", r.launchStatus.name());
        m.put("status", r.status.name());
        m.put("startTime", r.startTime.toString());
        if (r.endTime != null) {
            m.put("endTime", r.endTime.toString());
        }
        m.put("estimatedCoveragePct", r.estimatedCoveragePct);
        m.put("message", r.message);
        return m;
    }
}