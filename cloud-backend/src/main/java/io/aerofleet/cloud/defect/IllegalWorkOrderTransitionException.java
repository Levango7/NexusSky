package io.aerofleet.cloud.defect;

/** 工单非法状态迁移（控制器映射 409 + 当前态/动作）。 */
public class IllegalWorkOrderTransitionException extends RuntimeException {
    public final String currentStatus;
    public final WorkOrderStateMachine.Transition transition;

    public IllegalWorkOrderTransitionException(String currentStatus,
                                               WorkOrderStateMachine.Transition t) {
        super("work order transition " + t + " rejected in state " + currentStatus);
        this.currentStatus = currentStatus;
        this.transition = t;
    }
}
