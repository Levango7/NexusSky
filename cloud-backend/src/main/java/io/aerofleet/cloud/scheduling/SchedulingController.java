package io.aerofleet.cloud.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** M10 集群调度 REST API */
@RestController
@RequestMapping("/api/scheduling")
public class SchedulingController {
    private static final Logger log = LoggerFactory.getLogger(SchedulingController.class);

    private final TaskAssignmentService assignmentService;
    private final ConflictAvoidanceService conflictService;

    public SchedulingController(TaskAssignmentService assignmentService,
                                ConflictAvoidanceService conflictService) {
        this.assignmentService = assignmentService;
        this.conflictService = conflictService;
    }

    @PostMapping("/tasks")
    public AssignmentResult createTask(@RequestBody TaskRequest req) {
        log.info("Create task: {} type={} pri={}", req.getTaskId(), req.getTaskType(), req.getPriority());
        return assignmentService.assignTask(req);
    }

    @GetMapping("/tasks")
    public Map<String, AssignmentResult> listTasks() {
        return assignmentService.getAllAssignments();
    }

    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> cancelTask(@PathVariable String id) {
        boolean ok = assignmentService.cancelTask(id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", id);
        resp.put("cancelled", ok);
        return resp;
    }

    @PostMapping("/conflicts/check")
    public ConflictAvoidanceService.ConflictResult checkConflict(@RequestBody Map<String, Number> body) {
        return conflictService.checkConflict(
                body.get("lat1").doubleValue(), body.get("lon1").doubleValue(),
                body.get("alt1").doubleValue(), body.get("v1").doubleValue(), body.get("h1").doubleValue(),
                body.get("lat2").doubleValue(), body.get("lon2").doubleValue(),
                body.get("alt2").doubleValue(), body.get("v2").doubleValue(), body.get("h2").doubleValue());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalTasks", assignmentService.getAllAssignments().size());
        s.put("status", "ACTIVE");
        return s;
    }
}