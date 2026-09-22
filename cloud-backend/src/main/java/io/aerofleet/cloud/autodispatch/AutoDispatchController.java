package io.aerofleet.cloud.autodispatch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;

/**
 * 自动出警 REST API（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 端点前缀 {@code /api/autodispatch}，覆盖：
 * <ul>
 *   <li>手动触发自动出警（{@code POST /trigger}）</li>
 *   <li>出警历史查询（{@code GET /history}）</li>
 *   <li>进行中出警任务查询（{@code GET /active}）</li>
 *   <li>中止出警任务（{@code POST /{dispatchId}/abort}）</li>
 *   <li>配置查询与更新（{@code GET /config}、{@code PUT /config}）</li>
 * </ul>
 * <p>
 * 错误响应统一使用 {@code {"error": "..."}} 格式，与
 * {@link io.aerofleet.cloud.api.exception.ApiExceptionHandler} 风格一致。
 */
@RestController
@RequestMapping("/api/v1/autodispatch")
@Tag(name = "AutoDispatch", description = "自动出警 REST API：报警触发无人机自动派遣、出警历史/活跃任务查询、配置管理")
public class AutoDispatchController {


    private final AutoDispatchService service;

    public AutoDispatchController(AutoDispatchService service) {
        this.service = service;
    }

    /**
     * 手动触发自动出警。
     * <p>
     * body: {@code {"lat": 39.9, "lon": 116.3, "alarmId": "xxx", "droneCount": 1}}
     *
     * @param body 触发请求体
     * @return 派遣结果
     */
    @Operation(summary = "手动触发自动出警", description = "body: {lat, lon, alarmId, droneCount}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "派遣结果"),
            @ApiResponse(responseCode = "400", description = "请求体格式错误")
    })
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> trigger(@RequestBody Map<String, Object> body) {
        double lat = toDouble(body.get("lat"));
        double lon = toDouble(body.get("lon"));
        String alarmId = body.get("alarmId") == null
                ? "manual-" + System.currentTimeMillis()
                : String.valueOf(body.get("alarmId"));
        int droneCount = toInt(body.get("droneCount"));

        DispatchResult result = service.dispatchDrone(lat, lon, alarmId, droneCount);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dispatchId", result.getDispatchId());
        resp.put("status", result.getStatus().name());
        resp.put("dispatchedDrones", dispatchedToList(result.getDispatchedDrones()));
        resp.put("message", result.getMessage());
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    /**
     * 查询出警历史。
     *
     * @param limit 最多返回条数（默认 100）
     * @return 出警记录列表
     */
    @Operation(summary = "查询出警历史", description = "返回最近 N 条出警记录")
    @ApiResponse(responseCode = "200", description = "出警记录列表")
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        List<DispatchRecord> records = service.getHistory(limit);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", records.stream().map(AutoDispatchController::recordToMap).toList());
        resp.put("total", records.size());
        return ResponseEntity.ok(resp);
    }

    /**
     * 查询进行中的出警任务。
     */
    @Operation(summary = "查询进行中的出警任务")
    @ApiResponse(responseCode = "200", description = "活跃出警任务列表")
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> active() {
        List<DispatchRecord> records = service.getActiveRecords();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", records.stream().map(AutoDispatchController::recordToMap).toList());
        resp.put("total", records.size());
        return ResponseEntity.ok(resp);
    }

    /**
     * 中止出警任务。
     *
     * @param dispatchId 派遣 ID
     * @return 中止结果
     */
    @Operation(summary = "中止出警任务")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "中止成功"),
            @ApiResponse(responseCode = "404", description = "出警任务不存在")
    })
    @PostMapping("/{dispatchId}/abort")
    public ResponseEntity<Map<String, Object>> abort(@PathVariable("dispatchId") String dispatchId) {
        DispatchRecord record = service.abortDispatch(dispatchId);
        if (record == null) {
            throw new NotFoundException("dispatch not found: " + dispatchId);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dispatchId", dispatchId);
        resp.put("status", record.getStatus());
        resp.put("abortTime", record.getAbortTime());
        return ResponseEntity.ok(resp);
    }

    /**
     * 获取自动出警配置。
     */
    @Operation(summary = "获取自动出警配置")
    @ApiResponse(responseCode = "200", description = "当前配置")
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(configToMap(service.getConfig()));
    }

    /**
     * 更新自动出警配置。
     * <p>
     * body: {@code {"enabled": true, "minBatteryPct": 30, "maxDispatchDistanceM": 10000,
     * "defaultDroneCount": 1, "hoverAltitudeM": 50, "hoverDurationSec": 300}}
     */
    @Operation(summary = "更新自动出警配置", description = "字段级合并，未提供的字段保留原值")
    @ApiResponse(responseCode = "200", description = "更新后的配置")
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        boolean enabled = toBool(body.get("enabled"), service.getConfig().isEnabled());
        Integer minBatteryPct = toIntegerOrNull(body.get("minBatteryPct"));
        Integer maxDispatchDistanceM = toIntegerOrNull(body.get("maxDispatchDistanceM"));
        Integer defaultDroneCount = toIntegerOrNull(body.get("defaultDroneCount"));
        Integer hoverAltitudeM = toIntegerOrNull(body.get("hoverAltitudeM"));
        Integer hoverDurationSec = toIntegerOrNull(body.get("hoverDurationSec"));

        AutoDispatchConfig updated = service.updateConfig(enabled, minBatteryPct,
                maxDispatchDistanceM, defaultDroneCount, hoverAltitudeM, hoverDurationSec);
        return ResponseEntity.ok(configToMap(updated));
    }

    // =====================================================================
    // 序列化辅助
    // =====================================================================

    private static List<Map<String, Object>> dispatchedToList(
            List<DispatchResult.DispatchedDrone> drones) {
        List<Map<String, Object>> list = new ArrayList<>(drones.size());
        for (DispatchResult.DispatchedDrone d : drones) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sysid", d.getSysid());
            m.put("taskAssigned", d.isTaskAssigned());
            m.put("estimatedArrivalSec", d.getEstimatedArrivalSec());
            list.add(m);
        }
        return list;
    }

    private static Map<String, Object> recordToMap(DispatchRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dispatchId", r.getDispatchId());
        m.put("alarmId", r.getAlarmId());
        m.put("triggerTime", r.getTriggerTime());
        m.put("lat", r.getLat());
        m.put("lon", r.getLon());
        m.put("status", r.getStatus());
        m.put("dispatchedDrones", dispatchedToList(r.getDispatchedDrones()));
        m.put("abortTime", r.getAbortTime());
        m.put("completeTime", r.getCompleteTime());
        return m;
    }

    private static Map<String, Object> configToMap(AutoDispatchConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.isEnabled());
        m.put("minBatteryPct", c.getMinBatteryPct());
        m.put("maxDispatchDistanceM", c.getMaxDispatchDistanceM());
        m.put("defaultDroneCount", c.getDefaultDroneCount());
        m.put("hoverAltitudeM", c.getHoverAltitudeM());
        m.put("hoverDurationSec", c.getHoverDurationSec());
        return m;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            return Double.parseDouble(s);
        }
        return 0.0;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            return Integer.parseInt(s);
        }
        return 0;
    }

    private static Integer toIntegerOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean toBool(Object o, boolean defaultValue) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }
}