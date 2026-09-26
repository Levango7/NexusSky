package io.aerofleet.cloud.orch.entity;

import io.aerofleet.cloud.orch.enums.ModuleType;
import io.aerofleet.cloud.orch.enums.StepAction;
import io.aerofleet.cloud.orch.enums.StepStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 任务步骤持久化实体（JPA）。
 *
 * 保存编排计划中单个步骤的配置、依赖关系及执行状态。
 */
@Entity
@Table(name = "orch_step", uniqueConstraints = {
        @UniqueConstraint(name = "uk_plan_step", columnNames = {"planId", "stepId"})
})
public class TaskStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50)
    private String stepId;

    @Column(nullable = false)
    private Long planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModuleType module;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepAction action;

    /** JSON 格式存储步骤参数 */
    @Column(columnDefinition = "TEXT")
    private String params;

    /** JSON 格式存储依赖步骤 ID 列表，如 ["step-1","step-2"] */
    @Column(columnDefinition = "TEXT")
    private String dependsOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepStatus status;

    /** JSON 格式存储所需资源 ID 列表，如 [1,2] */
    @Column(columnDefinition = "TEXT")
    private String requiredResources;

    private Long timeoutMs;
    private Boolean continueOnFailure;
    private Long startTime;
    private Long endTime;
    private String failReason;

    /** 模块返回的任务 ID */
    private String moduleTaskId;

    /** 租户 ID（数据隔离）。 */
    @Column(name = "tenant_id")
    private Integer tenantId;

    /** JPA 无参构造器（必需）。 */
    public TaskStepEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStepId() {
        return stepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
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

    public String getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(String dependsOn) {
        this.dependsOn = dependsOn;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
    }

    public String getRequiredResources() {
        return requiredResources;
    }

    public void setRequiredResources(String requiredResources) {
        this.requiredResources = requiredResources;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Boolean getContinueOnFailure() {
        return continueOnFailure;
    }

    public void setContinueOnFailure(Boolean continueOnFailure) {
        this.continueOnFailure = continueOnFailure;
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public String getModuleTaskId() {
        return moduleTaskId;
    }

    public void setModuleTaskId(String moduleTaskId) {
        this.moduleTaskId = moduleTaskId;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer tenantId) {
        this.tenantId = tenantId;
    }
}