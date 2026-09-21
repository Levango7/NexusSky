package io.aerofleet.cloud.orch;

import io.aerofleet.cloud.orch.entity.ConditionTriggerEntity;
import io.aerofleet.cloud.orch.entity.OrchestrationPlanEntity;
import io.aerofleet.cloud.orch.entity.TaskStepEntity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排计划 REST 端点。
 * <p>
 * 独立路径前缀 /api/v1/orch，提供编排计划的创建、查询、生命周期管理和进度查询。
 * <p>
 * 端点清单：
 * <pre>
 * POST /api/v1/orch/plans                  创建编排计划
 * GET  /api/v1/orch/plans                  列出所有编排计划
 * GET  /api/v1/orch/plans/{planId}         查询指定计划详情
 * POST /api/v1/orch/plans/{planId}/start   启动计划
 * POST /api/v1/orch/plans/{planId}/pause   暂停计划
 * POST /api/v1/orch/plans/{planId}/resume  恢复计划
 * POST /api/v1/orch/plans/{planId}/abort   中止计划
 * GET  /api/v1/orch/plans/{planId}/progress 查询步骤进度
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/orch")
public class OrchestrationController {

    private final OrchestrationPlanService planService;

    @Autowired
    public OrchestrationController(OrchestrationPlanService planService) {
        this.planService = planService;
    }

    // ==================== 端点 ====================

    /** 创建编排计划。 */
    @PostMapping("/plans")
    public ResponseEntity<Map<String, Object>> createPlan(@RequestBody CreatePlanRequest req) {
        Long planId = planService.createPlan(
                req.getName(), req.getResourcePool(), req.getSteps(), req.getTriggers());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", "DRAFT");
        return ResponseEntity.ok(result);
    }

    /** 列出所有编排计划。 */
    @GetMapping("/plans")
    public ResponseEntity<List<OrchestrationPlanEntity>> listPlans() {
        return ResponseEntity.ok(planService.listPlans());
    }

    /** 查询指定计划详情。 */
    @GetMapping("/plans/{planId}")
    public ResponseEntity<OrchestrationPlanEntity> getPlan(@PathVariable("planId") Long planId) {
        OrchestrationPlanEntity plan = planService.getPlan(planId);
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(plan);
    }

    /** 启动计划。 */
    @PostMapping("/plans/{planId}/start")
    public ResponseEntity<Map<String, Object>> startPlan(@PathVariable("planId") Long planId) {
        planService.startPlan(planId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", "RUNNING");
        return ResponseEntity.ok(result);
    }

    /** 暂停计划。 */
    @PostMapping("/plans/{planId}/pause")
    public ResponseEntity<Map<String, Object>> pausePlan(@PathVariable("planId") Long planId) {
        planService.pausePlan(planId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", "PAUSED");
        return ResponseEntity.ok(result);
    }

    /** 恢复计划。 */
    @PostMapping("/plans/{planId}/resume")
    public ResponseEntity<Map<String, Object>> resumePlan(@PathVariable("planId") Long planId) {
        planService.resumePlan(planId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", "RUNNING");
        return ResponseEntity.ok(result);
    }

    /** 中止计划。 */
    @PostMapping("/plans/{planId}/abort")
    public ResponseEntity<Map<String, Object>> abortPlan(@PathVariable("planId") Long planId) {
        planService.abortPlan(planId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", "ABORTED");
        return ResponseEntity.ok(result);
    }

    /** 查询步骤进度。 */
    @GetMapping("/plans/{planId}/progress")
    public ResponseEntity<List<TaskStepEntity>> getProgress(@PathVariable("planId") Long planId) {
        return ResponseEntity.ok(planService.getProgress(planId));
    }

    // ==================== 异常处理 ====================

    /** 参数校验失败 → 400 Bad Request。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", "Bad Request");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /** 状态冲突 → 409 Conflict。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", "Conflict");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    // ==================== 请求体 DTO ====================

    /** 创建编排计划请求体。 */
    public static class CreatePlanRequest {

        private String name;
        private List<Integer> resourcePool;
        private List<TaskStepEntity> steps;
        private List<ConditionTriggerEntity> triggers;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<Integer> getResourcePool() {
            return resourcePool;
        }

        public void setResourcePool(List<Integer> resourcePool) {
            this.resourcePool = resourcePool;
        }

        public List<TaskStepEntity> getSteps() {
            return steps;
        }

        public void setSteps(List<TaskStepEntity> steps) {
            this.steps = steps;
        }

        public List<ConditionTriggerEntity> getTriggers() {
            return triggers;
        }

        public void setTriggers(List<ConditionTriggerEntity> triggers) {
            this.triggers = triggers;
        }
    }
}