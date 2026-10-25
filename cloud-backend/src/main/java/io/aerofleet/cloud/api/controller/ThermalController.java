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
     * 机队热成像最新读数（P2 #19，2026-10-24 接线；2026-10-26 条件③补逐点坐标）。
     *
     * <p>数据源是各机热成像遥测的快照（{@link DroneSnapshot} 热像字段，
     * {@code ThermalIngestListener} 写入）：
     * <ul>
     *   <li>聚合量来自 THERMAL_DATA(30012)；</li>
     *   <li>{@code hotspots} 来自 THERMAL_HOTSPOT(30064)——逐点像素坐标反投影出的
     *       地面经纬度（P2 #19 恢复条件③，见监听器注释里的输入来源与时效口径）。</li>
     * </ul>
     *
     * <p>逐点坐标契约：{@code lat}/{@code lon} 为 <b>null = 当时无法解算</b>（缺
     * GPS/高度/姿态或射线朝天），不是 0/NaN——前端必须如实显示"无法解算"。
     * {@code truncated=true} 表示飞机侧热点数超出单帧容量 3，被截断的是较冷的那些。
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
            item.put("hotspotTruncated", s.thermalHotspotsTruncated);
            List<Map<String, Object>> hotspots = new ArrayList<>();
            for (DroneSnapshot.ThermalHotspot h : s.thermalHotspots) {
                Map<String, Object> hv = new LinkedHashMap<>();
                hv.put("u", h.u());
                hv.put("v", h.v());
                hv.put("tempC", h.tempC());
                hv.put("lat", h.lat());
                hv.put("lon", h.lon());
                hotspots.add(hv);
            }
            item.put("hotspots", hotspots);
            items.add(item);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return ResponseEntity.ok(resp);
    }
}