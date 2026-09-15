package io.aerofleet.sim;

/**
 * 深度感知抽象接口（M3 感知成像增强，FR-11）。
 * <p>
 * 深度/点云感知的接入点：输出深度图摘要/最近障碍距离与方向/点云统计。
 * <p>
 * 实现类：{@link SimulatedDepthSource}（模拟器合成障碍物深度）。
 * 未来接真深度相机/LiDAR 只需新增实现类。
 * <p>
 * 注：本里程碑不做完整 SLAM/三维重建，仅提供避障用距离/方向（spec 7.4）。
 */
public interface DepthSource {

    /**
     * 深度图矩阵（FR-11）。
     * <p>
     * 二维距离矩阵，每个像素对应相机到场景表面的距离（米）。
     * 无障碍位置填远距值（如 100m）。
     *
     * @return 深度图矩阵（米）
     */
    double[][] depthMap();

    /**
     * 最近障碍距离与方向（FR-11）。
     * <p>
     * 方向相对无人机航向归一化到 0-359°。无障碍时 distance=Double.MAX_VALUE。
     *
     * @return 最近障碍（距离 + 方向）
     */
    NearestObstacle nearestObstacle();

    /**
     * 点云统计（FR-11）。
     * <p>
     * 点数/密度/最近点距离。
     *
     * @return 点云统计
     */
    PointCloudStats pointCloudStats();

    /** 最近障碍（距离 + 方向）。 */
    record NearestObstacle(double distance, double directionDeg) {
    }

    /** 点云统计（点数 + 密度 + 最近距离）。 */
    record PointCloudStats(int pointCount, double density, double nearestDistance) {
    }
}