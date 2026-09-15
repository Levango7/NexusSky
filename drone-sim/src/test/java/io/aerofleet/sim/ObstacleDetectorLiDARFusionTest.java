package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.ThreatLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ObstacleDetector LiDAR 融合单测（M4 硬件抽象，FR-14/FR-36）。
 */
class ObstacleDetectorLiDARFusionTest {

    /** M3 既有行为不变：fusion=false → 从 DepthSource 读取。 */
    @Test
    void fusionFalseUsesDepthSource() {
        SyntheticObstacle obs = new SyntheticObstacle(5, 0, 0.5);
        SimulatedDepthSource depth = new SimulatedDepthSource(List.of(obs), 0, 0, 0);
        ObstacleDetector detector = new ObstacleDetector(depth, 10, 3);

        // 注入 LiDAR 但 fusion=false → 仍从 DepthSource 读取
        SimulatedLiDARSource lidar = new SimulatedLiDARSource(
                List.of(new SyntheticObstacle(20, 0, 1)), TerrainModel.flat(), 0, 0, 10);
        detector.setLidarSource(lidar, false);

        assertFalse(detector.isLidarFusionEnabled());
        ObstacleDetector.ObstacleReport r = detector.detect();
        // DepthSource 障碍在 5m → HIGH（< safety=10）
        assertEquals(5.0, r.distance(), 0.01);
        assertEquals(ThreatLevel.HIGH, r.threat());
    }

    /** FR-14 fusion=true → 从 LiDARSource 读取。 */
    @Test
    void fusionTrueUsesLidar() {
        SyntheticObstacle obs = new SyntheticObstacle(5, 0, 0.5);
        SimulatedDepthSource depth = new SimulatedDepthSource(List.of(obs), 0, 0, 0);
        ObstacleDetector detector = new ObstacleDetector(depth, 10, 3);

        // LiDAR 障碍在 20m（不同于 DepthSource 的 5m）
        SimulatedLiDARSource lidar = new SimulatedLiDARSource(
                List.of(new SyntheticObstacle(20, 0, 1)), TerrainModel.flat(), 0, 0, 10);
        detector.setLidarSource(lidar, true);

        assertTrue(detector.isLidarFusionEnabled());
        ObstacleDetector.ObstacleReport r = detector.detect();
        // LiDAR 障碍在 20m → LOW（< 4×safety=40）
        assertEquals(20.0, r.distance(), 0.01);
        assertEquals(ThreatLevel.LOW, r.threat());
    }

    /** fusion=true 但 lidar=null → 回退 DepthSource。 */
    @Test
    void fusionTrueButNullLidarUsesDepthSource() {
        SyntheticObstacle obs = new SyntheticObstacle(5, 0, 0.5);
        SimulatedDepthSource depth = new SimulatedDepthSource(List.of(obs), 0, 0, 0);
        ObstacleDetector detector = new ObstacleDetector(depth, 10, 3);

        detector.setLidarSource(null, true);
        assertFalse(detector.isLidarFusionEnabled());

        ObstacleDetector.ObstacleReport r = detector.detect();
        assertEquals(5.0, r.distance(), 0.01);
    }
}