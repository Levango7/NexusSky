package io.aerofleet.cloud.dock;

/**
 * 非法状态迁移（spec R2：硬约束，非法即拒绝）。
 * 控制器映射为 409 + 当前状态名，不吞不降级。
 */
public class IllegalDockTransitionException extends RuntimeException {
    public final DockState current;
    public final DockCommand command;

    public IllegalDockTransitionException(DockState current, DockCommand command) {
        super("command " + command.method() + " rejected: dock state=" + current
                + (current.transitional() ? " (transition in progress)" : ""));
        this.current = current;
        this.command = command;
    }
}
