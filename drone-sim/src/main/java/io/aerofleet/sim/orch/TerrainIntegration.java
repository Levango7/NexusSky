package io.aerofleet.sim.orch;

/**
 * 地形环境集成接口（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 由 terrain 子系统实现，编排引擎通过此接口在灾区测绘阶段
 * 查询地形类型、RF 衰减、空域许可。实现可为 null（表示地形模块未启用）。
 */
public interface TerrainIntegration {

    /**
     * 查询指定坐标的地形类型。
     *
     * @param lat 纬度（度）
     * @param lon 经度（度）
     * @return 地形类型（如 0=平原, 1=山地, 2=水域, 3=建筑）
     */
    int getTerrainType(double lat, double lon);

    /**
     * 查询两点之间的 RF 衰减（dB）。
     *
     * @param lat1 源纬度
     * @param lon1 源经度
     * @param lat2 目标纬度
     * @param lon2 目标经度
     * @return 衰减值（dB）
     */
    double getRfAttenuation(double lat1, double lon1, double lat2, double lon2);

    /**
     * 查询指定坐标与高度是否允许飞行。
     *
     * @param lat 纬度
     * @param lon 经度
     * @param alt 高度（m）
     * @return true 表示允许飞行
     */
    boolean isFlightAllowed(double lat, double lon, double alt);
}