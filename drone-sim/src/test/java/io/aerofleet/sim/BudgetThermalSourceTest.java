package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BudgetThermalSource 单测（AMG8833 8×8 红外热源阵列传感器模拟）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>帧尺寸 8×8、温度范围 0-80°C</li>
 *   <li>generateFrame 热源合成 + 噪声</li>
 *   <li>detectHeatSources 人体热源检测（>37°C）+ 置信度</li>
 *   <li>detectHotspot 最热像素检测</li>
 *   <li>ThermalSource 接口兼容（analyze / detectHotspots）</li>
 * </ul>
 */
class BudgetThermalSourceTest {

    // ===== 常量与基本属性 =====

    @Test
    @DisplayName("AMG8833 帧尺寸为 8×8")
    void frameSize_is8x8() {
        BudgetThermalSource src = new BudgetThermalSource();
        assertEquals(8, BudgetThermalSource.WIDTH, "宽度应为 8");
        assertEquals(8, BudgetThermalSource.HEIGHT, "高度应为 8");
        assertEquals(8, src.width(), "width() 应返回 8");
        assertEquals(8, src.height(), "height() 应返回 8");
    }

    @Test
    @DisplayName("AMG8833 温度范围为 0-80°C")
    void tempRange_is0to80() {
        assertEquals(0.0, BudgetThermalSource.MIN_TEMP_C, "最低温度应为 0°C");
        assertEquals(80.0, BudgetThermalSource.MAX_TEMP_C, "最高温度应为 80°C");
        assertEquals(2.5, BudgetThermalSource.ACCURACY_C, "精度应为 ±2.5°C");
    }

    @Test
    @DisplayName("人体热源检测阈值为 37°C")
    void humanTempThreshold_is37() {
        assertEquals(37.0, BudgetThermalSource.HUMAN_TEMP_THRESHOLD,
                "人体热源阈值应为 37°C");
    }

    // ===== generateFrame =====

    @Test
    @DisplayName("generateFrame 返回 8×8 温度矩阵")
    void generateFrame_correctDimensions() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 2.5, new Random(42));
        double[][] frame = src.generateFrame(25.0, null);
        assertEquals(8, frame.length, "高度应为 8");
        assertEquals(8, frame[0].length, "宽度应为 8");
    }

    @Test
    @DisplayName("generateFrame 无热源时温度接近环境温度")
    void generateFrame_noHeatSource_closeToAmbient() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 0.001, new Random(42));
        double[][] frame = src.generateFrame(25.0, null);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals(25.0, frame[y][x], 0.002,
                        "无热源时温度应接近环境温度（±0.001 噪声）");
            }
        }
    }

    @Test
    @DisplayName("generateFrame 温度限制在 [0, 80] 范围内")
    void generateFrame_tempClampedToRange() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 2.5, new Random(42));
        // 环境温度 -10°C，应被 clamp 到 0°C 附近
        double[][] frame = src.generateFrame(-10.0, null);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertTrue(frame[y][x] >= 0.0,
                        "温度不应低于 0°C, got " + frame[y][x]);
            }
        }
        // 环境温度 100°C，应被 clamp 到 80°C 附近
        frame = src.generateFrame(100.0, null);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertTrue(frame[y][x] <= 80.0,
                        "温度不应高于 80°C, got " + frame[y][x]);
            }
        }
    }

    @Test
    @DisplayName("generateFrame 有热源时中心温度高于边缘")
    void generateFrame_withHeatSource_centerHotterThanEdge() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 0.001, new Random(42));
        // 热源在中心 (4, 4)，强度 40°C，sigma 2
        double[][] heatSources = {{4.0, 4.0, 40.0, 2.0}};
        double[][] frame = src.generateFrame(25.0, heatSources);
        double centerTemp = frame[4][4];
        double cornerTemp = frame[0][0];
        assertTrue(centerTemp > cornerTemp,
                "中心温度 " + centerTemp + " 应高于角落温度 " + cornerTemp);
        assertTrue(centerTemp > 37.0,
                "中心温度应超过人体阈值 37°C, got " + centerTemp);
    }

    // ===== detectHeatSources =====

    @Test
    @DisplayName("detectHeatSources 检测到人体热源（>37°C）")
    void detectHeatSources_findsHumanHeat() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 0.001, new Random(42));
        // 热源在中心 (4, 4)，强度 40°C，sigma 2 → 中心温度 65°C
        double[][] heatSources = {{4.0, 4.0, 40.0, 2.0}};
        double[][] frame = src.generateFrame(25.0, heatSources);
        List<BudgetThermalSource.HeatSource> result = src.detectHeatSources(frame);
        assertFalse(result.isEmpty(), "应检测到至少一个热源");
        BudgetThermalSource.HeatSource hs = result.get(0);
        assertTrue(hs.tempC() > 37.0,
                "热源温度应超过 37°C, got " + hs.tempC());
        assertTrue(hs.confidence() > 0.0,
                "热源置信度应大于 0, got " + hs.confidence());
    }

    @Test
    @DisplayName("detectHeatSources 无热源时返回空列表")
    void detectHeatSources_noHeatSource_returnsEmpty() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 0.001, new Random(42));
        double[][] frame = src.generateFrame(25.0, null);
        List<BudgetThermalSource.HeatSource> result = src.detectHeatSources(frame);
        assertTrue(result.isEmpty(), "无热源应返回空列表");
    }

    @Test
    @DisplayName("detectHeatSources 温度低于阈值返回空列表")
    void detectHeatSources_belowThreshold_returnsEmpty() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 0.001, new Random(42));
        double[][] frame = src.generateFrame(30.0, null);
        List<BudgetThermalSource.HeatSource> result = src.detectHeatSources(frame);
        assertTrue(result.isEmpty(), "30°C 低于 37°C 阈值应返回空列表");
    }

    @Test
    @DisplayName("detectHeatSources 置信度与温度超出阈值程度成正比")
    void detectHeatSources_confidenceProportionalToTempAboveThreshold() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 0.001, new Random(42));
        // 热源温度 = 47°C → 置信度 = (47-37)/10 = 1.0
        double[][] heatSources = {{4.0, 4.0, 22.0, 1.0}};
        double[][] frame = src.generateFrame(25.0, heatSources);
        List<BudgetThermalSource.HeatSource> result = src.detectHeatSources(frame);
        assertFalse(result.isEmpty(), "应检测到热源");
        BudgetThermalSource.HeatSource hs = result.get(0);
        // 中心温度 ≈ 47°C，置信度应接近 1.0
        assertTrue(hs.confidence() > 0.5,
                "温度 47°C 时置信度应较高, got " + hs.confidence());
    }

    @Test
    @DisplayName("detectHeatSources 自定义阈值检测")
    void detectHeatSources_customThreshold() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 0.001, new Random(42));
        double[][] frame = src.generateFrame(30.0, null);
        // 用 25°C 阈值，30°C 环境温度应被检测到
        List<BudgetThermalSource.HeatSource> result = src.detectHeatSources(frame, 25.0);
        assertFalse(result.isEmpty(), "30°C 超过 25°C 自定义阈值应检测到热源");
    }

    @Test
    @DisplayName("detectHeatSources 空矩阵返回空列表")
    void detectHeatSources_emptyMatrix_returnsEmpty() {
        BudgetThermalSource src = new BudgetThermalSource();
        List<BudgetThermalSource.HeatSource> result = src.detectHeatSources(new double[0][]);
        assertTrue(result.isEmpty(), "空矩阵应返回空列表");
    }

    @Test
    @DisplayName("detectHeatSources null 矩阵返回空列表")
    void detectHeatSources_nullMatrix_returnsEmpty() {
        BudgetThermalSource src = new BudgetThermalSource();
        List<BudgetThermalSource.HeatSource> result = src.detectHeatSources(null);
        assertTrue(result.isEmpty(), "null 矩阵应返回空列表");
    }

    @Test
    @DisplayName("detectHeatSources 多个热源分别检测")
    void detectHeatSources_multipleSources() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 0.001, new Random(42));
        // 两个热源：左上 (1,1) 和右下 (6,6)
        double[][] heatSources = {
                {1.0, 1.0, 30.0, 1.0},
                {6.0, 6.0, 30.0, 1.0}
        };
        double[][] frame = src.generateFrame(25.0, heatSources);
        List<BudgetThermalSource.HeatSource> result = src.detectHeatSources(frame);
        assertEquals(2, result.size(), "应检测到 2 个独立热源");
    }

    // ===== detectHotspot =====

    @Test
    @DisplayName("detectHotspot 检测到最热像素")
    void detectHotspot_findsHottestPixel() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 0.001, new Random(42));
        double[][] heatSources = {{4.0, 4.0, 40.0, 2.0}};
        double[][] frame = src.generateFrame(25.0, heatSources);
        double[] hotspot = src.detectHotspot(frame, 50.0);
        assertNotNull(hotspot, "应检测到热点");
        assertTrue(hotspot[2] > 50.0, "热点温度应超过 50°C, got " + hotspot[2]);
    }

    @Test
    @DisplayName("detectHotspot 无热点返回 null")
    void detectHotspot_none_returnsNull() {
        BudgetThermalSource src = new BudgetThermalSource(0, 80, 0.001, new Random(42));
        double[][] frame = src.generateFrame(25.0, null);
        double[] hotspot = src.detectHotspot(frame, 50.0);
        assertNull(hotspot, "无热源应返回 null");
    }

    // ===== ThermalSource 接口兼容 =====

    @Test
    @DisplayName("BudgetThermalSource 实现 ThermalSource 接口")
    void implementsThermalSource() {
        BudgetThermalSource src = new BudgetThermalSource();
        assertInstanceOf(ThermalSource.class, src,
                "BudgetThermalSource 应实现 ThermalSource");
    }

    @Test
    @DisplayName("analyze 返回正确的温度统计")
    void analyze_correctStats() {
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
    void analyze_uniformField_noHotspots() {
        BudgetThermalSource src = new BudgetThermalSource();
        double[][] matrix = {{25, 25, 25}, {25, 25, 25}, {25, 25, 25}};
        ThermalSource.ThermalResult result = src.analyze(matrix);
        assertTrue(result.hotspots().isEmpty(), "均匀温度场应无热点");
        assertEquals(0.0, result.stdDev(), "均匀温度场标准差应为 0");
    }

    @Test
    @DisplayName("detectHotspots 检测到局部极大值热点")
    void detectHotspots_findsHotspot() {
        BudgetThermalSource src = new BudgetThermalSource();
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
    void detectHotspots_allBelowThreshold_returnsEmpty() {
        BudgetThermalSource src = new BudgetThermalSource();
        double[][] matrix = {{20, 21, 20}, {21, 22, 21}, {20, 21, 20}};
        List<ThermalSource.Hotspot> hotspots = src.detectHotspots(matrix, 100.0);
        assertTrue(hotspots.isEmpty(), "全部低于阈值应返回空列表");
    }

    // ===== 构造器参数校验 =====

    @Test
    @DisplayName("构造器 min >= max 抛出异常")
    void constructor_minGteMax_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new BudgetThermalSource(80, 80, 2.5),
                "min == max 应抛出异常");
        assertThrows(IllegalArgumentException.class,
                () -> new BudgetThermalSource(90, 80, 2.5),
                "min > max 应抛出异常");
    }

    @Test
    @DisplayName("构造器 accuracy <= 0 抛出异常")
    void constructor_accuracyLeZero_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new BudgetThermalSource(0, 80, 0.0),
                "accuracy == 0 应抛出异常");
        assertThrows(IllegalArgumentException.class,
                () -> new BudgetThermalSource(0, 80, -1.0),
                "accuracy < 0 应抛出异常");
    }

    // ===== HeatSource record =====

    @Test
    @DisplayName("HeatSource 置信度超出 [0,1] 抛出异常")
    void heatSource_confidenceOutOfRange_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new BudgetThermalSource.HeatSource(4, 4, 50, -0.1),
                "置信度 < 0 应抛出异常");
        assertThrows(IllegalArgumentException.class,
                () -> new BudgetThermalSource.HeatSource(4, 4, 50, 1.1),
                "置信度 > 1 应抛出异常");
    }

    @Test
    @DisplayName("HeatSource 合法置信度构造成功")
    void heatSource_validConfidence() {
        BudgetThermalSource.HeatSource hs1 = new BudgetThermalSource.HeatSource(4, 4, 50, 0.0);
        assertEquals(0.0, hs1.confidence(), "置信度 0.0 应构造成功");
        BudgetThermalSource.HeatSource hs2 = new BudgetThermalSource.HeatSource(4, 4, 50, 1.0);
        assertEquals(1.0, hs2.confidence(), "置信度 1.0 应构造成功");
        BudgetThermalSource.HeatSource hs3 = new BudgetThermalSource.HeatSource(4, 4, 50, 0.5);
        assertEquals(0.5, hs3.confidence(), "置信度 0.5 应构造成功");
    }
}