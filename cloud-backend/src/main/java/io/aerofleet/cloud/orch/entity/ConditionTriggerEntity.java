package io.aerofleet.cloud.orch.entity;

import io.aerofleet.cloud.orch.enums.TriggerAction;
import io.aerofleet.cloud.orch.enums.TriggerType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 条件触发器持久化实体（JPA）。
 *
 * 保存编排计划中条件触发器的类型、条件表达式及触发动作配置。
 */
@Entity
@Table(name = "orch_trigger")
public class ConditionTriggerEntity {

    @Id
    @Column(length = 50)
    private String triggerId;

    @Column(nullable = false)
    private Long planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerType type;

    /** JSON 格式存储触发条件表达式 */
    @Column(columnDefinition = "TEXT")
    private String condition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerAction action;

    private Long targetPlanId;

    /** JPA 无参构造器（必需）。 */
    public ConditionTriggerEntity() {
    }

    public String getTriggerId() {
        return triggerId;
    }

    public void setTriggerId(String triggerId) {
        this.triggerId = triggerId;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
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