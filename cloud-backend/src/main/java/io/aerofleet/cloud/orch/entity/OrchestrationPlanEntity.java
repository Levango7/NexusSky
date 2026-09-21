package io.aerofleet.cloud.orch.entity;

import io.aerofleet.cloud.orch.enums.PauseReason;
import io.aerofleet.cloud.orch.enums.PlanStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排计划持久化实体（JPA）。
 *
 * 保存编排计划的核心配置、状态及关联的步骤与触发器列表。
 */
@Entity
@Table(name = "orch_plan")
public class OrchestrationPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long planId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanStatus status;

    /** JSON 格式存储无人机 ID 列表 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String resourcePool;

    private Long createTime;
    private Long startTime;
    private Long endTime;

    /** 暂停原因，用于区分应急暂停和手动暂停 */
    @Enumerated(EnumType.STRING)
    private PauseReason pauseReason;

    /** 步骤列表（非持久化，由 Service 层通过 Repository 查询） */
    @Transient
    private List<TaskStepEntity> steps = new ArrayList<>();

    /** 触发器列表（非持久化，由 Service 层通过 Repository 查询） */
    @Transient
    private List<ConditionTriggerEntity> triggers = new ArrayList<>();

    /** JPA 无参构造器（必需）。 */
    public OrchestrationPlanEntity() {
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public void setStatus(PlanStatus status) {
        this.status = status;
    }

    public String getResourcePool() {
        return resourcePool;
    }

    public void setResourcePool(String resourcePool) {
        this.resourcePool = resourcePool;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
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

    public PauseReason getPauseReason() {
        return pauseReason;
    }

    public void setPauseReason(PauseReason pauseReason) {
        this.pauseReason = pauseReason;
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