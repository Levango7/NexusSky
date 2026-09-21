package io.aerofleet.cloud.orch.event;

import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * 应急启动事件，当应急场景触发时发布。
 * <p>
 * 监听方据此暂停与应急资源有交集的运行中编排计划。
 */
public class EmergencyStartEvent extends ApplicationEvent {

    private final Long planId;
    private final List<Integer> droneIds;

    public EmergencyStartEvent(Object source, Long planId, List<Integer> droneIds) {
        super(source);
        this.planId = planId;
        this.droneIds = droneIds;
    }

    public Long getPlanId() {
        return planId;
    }

    public List<Integer> getDroneIds() {
        return droneIds;
    }

    @Override
    public String toString() {
        return "EmergencyStartEvent{planId=" + planId
                + ", droneIds=" + droneIds + '}';
    }
}