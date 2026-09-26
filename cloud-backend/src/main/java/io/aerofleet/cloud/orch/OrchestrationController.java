package io.aerofleet.cloud.orch;

import io.aerofleet.cloud.orch.entity.ConditionTriggerEntity;
import io.aerofleet.cloud.orch.entity.OrchestrationPlanEntity;
import io.aerofleet.cloud.orch.entity.TaskStepEntity;
import io.aerofleet.cloud.orch.enums.ModuleType;
import io.aerofleet.cloud.orch.enums.StepAction;
import io.aerofleet.cloud.orch.enums.TriggerAction;
import io.aerofleet.cloud.orch.enums.TriggerType;

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

import java.util.ArrayList;
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
        List<TaskStepEntity> stepEntities = new ArrayList<>();
        if (req.getSteps() != null) {
            for (TaskStepRequest stepReq : req.getSteps()) {
                stepEntities.add(toTaskStepEntity(stepReq));
            }
        }

        List<ConditionTriggerEntity> triggerEntities = new ArrayList<>();
        if (req.getTriggers() != null) {
            for (TriggerRequest triggerReq : req.getTriggers()) {
                triggerEntities.add(toConditionTriggerEntity(triggerReq));
            }
        }

        Long planId = planService.createPlan(
                req.getName(), req.getResourcePool(), stepEntities, triggerEntities);
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

    // ==================== DTO → Entity 转换 ====================

    private TaskStepEntity toTaskStepEntity(TaskStepRequest req) {
        TaskStepEntity entity = new TaskStepEntity();
        entity.setStepId(req.getStepId());
        entity.setModule(req.getModule());
        entity.setAction(req.getAction());
        entity.setParams(req.getParams());
        entity.setRequiredResources(req.getRequiredResources());
        entity.setDependsOn(req.getDependsOn());
        entity.setContinueOnFailure(req.getContinueOnFailure());
        entity.setTimeoutMs(req.getTimeoutMs());
        return entity;
    }

    private ConditionTriggerEntity toConditionTriggerEntity(TriggerRequest req) {
        ConditionTriggerEntity entity = new ConditionTriggerEntity();
        entity.setTriggerId(req.getTriggerId());
        entity.setType(req.getType());
        entity.setCondition(req.getCondition());
        entity.setAction(req.getAction());
        entity.setTargetPlanId(req.getTargetPlanId());
        return entity;
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
        private List<TaskStepRequest> steps;
        private List<TriggerRequest> triggers;

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

        public List<TaskStepRequest> getSteps() {
            return steps;
        }

        public void setSteps(List<TaskStepRequest> steps) {
            this.steps = steps;
        }

        public List<TriggerRequest> getTriggers() {
            return triggers;
        }

        public void setTriggers(List<TriggerRequest> triggers) {
            this.triggers = triggers;
        }
    }

    /** 任务步骤请求 DTO，只暴露允许客户端设置的字段。 */
    public static class TaskStepRequest {

        private String stepId;
        private ModuleType module;
        private StepAction action;
        private String params;
        private String requiredResources;
        private String dependsOn;
        private Boolean continueOnFailure;
        private Long timeoutMs;

        public String getStepId() {
            return stepId;
        }

        public void setStepId(String stepId) {
            this.stepId = stepId;
        }

        public ModuleType getModule() {
            return module;
        }

        public void setModule(ModuleType module) {
            this.module = module;
        }

        public StepAction getAction() {
            return action;
        }

        public void setAction(StepAction action) {
            this.action = action;
        }

        public String getParams() {
            return params;
        }

        public void setParams(String params) {
            this.params = params;
        }

        public String getRequiredResources() {
            return requiredResources;
        }

        public void setRequiredResources(String requiredResources) {
            this.requiredResources = requiredResources;
        }

        public String getDependsOn() {
            return dependsOn;
        }

        public void setDependsOn(String dependsOn) {
            this.dependsOn = dependsOn;
        }

        public Boolean getContinueOnFailure() {
            return continueOnFailure;
        }

        public void setContinueOnFailure(Boolean continueOnFailure) {
            this.continueOnFailure = continueOnFailure;
        }

        public Long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /** 触发器请求 DTO，只暴露允许客户端设置的字段。 */
    public static class TriggerRequest {

        private String triggerId;
        private TriggerType type;
        private String condition;
        private TriggerAction action;
        private Long targetPlanId;

        public String getTriggerId() {
            return triggerId;
        }

        public void setTriggerId(String triggerId) {
            this.triggerId = triggerId;
        }

        public TriggerType getType() {
            return type;
        }

        public void setType(TriggerType type) {
            this.type = type;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public TriggerAction getAction() {
            return action;
        }

        public void setAction(TriggerAction action) {
            this.action = action;
        }

        public Long getTargetPlanId() {
            return targetPlanId;
        }

        public void setTargetPlanId(Long targetPlanId) {
            this.targetPlanId = targetPlanId;
        }
    }
}
