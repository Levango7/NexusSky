package io.aerofleet.cloud.orch.entity;

import io.aerofleet.cloud.orch.enums.PlanStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

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

    @OneToMany(mappedBy = "planId", cascade = CascadeType.ALL)
    private List<TaskStepEntity> steps = new ArrayList<>();

    @OneToMany(mappedBy = "planId", cascade = CascadeType.ALL)
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