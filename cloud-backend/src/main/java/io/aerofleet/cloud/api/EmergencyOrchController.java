package io.aerofleet.cloud.api;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
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

/**
 * 应急任务编排 REST 端点（M9 应急任务编排，FR-30）。
 * <p>
 * 独立路径前缀 /api/v1/emergency/*，既有端点不受影响（DFX 4.5）。
 * <p>
 * 端点清单：
 * <pre>
 * POST /api/v1/emergency/orch/start                 启动编排计划（自定义参数）
 * POST /api/v1/emergency/orch/{planId}/abort        中止编排计划
 * GET  /api/v1/emergency/orch/{planId}              查询计划状态
 * GET  /api/v1/emergency/orch/{planId}/progress     查询阶段进度
 * GET  /api/v1/emergency/orch/{planId}/coverage     查询覆盖信息
 * POST /api/v1/emergency/orch/{planId}/replan       重规划
 * POST /api/v1/emergency/orch/{planId}/priority     调整任务优先级
 * GET  /api/v1/emergency/orch/{planId}/priority/queue  查询优先级队列
 * GET  /api/v1/emergency/scenarios                  查询场景预设列表
 * POST /api/v1/emergency/scenarios/{type}/start     加载预设并启动（用预设默认值填充缺失参数）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/emergency")
public class EmergencyOrchController {

    private final EmergencyOrchService service;

    public EmergencyOrchController(EmergencyOrchService service) {
        this.service = service;
    }

    /**
     * 启动编排计划（自定义参数模式）。
     * <p>
     * 调用方需提供全部参数（scenarioType/centerLat/centerLon/radius/droneIds），
     * 适用于非预设场景的自定义编排。若需基于预设场景启动，请使用
     * {@link #startScenario(int, Map)}（自动填充预设默认值）。
     */
    @PostMapping("/orch/start")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
        int scenarioType = numInt(body, "scenarioType", 3);
        int centerLat = numInt(body, "centerLat", 0);
        int centerLon = numInt(body, "centerLon", 0);
        int radius = numInt(body, "radius", 1000);
        List<Integer> droneIds = droneIdsOf(body);
        long planId = service.start(scenarioType, centerLat, centerLon, radius, droneIds);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", "RUNNING");
        result.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(result);
    }

    /** 中止编排计划。 */
    @PostMapping("/orch/{planId}/abort")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> abort(@PathVariable("planId") long planId) {
        boolean ok = service.abort(planId);
        if (!ok) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "planId " + planId + " not found"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", "ABORTED");
        result.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(result);
    }

    /** 查询计划状态。 */
    @GetMapping("/orch/{planId}")
    public ResponseEntity<Map<String, Object>> getPlan(@PathVariable("planId") long planId) {
        Map<String, Object> plan = service.getPlan(planId);
        if (plan == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "planId " + planId + " not found"));
        }
        return ResponseEntity.ok(plan);
    }

    /** 查询阶段进度。 */
    @GetMapping("/orch/{planId}/progress")
    public ResponseEntity<Map<String, Object>> getProgress(@PathVariable("planId") long planId) {
        Map<String, Object> progress = service.getProgress(planId);
        if (progress == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "planId " + planId + " not found"));
        }
        return ResponseEntity.ok(progress);
    }

    /** 查询覆盖信息。 */
    @GetMapping("/orch/{planId}/coverage")
    public ResponseEntity<Map<String, Object>> getCoverage(@PathVariable("planId") long planId) {
        Map<String, Object> coverage = service.getCoverage(planId);
        if (coverage == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "planId " + planId + " not found"));
        }
        return ResponseEntity.ok(coverage);
    }

    /** 重规划。 */
    @PostMapping("/orch/{planId}/replan")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> replan(@PathVariable("planId") long planId,
                                                      @RequestBody Map<String, Object> body) {
        String reason = str(body, "reason", "");
        long newPlanId = service.replan(planId, reason);
        if (newPlanId < 0) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "planId " + planId + " not found"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("newPlanId", newPlanId);
        result.put("status", "RUNNING");
        return ResponseEntity.ok(result);
    }

    /** 调整任务优先级。 */
    @PostMapping("/orch/{planId}/priority")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> adjustPriority(@PathVariable("planId") long planId,
                                                              @RequestBody Map<String, Object> body) {
        long taskId = numLong(body, "taskId", 0);
        int priority = numInt(body, "priority", 4);
        String reason = str(body, "reason", "");
        Map<String, Object> result = service.adjustPriority(planId, taskId, priority, reason);
        if (result == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "planId " + planId + " not found"));
        }
        return ResponseEntity.ok(result);
    }

    /** 查询优先级队列。 */
    @GetMapping("/orch/{planId}/priority/queue")
    public ResponseEntity<Map<String, Object>> getPriorityQueue(@PathVariable("planId") long planId) {
        Map<String, Object> queue = service.getPriorityQueue(planId);
        if (queue == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "planId " + planId + " not found"));
        }
        return ResponseEntity.ok(queue);
    }

    /** 查询场景预设列表。 */
    @GetMapping("/scenarios")
    public ResponseEntity<Map<String, Object>> getScenarios() {
        List<Map<String, Object>> scenarios = service.getScenarios();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenarios", scenarios);
        return ResponseEntity.ok(result);
    }

    /**
     * 加载预设场景并启动（预设参数模式）。
     * <p>
     * 根据 type 查找预设场景，用预设默认值填充缺失的 radius/droneIds 参数，
     * 然后委托 {@link #start(Map)} 启动。与 start 的区别：start 要求调用方
     * 提供全部参数，startScenario 自动填充预设默认值，简化常见场景的调用。
     */
    @PostMapping("/scenarios/{type}/start")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> startScenario(@PathVariable("type") int type,
                                                             @RequestBody Map<String, Object> body) {
        int centerLat = numInt(body, "centerLat", 0);
        int centerLon = numInt(body, "centerLon", 0);
        int radius = numInt(body, "radius", 0);
        List<Integer> droneIds = droneIdsOf(body);
        long planId = service.startScenario(type, centerLat, centerLon, radius, droneIds);
        if (planId < 0) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "scenario type " + type + " not found"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", "RUNNING");
        return ResponseEntity.ok(result);
    }

    // =====================================================================
    // 请求解析辅助
    // =====================================================================

    /**
     * 从 body 提取 int 值，缺失返回默认值。
     * <p>
     * 数值格式异常时抛出 {@link BadRequestException}（400），而非让
     * Spring 兜底返回 500。
     */
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

    /**
     * 从 body 提取 long 值。
     * <p>
     * 数值格式异常时抛出 {@link BadRequestException}（400），而非让
     * Spring 兜底返回 500。
     */
    private static long numLong(Map<String, Object> body, String key, long def) {
        Object v = body.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("field '" + key + "' is not a valid long: " + v);
        }
    }

    /** 从 body 提取 String 值。 */
    private static String str(Map<String, Object> body, String key, String def) {
        Object v = body.get(key);
        return v == null ? def : v.toString();
    }

    /** 从 body 提取 droneIds 列表。 */
    @SuppressWarnings("unchecked")
    private static List<Integer> droneIdsOf(Map<String, Object> body) {
        Object v = body.get("droneIds");
        if (v == null) {
            return new ArrayList<>();
        }
        if (v instanceof List) {
            List<Integer> result = new ArrayList<>();
            for (Object o : (List<Object>) v) {
                if (o instanceof Number) {
                    result.add(((Number) o).intValue());
                } else {
                    result.add(Integer.parseInt(o.toString()));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }
}