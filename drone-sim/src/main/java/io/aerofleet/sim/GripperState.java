package io.aerofleet.sim;

/**
 * 抛投器状态枚举（FR-19）。
 * <p>
 * 状态机：{@link #IDLE} → {@link #GRABBING} → {@link #HOLDING} → {@link #RELEASING} → {@link #RELEASED} → {@link #IDLE}；
 * 每个转移由对应命令驱动，超时（默认 2s）转 {@link #FAULT}。
 */
public enum GripperState {
    IDLE,
    GRABBING,
    HOLDING,
    RELEASING,
    RELEASED,
    FAULT
}