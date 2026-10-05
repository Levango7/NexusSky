package io.aerofleet.cloud.scheduling;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M10 集群调度 REST API */
@RestController
@RequestMapping("/api/v1/scheduling")
@RequireRole(Role.OBSERVER)
public class SchedulingController {
    private static final Logger log = LoggerFactory.getLogger(SchedulingController.class);

    private final TaskAssignmentService assignmentService;
    private final ConflictAvoidanceService conflictService;
    private final ConflictScanService conflictScanService;

    public SchedulingController(TaskAssignmentService assignmentService,
                                ConflictAvoidanceService conflictService,
                                ConflictScanService conflictScanService) {
        this.assignmentService = assignmentService;
        this.conflictService = conflictService;
        this.conflictScanService = conflictScanService;
    }

    @PostMapping("/tasks")
    @RequireRole(Role.OPERATOR)
    public AssignmentResult createTask(@RequestBody @Valid TaskRequest req) {
        log.info("Create task: {} type={} pri={}", req.getTaskId(), req.getTaskType(), req.getPriority());
        return assignmentService.assignTask(req);
    }

    @GetMapping("/tasks")
    public Map<String, AssignmentResult> listTasks() {
        return assignmentService.getAllAssignments();
    }

    @DeleteMapping("/tasks/{id}")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> cancelTask(@PathVariable String id) {
        boolean ok = assignmentService.cancelTask(id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", id);
        resp.put("cancelled", ok);
        return resp;
    }

    /**
     * 显式启动任务（生命周期沿）：标记执行中并发布 TaskStatus(30050) IN_PROGRESS 帧。
     */
    @PostMapping("/tasks/{id}/start")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> startTask(@PathVariable String id) {
        boolean ok = assignmentService.startTask(id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", id);
        resp.put("started", ok);
        return resp;
    }

    /**
     * 标记任务完成：解除无人机负载映射并发布 TaskStatus(30050) COMPLETED 帧。
     */
    @PostMapping("/tasks/{id}/complete")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> completeTask(@PathVariable String id) {
        boolean ok = assignmentService.completeTask(id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", id);
        resp.put("completed", ok);
        return resp;
    }

    @PostMapping("/conflicts/check")
    @RequireRole(Role.OPERATOR)
    public ConflictAvoidanceService.ConflictResult checkConflict(@RequestBody Map<String, Number> body) {
        // P3-fix(Minor): 缺少字段时返回 400 而非 NPE 导致的 500
        String[] required = {"lat1", "lon1", "alt1", "v1", "h1", "lat2", "lon2", "alt2", "v2", "h2"};
        for (String key : required) {
            if (body.get(key) == null) {
                throw new BadRequestException("missing required field: " + key);
            }
        }
        return conflictService.checkConflict(
                body.get("lat1").doubleValue(), body.get("lon1").doubleValue(),
                body.get("alt1").doubleValue(), body.get("v1").doubleValue(), body.get("h1").doubleValue(),
                body.get("lat2").doubleValue(), body.get("lon2").doubleValue(),
                body.get("alt2").doubleValue(), body.get("v2").doubleValue(), body.get("h2").doubleValue());
    }

    /**
     * 机队冲突扫描（边界清零新增）：对在线无人机构建 4D 预测航迹两两检测，
     * 每个冲突对发布 ConflictAlertMsg(30049) WS 帧。
     */
    @PostMapping("/conflicts/scan")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> scanConflicts() {
        List<ConflictAvoidanceService.ConflictPair> pairs = conflictScanService.scanOnce();
        List<Map<String, Object>> views = new java.util.ArrayList<>();
        for (ConflictAvoidanceService.ConflictPair p : pairs) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("sysid1", p.sysid1);
            v.put("sysid2", p.sysid2);
            v.put("minDistanceM", p.result.horizontalDistance);
            v.put("timeToConflictSec", p.result.timeToConflict);
            v.put("type", p.result.type);
            views.add(v);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conflictCount", pairs.size());
        resp.put("conflicts", views);
        return resp;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalTasks", assignmentService.getAllAssignments().size());
        s.put("status", "ACTIVE");
        return s;
    }
}