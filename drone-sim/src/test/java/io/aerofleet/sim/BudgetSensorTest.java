package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.ObstacleType;
import io.aerofleet.mavlink.enums.ThreatLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    // ===== UltrasonicSource 作为 DepthSource 接口 =====

    @Test
    @DisplayName("UltrasonicSource 实现 DepthSource 接口")
    void ultrasonic_implementsDepthSource() {
        UltrasonicSource src = new UltrasonicSource();
        assertInstanceOf(DepthSource.class, src, "UltrasonicSource 应实现 DepthSource");
    }

    @Test
    @DisplayName("depthMap 返回 4×4 矩阵，未测量时全填远距")
    void ultrasonic_depthMap_allFarBeforeMeasure() {
        UltrasonicSource src = new UltrasonicSource();
        double[][] map = src.depthMap();
        assertEquals(4, map.length, "深度图高度应为 4");
        assertEquals(4, map[0].length, "深度图宽度应为 4");
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(UltrasonicSource.FAR_DISTANCE_M, map[y][x],
                        "未测量时所有像素应为远距 " + UltrasonicSource.FAR_DISTANCE_M);
            }
        }
    }

    @Test
    @DisplayName("depthMap 按方向填充传感器读数")
    void ultrasonic_depthMap_fillsByDirection() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        // 前=1.0, 后=2.0, 左=3.0, 右=0.5
        src.measure(new double[]{1.0, 2.0, 3.0, 0.5});
        double[][] map = src.depthMap();
        // 第0行=前, 第3行=后, 第1-2行第0列=左, 第1-2行第3列=右, 中心=远距
        // 噪声 ±3mm，用容差验证
        assertEquals(1.0, map[0][0], 0.01, "前方向(第0行)应约等于 1.0");
        assertEquals(1.0, map[0][3], 0.01, "前方向(第0行)应约等于 1.0");
        assertEquals(2.0, map[3][0], 0.01, "后方向(第3行)应约等于 2.0");
        assertEquals(0.5, map[1][3], 0.01, "右方向(第3列)应约等于 0.5");
        assertEquals(3.0, map[2][0], 0.01, "左方向(第0列)应约等于 3.0");
        assertEquals(UltrasonicSource.FAR_DISTANCE_M, map[1][1], "中心区域应为远距");
        assertEquals(UltrasonicSource.FAR_DISTANCE_M, map[2][2], "中心区域应为远距");
    }

    @Test
    @DisplayName("nearestObstacle 返回最近障碍距离和方向")
    void ultrasonic_nearestObstacle_returnsClosest() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        // 前=3.0, 后=2.0, 左=3.0, 右=0.5 → 最近是右(270°)
        src.measure(new double[]{3.0, 2.0, 3.0, 0.5});
        DepthSource.NearestObstacle nearest = src.nearestObstacle();
        assertEquals(0.5, nearest.distance(), 0.01, "最近障碍距离应约等于 0.5");
        assertEquals(270, nearest.directionDeg(), "最近障碍方向应为 270°(右)");
    }

    @Test
    @DisplayName("nearestObstacle 前方最近返回 0°")
    void ultrasonic_nearestObstacle_frontDirection() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        // 前方 0.3m 最近
        src.measure(new double[]{0.3, 4.0, 4.0, 4.0});
        DepthSource.NearestObstacle nearest = src.nearestObstacle();
        assertEquals(0.3, nearest.distance(), 0.01, "最近障碍距离应约等于 0.3");
        assertEquals(0, nearest.directionDeg(), "前方方向应为 0°");
    }

    @Test
    @DisplayName("nearestObstacle 无障碍返回 MAX_VALUE")
    void ultrasonic_nearestObstacle_noObstacle_returnsMaxValue() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        // 全部达到 maxRange（无障碍）
        src.measure(new double[]{10.0, 10.0, 10.0, 10.0});
        DepthSource.NearestObstacle nearest = src.nearestObstacle();
        assertEquals(Double.MAX_VALUE, nearest.distance(), "无障碍应返回 MAX_VALUE");
    }

    @Test
    @DisplayName("nearestObstacle 未测量返回 MAX_VALUE")
    void ultrasonic_nearestObstacle_beforeMeasure_returnsMaxValue() {
        UltrasonicSource src = new UltrasonicSource();
        DepthSource.NearestObstacle nearest = src.nearestObstacle();
        assertEquals(Double.MAX_VALUE, nearest.distance(), "未测量应返回 MAX_VALUE");
    }

    @Test
    @DisplayName("pointCloudStats 返回正确统计")
    void ultrasonic_pointCloudStats_correctStats() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        // 前=0.3(障碍), 后=4.0(无), 左=1.0(障碍), 右=4.0(无) → 2 个方向有障碍
        src.measure(new double[]{0.3, 10.0, 1.0, 10.0});
        DepthSource.PointCloudStats stats = src.pointCloudStats();
        assertEquals(200, stats.pointCount(), "2 个方向有障碍 × 100 = 200 点");
        assertTrue(stats.density() > 0, "密度应大于 0");
        assertEquals(0.3, stats.nearestDistance(), 0.01, "最近距离应约等于 0.3");
    }

    @Test
    @DisplayName("pointCloudStats 无障碍返回零点")
    void ultrasonic_pointCloudStats_noObstacle_returnsZero() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        src.measure(new double[]{10.0, 10.0, 10.0, 10.0});
        DepthSource.PointCloudStats stats = src.pointCloudStats();
        assertEquals(0, stats.pointCount(), "无障碍应返回 0 点");
        assertEquals(0, stats.density(), "无障碍密度应为 0");
        assertEquals(Double.MAX_VALUE, stats.nearestDistance(), "无障碍最近距离为 MAX_VALUE");
    }

    // ===== BudgetThermalSource 作为 ThermalSource 接口 =====

    @Test
    @DisplayName("BudgetThermalSource 实现 ThermalSource 接口")
    void thermal_implementsThermalSource() {
        BudgetThermalSource src = new BudgetThermalSource();
        assertInstanceOf(ThermalSource.class, src, "BudgetThermalSource 应实现 ThermalSource");
    }

    @Test
    @DisplayName("analyze 返回正确的温度统计")
    void thermal_analyze_correctStats() {
        BudgetThermalSource src = new BudgetThermalSource();
        // 2×2 矩阵：mean=35, min=20, max=50, stdDev=sqrt(125)≈11.18
        double[][] matrix = {{20, 30}, {40, 50}};
        ThermalSource.ThermalResult result = src.analyze(matrix);
        assertEquals(35.0, result.mean(), 0.001, "均值应为 35");
        assertEquals(20.0, result.min(), 0.001, "最小值应为 20");
        assertEquals(50.0, result.max(), 0.001, "最大值应为 50");
        assertEquals(Math.sqrt(125), result.stdDev(), 0.001, "标准差应为 sqrt(125)");
    }

    @Test
    @DisplayName("analyze 均匀温度场无热点")
    void thermal_analyze_uniformField_noHotspots() {
        BudgetThermalSource src = new BudgetThermalSource();
        // 3×3 均匀 25°C
        double[][] matrix = {{25, 25, 25}, {25, 25, 25}, {25, 25, 25}};
        ThermalSource.ThermalResult result = src.analyze(matrix);
        assertTrue(result.hotspots().isEmpty(), "均匀温度场应无热点");
        assertEquals(0.0, result.stdDev(), "均匀温度场标准差应为 0");
    }

    @Test
    @DisplayName("detectHotspots 检测到局部极大值热点")
    void thermal_detectHotspots_findsHotspot() {
        BudgetThermalSource src = new BudgetThermalSource();
        // 3×3：中心 80°C 为局部极大值，其余 20°C
        double[][] matrix = {{20, 20, 20}, {20, 80, 20}, {20, 20, 20}};
        List<ThermalSource.Hotspot> hotspots = src.detectHotspots(matrix, 50.0);
        assertEquals(1, hotspots.size(), "应检测到 1 个热点");
        ThermalSource.Hotspot hs = hotspots.get(0);
        assertEquals(1, hs.u(), "热点 u 坐标应为 1");
        assertEquals(1, hs.v(), "热点 v 坐标应为 1");
        assertEquals(80.0, hs.tempC(), 0.001, "热点温度应为 80");
        assertTrue(hs.areaPx() >= 1, "热点面积应 >= 1");
    }

    @Test
    @DisplayName("detectHotspots 全低于阈值返回空列表")
    void thermal_detectHotspots_allBelowThreshold_returnsEmpty() {
        BudgetThermalSource src = new BudgetThermalSource();
        double[][] matrix = {{20, 21, 20}, {21, 22, 21}, {20, 21, 20}};
        List<ThermalSource.Hotspot> hotspots = src.detectHotspots(matrix, 100.0);
        assertTrue(hotspots.isEmpty(), "全部低于阈值应返回空列表");
    }

    @Test
    @DisplayName("detectHotspots 空矩阵返回空列表")
    void thermal_detectHotspots_emptyMatrix_returnsEmpty() {
        BudgetThermalSource src = new BudgetThermalSource();
        List<ThermalSource.Hotspot> hotspots = src.detectHotspots(new double[0][], 50.0);
        assertTrue(hotspots.isEmpty(), "空矩阵应返回空列表");
    }

    // ===== ObstacleDetector + UltrasonicSource 集成 =====

    @Test
    @DisplayName("ObstacleDetector 使用 UltrasonicSource 检测 CRITICAL 障碍")
    void obstacleDetector_withUltrasonic_criticalThreat() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        // 前方 0.3m 有障碍
        src.measure(new double[]{0.3, 10.0, 10.0, 10.0});
        // safety=2.0, emergency=0.5 → 0.3 < 0.5 → CRITICAL
        ObstacleDetector detector = new ObstacleDetector(src, 2.0, 0.5);
        ObstacleDetector.ObstacleReport report = detector.detect();
        assertEquals(ThreatLevel.CRITICAL, report.threat(), "0.3m 应为 CRITICAL");
        assertEquals(0, report.directionDeg(), "方向应为 0°(前)");
        assertEquals(ObstacleType.STATIC, report.type(), "类型应为 STATIC");
        assertEquals(0.3, report.distance(), 0.01, "距离应约等于 0.3");
    }

    @Test
    @DisplayName("ObstacleDetector 使用 UltrasonicSource 检测 HIGH 障碍")
    void obstacleDetector_withUltrasonic_highThreat() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        // 前方 1.0m 有障碍
        src.measure(new double[]{1.0, 10.0, 10.0, 10.0});
        // safety=2.0, emergency=0.5 → 0.5 ≤ 1.0 < 2.0 → HIGH
        ObstacleDetector detector = new ObstacleDetector(src, 2.0, 0.5);
        ObstacleDetector.ObstacleReport report = detector.detect();
        assertEquals(ThreatLevel.HIGH, report.threat(), "1.0m 应为 HIGH");
    }

    @Test
    @DisplayName("ObstacleDetector 使用 UltrasonicSource 无障碍返回 NONE")
    void obstacleDetector_withUltrasonic_noObstacle_returnsNone() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        // 全部无障碍（达到 maxRange）
        src.measure(new double[]{10.0, 10.0, 10.0, 10.0});
        ObstacleDetector detector = new ObstacleDetector(src, 2.0, 0.5);
        ObstacleDetector.ObstacleReport report = detector.detect();
        assertEquals(ThreatLevel.NONE, report.threat(), "无障碍应为 NONE");
        assertEquals(Double.MAX_VALUE, report.distance(), "无障碍距离应为 MAX_VALUE");
    }

    @Test
    @DisplayName("ObstacleDetector 使用 UltrasonicSource 右侧障碍方向正确")
    void obstacleDetector_withUltrasonic_rightSideDirection() {
        UltrasonicSource src = new UltrasonicSource(4.0, 4, new Random(42));
        // 右侧 1.0m 有障碍（safety=2.0, emergency=0.5 → 1.0 在 [0.5, 2.0) → HIGH）
        src.measure(new double[]{10.0, 10.0, 10.0, 1.0});
        ObstacleDetector detector = new ObstacleDetector(src, 2.0, 0.5);
        ObstacleDetector.ObstacleReport report = detector.detect();
        assertEquals(ThreatLevel.HIGH, report.threat(), "1.0m 应为 HIGH");
        assertEquals(270, report.directionDeg(), "方向应为 270°(右)");
    }
}