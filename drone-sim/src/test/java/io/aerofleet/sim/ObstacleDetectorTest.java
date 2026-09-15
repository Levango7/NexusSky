package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.ObstacleType;
import io.aerofleet.mavlink.enums.ThreatLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ObstacleDetector 单测（M3 感知成像增强，FR-13）。
 * <p>
 * 安全距离=10m，紧停阈值=3m：
 * <ul>
 *   <li>距离=2m → CRITICAL</li>
 *   <li>距离=8m → HIGH</li>
 *   <li>距离=15m → MEDIUM</li>
 *   <li>距离=30m → LOW</li>
 *   <li>距离=50m → NONE</li>
 * </ul>
 */
class ObstacleDetectorTest {

    private static ObstacleDetector detectorWithObstacleAt(double distance) {
        // 障碍在无人机正北 distance 米处
        SyntheticObstacle obs = new SyntheticObstacle(distance, 0, 0.5);
        SimulatedDepthSource depth = new SimulatedDepthSource(
                List.of(obs), 0, 0, 0);
        return new ObstacleDetector(depth, 10, 3);
    }

    @Test
    void criticalThreat() {
        // 距离=2m < 紧停阈值=3m → CRITICAL
        ObstacleDetector det = detectorWithObstacleAt(2);
        ObstacleDetector.ObstacleReport r = det.detect();
        assertEquals(ThreatLevel.CRITICAL, r.threat());
        assertEquals(2.0, r.distance(), 0.01);
    }

    @Test
    void highThreat() {
        // 距离=8m < 安全距离=10m → HIGH
        ObstacleDetector det = detectorWithObstacleAt(8);
        ObstacleDetector.ObstacleReport r = det.detect();
        assertEquals(ThreatLevel.HIGH, r.threat());
    }

    @Test
    void mediumThreat() {
        // 距离=15m < 2×安全距离=20m → MEDIUM
        ObstacleDetector det = detectorWithObstacleAt(15);
        ObstacleDetector.ObstacleReport r = det.detect();
        assertEquals(ThreatLevel.MEDIUM, r.threat());
    }

    @Test
    void lowThreat() {
        // 距离=30m < 4×安全距离=40m → LOW
        ObstacleDetector det = detectorWithObstacleAt(30);
        ObstacleDetector.ObstacleReport r = det.detect();
        assertEquals(ThreatLevel.LOW, r.threat());
    }

    @Test
    void noneThreat() {
        // 距离=50m ≥ 4×安全距离=40m → NONE
        ObstacleDetector det = detectorWithObstacleAt(50);
        ObstacleDetector.ObstacleReport r = det.detect();
        assertEquals(ThreatLevel.NONE, r.threat());
    }

    @Test
    void noObstacleNoneThreat() {
        SimulatedDepthSource empty = new SimulatedDepthSource(
                List.of(), 0, 0, 0);
        ObstacleDetector det = new ObstacleDetector(empty, 10, 3);
        ObstacleDetector.ObstacleReport r = det.detect();
        assertEquals(ThreatLevel.NONE, r.threat());
        assertEquals(Double.MAX_VALUE, r.distance());
        assertEquals(ObstacleType.UNKNOWN, r.type());
    }

    @Test
    void obstacleTypeStaticWhenPresent() {
        ObstacleDetector det = detectorWithObstacleAt(8);
        ObstacleDetector.ObstacleReport r = det.detect();
        assertEquals(ObstacleType.STATIC, r.type());
    }

    @Test
    void setThresholdsValidatesSafetyDistance() {
        SimulatedDepthSource depth = new SimulatedDepthSource(List.of(), 0, 0, 0);
        ObstacleDetector det = new ObstacleDetector(depth, 10, 3);
        // safetyDistance < 1 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> det.setThresholds(0.5, 0.2));
    }

    @Test
    void setThresholdsValidatesEmergencyHover() {
        SimulatedDepthSource depth = new SimulatedDepthSource(List.of(), 0, 0, 0);
        ObstacleDetector det = new ObstacleDetector(depth, 10, 3);
        // emergencyHover >= safetyDistance → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> det.setThresholds(10, 10));
        assertThrows(IllegalArgumentException.class,
                () -> det.setThresholds(10, 15));
    }

    @Test
    void setThresholdsUpdatesThresholds() {
        SimulatedDepthSource depth = new SimulatedDepthSource(
                List.of(new SyntheticObstacle(5, 0, 0.5)), 0, 0, 0);
        ObstacleDetector det = new ObstacleDetector(depth, 10, 3);
        // 初始：距离=5m → HIGH
        assertEquals(ThreatLevel.HIGH, det.detect().threat());
        // 更新阈值：safety=4, emergency=1 → 距离=5m → MEDIUM (5 < 2×4=8)
        det.setThresholds(4, 1);
        assertEquals(ThreatLevel.MEDIUM, det.detect().threat());
    }

    @Test
    void constructorValidatesThresholds() {
        SimulatedDepthSource depth = new SimulatedDepthSource(List.of(), 0, 0, 0);
        assertThrows(IllegalArgumentException.class,
                () -> new ObstacleDetector(depth, 0.5, 0.2));
        assertThrows(IllegalArgumentException.class,
                () -> new ObstacleDetector(depth, 10, 10));
    }
}