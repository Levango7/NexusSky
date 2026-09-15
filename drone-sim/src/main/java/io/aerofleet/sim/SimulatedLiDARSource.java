package io.aerofleet.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟 LiDAR 数据源（M4 硬件抽象，FR-13）。
 * <p>
 * 基于合成障碍物 + 合成地形产出点云：在合成障碍物表面采样 3D 点 + 反射强度，
 * 并计算最近距离与点云统计。复用 M3 {@link SyntheticObstacle} 概念。
 * <p>
 * 位置由 droneNorth/droneEast/droneAlt 定义（无人机当前位置），点云相对该位置产出。
 */
public class SimulatedLiDARSource implements LiDARSource {

    private final List<SyntheticObstacle> obstacles;
    private final TerrainModel terrain;
    private final double droneNorth;
    private final double droneEast;
    private final double droneAlt;

    /**
     * @param obstacles  合成障碍物列表
     * @param terrain    合成地形模型
     * @param droneNorth 无人机北向坐标（米）
     * @param droneEast  无人机东向坐标（米）
     * @param droneAlt   无人机高度（米）
     */
    public SimulatedLiDARSource(List<SyntheticObstacle> obstacles, TerrainModel terrain,
                                double droneNorth, double droneEast, double droneAlt) {
        this.obstacles = obstacles != null ? obstacles : List.of();
        this.terrain = terrain != null ? terrain : TerrainModel.flat();
        this.droneNorth = droneNorth;
        this.droneEast = droneEast;
        this.droneAlt = droneAlt;
    }

    @Override
    public List<LidarPoint> pointCloud() {
        List<LidarPoint> cloud = new ArrayList<>();
        // 合成障碍物表面采样
        for (SyntheticObstacle obs : obstacles) {
            sampleObstacleSurface(obs, cloud);
        }
        // 合成地形采样
        sampleTerrain(cloud);
        return cloud;
    }

    @Override
    public double nearestDistance() {
        double nearest = Double.MAX_VALUE;
        for (SyntheticObstacle obs : obstacles) {
            double dist = Math.hypot(obs.north() - droneNorth, obs.east() - droneEast);
            nearest = Math.min(nearest, dist);
        }
        return nearest;
    }

    @Override
    public LidarStats pointCloudStats() {
        List<LidarPoint> cloud = pointCloud();
        int count = cloud.size();
        double nearest = nearestDistance();
        if (count == 0) {
            return new LidarStats(0, 0.0, 0.0, nearest);
        }
        double sumIntensity = 0;
        for (LidarPoint p : cloud) {
            sumIntensity += p.intensity();
        }
        double avgIntensity = sumIntensity / count;
        // 密度：点数归一化到 0-1（简化：点数 / 10000 上限）
        double density = Math.min(1.0, count / 10000.0);
        return new LidarStats(count, density, avgIntensity, nearest);
    }

    @Override
    public double[][] rangeImage() {
        // 简化：8×8 距离图，每个像素填最近距离
        double[][] image = new double[8][8];
        double nearest = nearestDistance();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                image[i][j] = nearest;
            }
        }
        return image;
    }

    /** 障碍物表面采样：在障碍物圆周上均匀采样 8 个点。 */
    private void sampleObstacleSurface(SyntheticObstacle obs, List<LidarPoint> cloud) {
        int samples = 8;
        for (int i = 0; i < samples; i++) {
            double angle = 2 * Math.PI * i / samples;
            double x = (obs.north() - droneNorth) + obs.radius() * Math.cos(angle);
            double y = (obs.east() - droneEast) + obs.radius() * Math.sin(angle);
            double z = -droneAlt;  // 障碍物在地面上
            double intensity = 0.8;  // 障碍物反射强度
            cloud.add(new LidarPoint(x, y, z, intensity));
        }
    }

    /** 地形采样：在无人机周围 4 个方向采样地面点。 */
    private void sampleTerrain(List<LidarPoint> cloud) {
        double[] offsets = {5, -5};
        for (double dn : offsets) {
            for (double de : offsets) {
                double north = droneNorth + dn;
                double east = droneEast + de;
                double elev = terrain.elevationAt(north, east);
                double x = dn;
                double y = de;
                double z = elev - droneAlt;
                double intensity = 0.3;  // 地面反射强度较低
                cloud.add(new LidarPoint(x, y, z, intensity));
            }
        }
    }
}