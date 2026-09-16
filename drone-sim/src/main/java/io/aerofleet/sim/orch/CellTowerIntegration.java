package io.aerofleet.sim.orch;

/**
 * 基站通信集成接口（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 由 celltower 子系统实现，编排引擎通过此接口在组网部署阶段
 * 创建/移除无人机基站、查询覆盖区域、触发切换。实现可为 null（表示基站模块未启用）。
 */
public interface CellTowerIntegration {

    /**
     * 在指定无人机上创建基站。
     *
     * @param droneId 无人机 ID
     * @param cellType 基站类型（如 LTE=0, NR=1）
     * @param lat     纬度（度）
     * @param lon     经度（度）
     * @param txPower 发射功率（dBm）
     */
    void createCellTower(int droneId, int cellType, double lat, double lon, int txPower);

    /**
     * 移除指定无人机上的基站。
     *
     * @param droneId 无人机 ID
     */
    void removeCellTower(int droneId);

    /**
     * 查询指定无人机基站的覆盖区域面积（km²）。
     *
     * @param droneId 无人机 ID
     * @return 覆盖面积
     */
    double getCoverageArea(int droneId);

    /**
     * 触发基站切换（从 fromDroneId 切换到 toDroneId）。
     *
     * @param fromDroneId 源基站无人机 ID
     * @param toDroneId   目标基站无人机 ID
     */
    void triggerHandover(int fromDroneId, int toDroneId);
}