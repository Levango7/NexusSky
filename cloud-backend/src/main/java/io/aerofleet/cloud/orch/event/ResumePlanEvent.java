package io.aerofleet.cloud.orch.event;

import org.springframework.context.ApplicationEvent;

/**
 * 恢复计划事件，由触发器发布以请求恢复指定编排计划。
 * <p>
 * OrchestrationPlanService 监听此事件执行实际的恢复操作。
 */
public class ResumePlanEvent extends ApplicationEvent {

    private final Long planId;

    public ResumePlanEvent(Object source, Long planId) {
        super(source);
        this.planId = planId;
    }

    public Long getPlanId() {
        return planId;
    }

    @Override
    public String toString() {
        return "ResumePlanEvent{planId=" + planId + '}';
    }
}