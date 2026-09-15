package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatedDepthSource 单测（M3 感知成像增强，FR-11/FR-12）。
 */
class DepthSourceTest {

    @Test
    void nearestObstacleCorrect() {
        // 障碍在无人机前方 5m（north=5, east=0）
        SyntheticObstacle obs = new SyntheticObstacle(5, 0, 1);
        SimulatedDepthSource src = new SimulatedDepthSource(
                List.of(obs), 0, 0, 0);
        DepthSource.NearestObstacle nearest = src.nearestObstacle();
        assertEquals(5.0, nearest.distance(), 0.01, "distance should be 5.0m");
    }

    @Test
    void noObstacleMaxDistance() {
        SimulatedDepthSource src = new SimulatedDepthSource(
                List.of(), 0, 0, 0);
        DepthSource.NearestObstacle nearest = src.nearestObstacle();
        assertEquals(Double.MAX_VALUE, nearest.distance(), "no obstacle → MAX_VALUE");
    }

    @Test
    void nearestObstacleDirectionCorrect() {
        // 障碍在无人机东向 10m（north=0, east=10），航向 0° → 方向=90°
        SyntheticObstacle obs = new SyntheticObstacle(0, 10, 1);
        SimulatedDepthSource src = new SimulatedDepthSource(
                List.of(obs), 0, 0, 0);
        DepthSource.NearestObstacle nearest = src.nearestObstacle();
        assertEquals(10.0, nearest.distance(), 0.01);
        assertEquals(90, nearest.directionDeg(), 1, "east obstacle → direction 90°");
    }

    @Test
    void nearestObstacleDirectionRelativeToYaw() {
        // 障碍在正北 10m，无人机航向 90°（向东）→ 方向=0-90=-90 → 归一化 270°
        SyntheticObstacle obs = new SyntheticObstacle(10, 0, 1);
        SimulatedDepthSource src = new SimulatedDepthSource(
                List.of(obs), 0, 0, 90);
        DepthSource.NearestObstacle nearest = src.nearestObstacle();
        assertEquals(270, nearest.directionDeg(), 1, "north obstacle with 90° yaw → 270°");
    }

    @Test
    void multipleObstaclesReturnsNearest() {
        SyntheticObstacle near = new SyntheticObstacle(3, 0, 1);
        SyntheticObstacle far = new SyntheticObstacle(20, 0, 1);
        SimulatedDepthSource src = new SimulatedDepthSource(
                List.of(far, near), 0, 0, 0);
        assertEquals(3.0, src.nearestObstacle().distance(), 0.01);
    }

    @Test
    void depthMapFilledAtObstacle() {
        // 障碍在无人机位置 → 深度图对应像素应填入距离值（非远距）
        SyntheticObstacle obs = new SyntheticObstacle(0, 0, 2);
        SimulatedDepthSource src = new SimulatedDepthSource(
                List.of(obs), 0, 0, 0, 10, 10, 20);
        double[][] map = src.depthMap();
        // 中心像素应在障碍物内 → 距离值 < FAR_DISTANCE
        double center = map[5][5];
        assertTrue(center < SimulatedDepthSource.FAR_DISTANCE_M,
                "center pixel should be at obstacle distance, got " + center);
    }

    @Test
    void depthMapFarWhenNoObstacle() {
        SimulatedDepthSource src = new SimulatedDepthSource(
                List.of(), 0, 0, 0, 10, 10, 20);
        double[][] map = src.depthMap();
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                assertEquals(SimulatedDepthSource.FAR_DISTANCE_M, map[y][x],
                        "no obstacle → all far distance");
            }
        }
    }

    @Test
    void pointCloudStatsCorrect() {
        SyntheticObstacle obs = new SyntheticObstacle(5, 0, 1);
        SimulatedDepthSource src = new SimulatedDepthSource(
                List.of(obs), 0, 0, 0);
        DepthSource.PointCloudStats stats = src.pointCloudStats();
        assertEquals(100, stats.pointCount(), "1 obstacle × 100 points");
        assertEquals(5.0, stats.nearestDistance(), 0.01);
        assertTrue(stats.density() > 0);
    }

    @Test
    void nullObstaclesTreatedAsEmpty() {
        SimulatedDepthSource src = new SimulatedDepthSource(
                null, 0, 0, 0);
        assertEquals(Double.MAX_VALUE, src.nearestObstacle().distance());
    }
}