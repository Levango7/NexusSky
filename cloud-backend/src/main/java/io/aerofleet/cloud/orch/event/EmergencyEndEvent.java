package io.aerofleet.cloud.orch.event;

import org.springframework.context.ApplicationEvent;

/**
 * 应急结束事件，当应急场景解除时发布。
 * <p>
 * 监听方据此推送恢复通知，由操作员手动恢复暂停的计划。
 */
public class EmergencyEndEvent extends ApplicationEvent {

    private final Long planId;

    public EmergencyEndEvent(Object source, Long planId) {
        super(source);
        this.planId = planId;
    }

    public Long getPlanId() {
        return planId;
    }

    @Override
    public String toString() {
        return "EmergencyEndEvent{planId=" + planId + '}';
    }
}