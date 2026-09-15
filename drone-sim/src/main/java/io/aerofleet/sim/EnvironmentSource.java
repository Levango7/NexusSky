package io.aerofleet.sim;

/**
 * 环境数据源接口（FR-04 演化契约 + FR-06 真实数据源扩展点预留）。
 * <p>
 * 实现方负责维护环境状态并按 tick 演化；调用方通过 {@link #snapshot()} 获取不可变快照。
 * <ul>
 *   <li>{@link SimulatedEnvSource}：假数据源（场景脚本 + seed 伪随机，确定性）</li>
 *   <li>{@link RealEnvSource}：真实数据源占位（M0b 边界，未实现）</li>
 * </ul>
 */
public interface EnvironmentSource {
    /**
     * 当前环境状态快照。
     * 线程安全：返回不可变 record，调用方可安全持有引用。
     */
    EnvironmentState snapshot();

    /**
     * 演化一个 tick。
     * 由仿真 tick 线程独占调用，无需内部同步。
     *
     * @param dt 时间步长（秒）
     */
    void evolve(double dt);
}