package io.aerofleet.cloud.orch.event;

import org.springframework.context.ApplicationEvent;

/**
 * 步骤失败事件，当步骤执行失败或超时时发布。
 * <p>
 * 监听方可据此触发补偿逻辑、中止计划或通知上层。
 */
public class StepFailEvent extends ApplicationEvent {

    private final Long planId;
    private final String stepId;
    private final String failReason;

    public StepFailEvent(Object source, Long planId, String stepId, String failReason) {
        super(source);
        this.planId = planId;
        this.stepId = stepId;
        this.failReason = failReason;
    }

    public Long getPlanId() {
        return planId;
    }

    public String getStepId() {
        return stepId;
    }

    public String getFailReason() {
        return failReason;
    }

    @Override
    public String toString() {
        return "StepFailEvent{planId=" + planId
                + ", stepId=" + stepId
                + ", failReason='" + failReason + '\'' + '}';
    }
}