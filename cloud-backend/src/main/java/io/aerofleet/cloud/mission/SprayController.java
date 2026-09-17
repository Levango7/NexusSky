package io.aerofleet.cloud.mission;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 喷洒 REST 端点（FR-32，路径前缀 /api/v1/spray）。
 * <p>
 * 复用 {@link FormationController} 风格（@RestController + 请求体 DTO + 响应 Map），
 * 独立路径前缀，不修改现有 /api/v1/formation/* 端点（DFX 4.5）。
 * <p>
 * 端点清单：
 * <pre>
 *   POST   /api/v1/spray              创建喷洒任务（FR-12）
 *   GET    /api/v1/spray/{id}         查询喷洒任务状态（FR-15）
 *   POST   /api/v1/spray/{id}/control 控制喷洒任务（FR-14）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/spray")
public class SprayController {

    private final SprayTaskService sprayService;

    public SprayController(SprayTaskService sprayService) {
        this.sprayService = sprayService;
    }

    /** 创建喷洒任务（FR-12）。 */
    @PostMapping
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> create(@RequestBody @Valid SprayTaskRequest req) {
        if (req.waypoints == null || req.waypoints.size() < 2) {
            throw new BadRequestException("waypoints must have >= 2 points");
        }
        SprayTask task = sprayService.create(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.taskId());
        out.put("segments", task.segmentCount());
        out.put("state", task.state().name());
        out.put("totalArea", task.totalArea());
        return out;
    }

    /** 查询喷洒任务状态（FR-15）。 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") int id) {
        SprayTask task = sprayService.task(id);
        if (task == null) {
            throw new NotFoundException("spray task " + id + " not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.taskId());
        out.put("state", task.state().name());
        out.put("coveredArea", task.coveredArea());
        out.put("coverageRate", task.coverageRate());
        out.put("remainingChemical", task.remainingChemical());
        out.put("currentSegment", task.currentSegment());
        out.put("segmentCount", task.segmentCount());
        return out;
    }

    /** 控制喷洒任务（FR-14）。 */
    @PostMapping("/{id}/control")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> control(@PathVariable("id") int id,
                                       @RequestBody ControlRequest body) {
        Map<Integer, SprayTaskService.AckResult> results = sprayService.control(id, body.action);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", id);
        out.put("results", results);
        return out;
    }

    /** 无人机离线 → HTTP 409（FR-14 异常场景）。 */
    @ExceptionHandler(SprayTaskService.DroneOfflineException.class)
    public ResponseEntity<Object> droneOffline(SprayTaskService.DroneOfflineException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
    }

    /** 控制请求体 DTO。 */
    public static final class ControlRequest {
        public String action;  // START / PAUSE / STOP / EMERGENCY_STOP
    }
}
