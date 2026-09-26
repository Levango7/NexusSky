package io.aerofleet.cloud.orch.event;

import org.springframework.context.ApplicationEvent;

/**
 * 暂停计划事件，由触发器发布以请求暂停指定编排计划。
 * <p>
 * OrchestrationPlanService 监听此事件执行实际的暂停操作。
 */
public class PausePlanEvent extends ApplicationEvent {

    private final Long planId;
    private final String reason;

    public PausePlanEvent(Object source, Long planId, String reason) {
        super(source);
        this.planId = planId;
        this.reason = reason;
    }

    public Long getPlanId() {
        return planId;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "PausePlanEvent{planId=" + planId
                + ", reason='" + reason + '\'' + '}';
    }
}