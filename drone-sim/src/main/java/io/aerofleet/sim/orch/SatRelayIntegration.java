package io.aerofleet.sim.orch;

/**
 * 卫星中继集成接口（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 由 satrelay 子系统实现，编排引擎通过此接口在组网部署阶段
 * 分配中继角色、查询星链可用性、启用 HAAPS（高空伪卫星）中继。
 * 实现可为 null（表示卫星中继模块未启用）。
 */
public interface SatRelayIntegration {

    /**
     * 为无人机分配中继角色。
     *
     * @param droneId 无人机 ID
     * @param role    中继角色（如 0=普通, 1=中继, 2=HAAPS）
     */
    void assignRelayRole(int droneId, int role);

    /**
     * 查询卫星链路是否可用。
     *
     * @return true 表示星链可用
     */
    boolean isSatLinkAvailable();

    /**
     * 在指定无人机上启用 HAAPS 中继。
     *
     * @param droneId 无人机 ID
     */
    void enableHapsRelay(int droneId);
}