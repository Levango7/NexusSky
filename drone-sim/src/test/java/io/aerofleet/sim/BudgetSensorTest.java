package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 丐版传感器数据源单测（UltrasonicSource / OpticalFlowSource / BudgetThermalSource）。
 * 替代昂贵的相控阵雷达 / LiDAR / 热成像。
 */
class BudgetSensorTest {

    // ===== UltrasonicSource =====

    @Test
    @DisplayName("超声波 measure 返回 4 个读数")
    void ultrasonic_measure_returnsCorrectCount() {
        UltrasonicSource src = new UltrasonicSource();
        double[] trueDistances = {1.0, 2.0, 3.0, 0.5};
        double[] readings = src.measure(trueDistances);
        assertEquals(4, readings.length);
    }

    @Test
    @DisplayName("超声波读数在 [minRange, maxRange] 范围内")
    void ultrasonic_measure_withinRange() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        // 含超出范围的输入：0.005(<min)、5.0(>max)、正常值
        double[] trueDistances = {0.005, 5.0, 2.0, 0.5};
        double[] readings = src.measure(trueDistances);
        for (double r : readings) {
            assertTrue(r >= 0.02, "reading should be >= minRange 0.02, got " + r);
            assertTrue(r <= 4.0, "reading should be <= maxRange 4.0, got " + r);
        }
    }

    @Test
    @DisplayName("超声波有障碍时返回正确方向")
    void ultrasonic_nearestObstacle_detected() {
        UltrasonicSource src = new UltrasonicSource();
        // 后方（索引1）0.5m 有障碍，其他方向 3.0m
        double[] readings = {3.0, 0.5, 3.0, 3.0};
        int dir = src.nearestObstacleDirection(readings, 1.0);
        assertEquals(1, dir, "应检测到后方(1)有障碍");
    }

    @Test
    @DisplayName("超声波无障碍返回 -1")
    void ultrasonic_nearestObstacle_none_returnsMinus1() {
        UltrasonicSource src = new UltrasonicSource();
        double[] readings = {3.0, 3.0, 3.0, 3.0};
        int dir = src.nearestObstacleDirection(readings, 1.0);
        assertEquals(-1, dir, "无障碍应返回 -1");
    }

    // ===== OpticalFlowSource =====

    @Test
    @DisplayName("光流基本位移计算正确")
    void opticalFlow_computeDisplacement_basic() {
        OpticalFlowSource src = new OpticalFlowSource();
        double[] disp = src.computeDisplacement(1.0, 0.5, 1.0);
        // 量化到 0.1 网格：1.0→1.0, 0.5→0.5
        assertEquals(1.0, disp[0], 0.001, "dx 应为 1.0");
        assertEquals(0.5, disp[1], 0.001, "dy 应为 0.5");
    }

    @Test
    @DisplayName("光流超速返回 true")
    void opticalFlow_outOfRange_highSpeed() {
        OpticalFlowSource src = new OpticalFlowSource();
        assertTrue(src.outOfRange(8.0), "8.0 m/s 超过 7.4 应返回 true");
    }

    @Test
    @DisplayName("光流正常速度返回 false")
    void opticalFlow_inRange_normalSpeed() {
        OpticalFlowSource src = new OpticalFlowSource();
        assertFalse(src.outOfRange(5.0), "5.0 m/s 在范围内应返回 false");
    }

    // ===== BudgetThermalSource =====

    @Test
    @DisplayName("热源帧尺寸 32×24")
    void thermal_generateFrame_correctDimensions() {
        BudgetThermalSource src = new BudgetThermalSource(-40, 300, 1.0, new Random(42));
        double[][] frame = src.generateFrame(25.0, null);
        assertEquals(24, frame.length, "高度应为 24");
        assertEquals(32, frame[0].length, "宽度应为 32");
    }

    @Test
    @DisplayName("热源有热点时检测到")
    void thermal_detectHotspot_found() {
        BudgetThermalSource src = new BudgetThermalSource(-40, 300, 1.0, new Random(42));
        // 热源在中心 (16, 12)，强度 60°C，sigma 3
        double[][] heatSources = {{16.0, 12.0, 60.0, 3.0}};
        double[][] frame = src.generateFrame(25.0, heatSources);
        double[] hotspot = src.detectHotspot(frame, 50.0);
        assertNotNull(hotspot, "应检测到热点");
        assertTrue(hotspot[2] > 50.0, "热点温度应超过 50°C, got " + hotspot[2]);
    }

    @Test
    @DisplayName("热源无热点返回 null")
    void thermal_detectHotspot_none_returnsNull() {
        BudgetThermalSource src = new BudgetThermalSource(-40, 300, 1.0, new Random(42));
        // 仅环境温度 25°C，无热源
        double[][] frame = src.generateFrame(25.0, null);
        double[] hotspot = src.detectHotspot(frame, 50.0);
        assertNull(hotspot, "无热源应返回 null");
    }
}