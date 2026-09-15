package io.aerofleet.sim;

/**
 * 通用执行机构接口（FR-02）。
 * <p>
 * 喷洒泵 {@link SprayPump} 与抛投器 {@link Gripper} 实现同一契约，
 * 由 {@link VirtualDrone} 在 tick 线程内统一驱动。
 * <p>
 * 线程安全：所有方法由 {@link VirtualDrone#tickOnce} 的 tick 线程独占调用；
 * {@link #getState()} 可由遥测发送线程并发读，实现类用 volatile 保证可见性。
 *
 * @see ActuatorStatus
 * @see ActuatorState
 */
public interface Actuator {

    /** 使能：状态从 DISABLED 转移到 IDLE（FR-04）。 */
    void enable();

    /** 禁用：状态转回 DISABLED 并停止一切活动（FR-04）。 */
    void disable();

    /**
     * 设置速率（FR-05）。
     * <p>
     * 当状态处于 IDLE 或 ACTIVE 时，将速率钳位到 [0, rateMax] 并更新当前速率；
     * rate &gt; 0 时状态转为 ACTIVE，rate == 0 时转为 IDLE。
     * DISABLED 状态下调用为空操作（命令拒绝由调用方处理）。
     *
     * @param rate 目标速率（喷洒泵 mL/s；抛投器无流量概念，空实现）
     */
    void setRate(double rate);

    /**
     * 查询当前状态（FR-02）。
     * <p>
     * 返回不可变 {@link ActuatorStatus}，含 enabled/rate/state 三字段。
     *
     * @return 当前状态快照
     */
    ActuatorStatus getState();
}