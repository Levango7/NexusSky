package io.aerofleet.sim;

import java.util.List;

/**
 * LiDAR 数据源抽象接口（M4 硬件抽象，FR-12）。
 * <p>
 * 输出点云/最近距离/点云统计/距离图。复用 M3 {@link DepthSource} 的
 * {@code NearestObstacle}/{@code PointCloudStats} 概念。
 * {@link SimulatedLiDARSource} 为模拟实现（合成障碍物 + 合成地形）。
 */
public interface LiDARSource {

    /** 点云列表（FR-12）。 */
    List<LidarPoint> pointCloud();

    /** 最近距离（FR-12）。无障碍返回 {@code Double.MAX_VALUE}。 */
    double nearestDistance();

    /** 点云统计（FR-12/数据约束 6.5）。 */
    LidarStats pointCloudStats();

    /** 距离图（FR-12，简化投影）。 */
    double[][] rangeImage();

    /** 单个 LiDAR 点（x/y/z/intensity）。 */
    record LidarPoint(double x, double y, double z, double intensity) {
    }

    /** 点云统计（点数/密度/平均强度/最近距离，数据约束 6.5）。 */
    record LidarStats(int pointCount, double density, double avgIntensity, double nearestDistance) {
    }
}