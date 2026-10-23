package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.cloud.vision.ThermalTaskService;
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

/**
 * 热成像 REST 端点（M3 感知成像增强，FR-28）。
 * <p>
 * 独立路径前缀 /api/v1/thermal/*，既有端点不受影响（DFX 4.5）。
 * <pre>
 * POST /api/v1/thermal/tasks          创建任务（FR-28）
 * GET  /api/v1/thermal/tasks/{taskId} 查询任务状态 + 温度场结果（FR-28）
 * GET  /api/v1/thermal/status         机队热成像最新读数（P2 #19，2026-10-24 接线）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/thermal")
@RequireRole(Role.OBSERVER)
public class ThermalController {

    private final ThermalTaskService service;
    private final DeviceRegistry registry;

    public ThermalController(ThermalTaskService service, DeviceRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    /** FR-28 创建热成像任务。 */
    @PostMapping("/tasks")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody Map<String, Object> body) {
        try {
            // P3-fix(Minor): sysid 缺失时返回友好错误消息而非 NPE 的 "null"
            Object sysidRaw = body.get("sysid");
            if (!(sysidRaw instanceof Number)) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "field 'sysid' is required and must be a number"));
            }
            int sysid = ((Number) sysidRaw).intValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> region = (Map<String, Object>) body.get("region");
            double threshold = body.get("hotspotThreshold") instanceof Number n
                    ? n.doubleValue() : 50.0;
            // 物理范围校验（异常 5.3.2）
            if (threshold < -273.15 || threshold > 1000) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "hotspot threshold out of physical range"));
            }
            ThermalTaskService.ThermalTask task = service.create(sysid, region, threshold);
            return ResponseEntity.ok(task.toView());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** FR-28 查询任务状态 + 温度场结果。 */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable("taskId") String taskId) {
        ThermalTaskService.ThermalTask task = service.get(taskId);
        if (task == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "task " + taskId + " not found"));
        }
        return ResponseEntity.ok(task.toView());
    }

    /**
     * 机队热成像最新读数（P2 #19，2026-10-24 接线）。
     *
     * <p>数据源是各机 THERMAL_DATA(30012) 遥测的快照（{@link DroneSnapshot} 热像字段，
     * {@code ThermalIngestListener} 写入）——机队级聚合读口径，不含逐点热源坐标
     * （那需要图像→地理位姿解算，登记在 docs/todo.md P2 #19 恢复条件 ③）。
     * 只列 online 且上报过（thermalUpdatedAtMs > 0）的机；租户口径由
     * {@code DeviceRegistry.all()} 按 TenantContext 过滤。
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DroneSnapshot s : registry.all()) {
            if (!s.online || s.thermalUpdatedAtMs == 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sysid", s.sysid);
            item.put("tempMean", s.thermalTempMean);
            item.put("tempMin", s.thermalTempMin);
            item.put("tempMax", s.thermalTempMax);
            item.put("tempStdDev", s.thermalTempStdDev);
            item.put("hotspotCount", s.thermalHotspotCount);
            item.put("updatedAtMs", s.thermalUpdatedAtMs);
            items.add(item);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return ResponseEntity.ok(resp);
    }
}