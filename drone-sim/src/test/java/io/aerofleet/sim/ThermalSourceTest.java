package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatedThermalSource 单测（M3 感知成像增强，FR-08/FR-09）。
 */
class ThermalSourceTest {

    private static double[][] uniform(int h, int w, double v) {
        double[][] m = new double[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                m[y][x] = v;
            }
        }
        return m;
    }

    @Test
    void analyzeReturnsStats() {
        // 5x5 温度场：背景 25℃ + 中心 80℃
        double[][] m = uniform(5, 5, 25);
        m[2][2] = 80;
        SimulatedThermalSource src = new SimulatedThermalSource();
        ThermalSource.ThermalResult r = src.analyze(m);
        assertEquals(80, r.max(), 0.001, "max should be 80");
        assertEquals(25, r.min(), 0.001, "min should be 25");
        assertTrue(r.mean() > 25, "mean should exceed background");
        assertTrue(r.stdDev() > 0, "stdDev should be positive");
    }

    @Test
    void hotspotDetection() {
        // 5x5 温度场：背景 25℃ + 中心 80℃
        double[][] m = uniform(5, 5, 25);
        m[2][2] = 80;
        SimulatedThermalSource src = new SimulatedThermalSource();
        List<ThermalSource.Hotspot> hotspots = src.detectHotspots(m, 50);
        assertFalse(hotspots.isEmpty(), "should detect the 80°C hotspot");
        assertTrue(hotspots.get(0).tempC() >= 50, "hotspot temp should exceed threshold");
    }

    @Test
    void noHotspotWhenUniform() {
        // 全 25℃ → 无热点
        double[][] m = uniform(5, 5, 25);
        SimulatedThermalSource src = new SimulatedThermalSource();
        List<ThermalSource.Hotspot> hotspots = src.detectHotspots(m, 50);
        assertTrue(hotspots.isEmpty(), "uniform field should have no hotspots");
    }

    @Test
    void analyzeUniformHasNoHotspots() {
        double[][] m = uniform(5, 5, 25);
        SimulatedThermalSource src = new SimulatedThermalSource();
        ThermalSource.ThermalResult r = src.analyze(m);
        assertTrue(r.hotspots().isEmpty(), "uniform field should have no hotspots");
        assertEquals(25, r.mean(), 0.001);
        assertEquals(0, r.stdDev(), 0.001);
    }

    @Test
    void synthesizeThermalField() {
        // 合成 1 个热源 → 温度场含升温区域
        SimulatedThermalSource src = new SimulatedThermalSource(new Random(42));
        double[][] field = src.synthesizeThermalField(20, 20,
                List.of(new SimulatedThermalSource.HeatSource(10, 10, 60, 3)));
        // 热源中心温度应显著高于背景
        double center = field[10][10];
        double corner = field[0][0];
        assertTrue(center > corner + 20, "center should be much hotter than corner");
        assertTrue(center > 50, "center should exceed 50°C with 60°C heat source");
    }

    @Test
    void synthesizeThermalFieldNoSources() {
        SimulatedThermalSource src = new SimulatedThermalSource(new Random(42));
        double[][] field = src.synthesizeThermalField(10, 10, null);
        // 无热源 → 全背景 20-30℃
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                assertTrue(field[y][x] >= 20 && field[y][x] < 30,
                        "background should be 20-30°C, got " + field[y][x]);
            }
        }
    }

    @Test
    void multipleHotspotsDetected() {
        // 两个独立热点
        double[][] m = uniform(10, 10, 25);
        m[2][2] = 80;
        m[7][7] = 90;
        SimulatedThermalSource src = new SimulatedThermalSource();
        List<ThermalSource.Hotspot> hotspots = src.detectHotspots(m, 50);
        assertEquals(2, hotspots.size(), "should detect 2 hotspots");
    }
}