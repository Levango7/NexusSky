package io.aerofleet.cloud.mission.spray;

import io.aerofleet.cloud.mission.formation.Formation;
import io.aerofleet.cloud.mission.formation.FormationController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
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
@RequireRole(Role.OBSERVER)
public class SprayController {

    /** 喷洒任务最少航点数（起点 + 终点）。 */
    private static final int MIN_WAYPOINTS = 2;

    private final SprayTaskService sprayService;

    public SprayController(SprayTaskService sprayService) {
        this.sprayService = sprayService;
    }

    /** 创建喷洒任务（FR-12）。 */
    @PostMapping
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> create(@RequestBody @Valid SprayTaskRequest req) {
        if (req.waypoints == null || req.waypoints.size() < MIN_WAYPOINTS) {
            throw new BadRequestException("waypoints must have >= " + MIN_WAYPOINTS + " points");
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
        return view(task);
    }

    /**
     * 按 sysid 查该机**最近一次**喷洒任务状态。
     *
     * <p><b>为什么要有这个口径</b>：前端按"当前选中的无人机"轮询喷洒状态
     * （{@code SprayPanel} 每秒一次），而原端点只按**任务 id** 查 ——
     * 前端手里没有任务 id，于是去调并不存在的 {@code /spray/status/{sysid}}，
     * 面板永远空、而且 404 被 {@code .catch(() => null)} 静默吞掉
     * （2026-10-21 实测：前端 5 个面板共 12 处端点对不上后端，这是其中之一）。
     */
    @GetMapping("/by-sysid/{sysid}")
    public ResponseEntity<Map<String, Object>> bySysid(@PathVariable("sysid") int sysid) {
        List<SprayTask> tasks = sprayService.tasksByTenant().stream()
                .filter(t -> t.targetSysid() == sysid)
                .sorted(Comparator.comparingInt(SprayTask::taskId).reversed())
                .toList();
        if (tasks.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "no spray task for sysid " + sysid));
        }
        return ResponseEntity.ok(view(tasks.get(0)));
    }

    /** 喷洒任务视图（按 id 查与按 sysid 查共用，避免两处口径漂移）。 */
    private Map<String, Object> view(SprayTask task) {
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
