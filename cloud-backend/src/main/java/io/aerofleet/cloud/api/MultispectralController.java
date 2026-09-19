package io.aerofleet.cloud.api;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.cloud.vision.MultispectralTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 多光谱 REST 端点（M3 感知成像增强，FR-27）。
 * <p>
 * 独立路径前缀 /api/v1/multispectral/*，既有端点不受影响（DFX 4.5）。
 * <pre>
 * POST /api/v1/multispectral/tasks          创建任务（FR-27）
 * GET  /api/v1/multispectral/tasks/{taskId} 查询任务状态 + NDVI 结果（FR-27）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/multispectral")
public class MultispectralController {

    private final MultispectralTaskService service;

    public MultispectralController(MultispectralTaskService service) {
        this.service = service;
    }

    /** FR-27 创建多光谱任务。 */
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
            List<String> bands = (List<String>) body.getOrDefault("bands", List.of("NIR", "RED"));
            @SuppressWarnings("unchecked")
            Map<String, Object> region = (Map<String, Object>) body.get("region");
            MultispectralTaskService.MultispectralTask task = service.create(sysid, bands, region);
            return ResponseEntity.ok(task.toView());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** FR-27 查询任务状态 + NDVI 结果。 */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> getTask(@PathVariable("taskId") String taskId) {
        MultispectralTaskService.MultispectralTask task = service.get(taskId);
        if (task == null) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "task " + taskId + " not found"));
        }
        return ResponseEntity.ok(task.toView());
    }
}