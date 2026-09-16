package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatedLiDARSource 单测（M4 硬件抽象，FR-12/FR-13）。
 */
class SimulatedLiDARSourceTest {

    @Test
    void nearestDistanceCorrect() {
        // 障碍物在北向 5m 处，无人机高度 10m → 3D 距离 = sqrt(5² + 0² + 10²) = sqrt(125)
        SyntheticObstacle obs = new SyntheticObstacle(5, 0, 1);
        SimulatedLiDARSource lidar = new SimulatedLiDARSource(
                List.of(obs), TerrainModel.flat(), 0, 0, 10);
        assertEquals(Math.sqrt(125), lidar.nearestDistance(), 0.01);
    }

    @Test
    void noObstacleMaxDistance() {
        SimulatedLiDARSource lidar = new SimulatedLiDARSource(
                List.of(), TerrainModel.flat(), 0, 0, 10);
        assertEquals(Double.MAX_VALUE, lidar.nearestDistance());
    }

    @Test
    void pointCloudNotEmpty() {
        SyntheticObstacle obs = new SyntheticObstacle(5, 0, 1);
        SimulatedLiDARSource lidar = new SimulatedLiDARSource(
                List.of(obs), TerrainModel.flat(), 0, 0, 10);
        List<LiDARSource.LidarPoint> cloud = lidar.pointCloud();
        // 障碍物 8 点 + 地形 4 点 = 12 点
        assertTrue(cloud.size() > 0);
        assertTrue(cloud.size() >= 12);
    }

    @Test
    void pointCloudStatsCorrect() {
        // 障碍物在北向 5m 处，无人机高度 10m → 3D 距离 = sqrt(125)（M4 代码审查 #5）
        SyntheticObstacle obs = new SyntheticObstacle(5, 0, 1);
        SimulatedLiDARSource lidar = new SimulatedLiDARSource(
                List.of(obs), TerrainModel.flat(), 0, 0, 10);
        LiDARSource.LidarStats stats = lidar.pointCloudStats();
        assertTrue(stats.pointCount() > 0);
        assertTrue(stats.density() >= 0 && stats.density() <= 1);
        assertTrue(stats.avgIntensity() >= 0 && stats.avgIntensity() <= 1);
        assertEquals(Math.sqrt(125), stats.nearestDistance(), 0.01);
    }
}