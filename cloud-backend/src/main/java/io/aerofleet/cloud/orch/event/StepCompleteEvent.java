package io.aerofleet.cloud.orch.event;

import org.springframework.context.ApplicationEvent;

/**
 * 步骤完成事件，当步骤执行成功时发布。
 * <p>
 * 监听方可据此驱动后续步骤或更新计划状态。
 */
public class StepCompleteEvent extends ApplicationEvent {

    private final Long planId;
    private final String stepId;
    private final String moduleTaskId;

    public StepCompleteEvent(Object source, Long planId, String stepId, String moduleTaskId) {
        super(source);
        this.planId = planId;
        this.stepId = stepId;
        this.moduleTaskId = moduleTaskId;
    }

    public Long getPlanId() {
        return planId;
    }

    public String getStepId() {
        return stepId;
    }

    public String getModuleTaskId() {
        return moduleTaskId;
    }

    @Override
    public String toString() {
        return "StepCompleteEvent{planId=" + planId
                + ", stepId=" + stepId
                + ", moduleTaskId='" + moduleTaskId + '\'' + '}';
    }
}