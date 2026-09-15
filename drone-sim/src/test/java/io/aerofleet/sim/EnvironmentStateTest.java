package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnvironmentState 单测（FR-02/07/12/14/17）：五元组完整性、风向量分解、能见度、凝露风险。
 */
class EnvironmentStateTest {

    @Test
    void stateFiveTupleComplete() {
        EnvironmentState s = new EnvironmentState(20, 50, 5, 180, 1, Weather.CLEAR, 0);
        assertEquals(20, s.temperature());
        assertEquals(50, s.humidity());
        assertEquals(5, s.windSpeed());
        assertEquals(180, s.windDirection());
        assertEquals(1, s.gust());
        assertEquals(Weather.CLEAR, s.weather());
        assertEquals(0, s.rainRate());
    }

    @Test
    void windVectorDecompositionNorth() {
        // 风向 0°=正北风（风从北吹向南）→ windNorth < 0, windEast ≈ 0
        EnvironmentState s = new EnvironmentState(20, 50, 10, 0, 0, Weather.CLEAR, 0);
        assertTrue(s.windNorth() < 0, "north wind: windNorth should be negative");
        assertEquals(0, s.windEast(), 1e-9, "north wind: windEast should be ~0");
        // 幅值 = windSpeed
        assertEquals(10, Math.hypot(s.windNorth(), s.windEast()), 1e-9);
    }

    @Test
    void windVectorDecompositionEast() {
        // 风向 90°=正东风（风从东吹向西）→ windEast < 0, windNorth ≈ 0
        EnvironmentState s = new EnvironmentState(20, 50, 10, 90, 0, Weather.CLEAR, 0);
        assertTrue(s.windEast() < 0, "east wind: windEast should be negative");
        assertEquals(0, s.windNorth(), 1e-9, "east wind: windNorth should be ~0");
        assertEquals(10, Math.hypot(s.windNorth(), s.windEast()), 1e-9);
    }

    @Test
    void windVectorMagnitudeEqualsWindSpeed() {
        for (double dir : new double[]{0, 45, 90, 135, 180, 270}) {
            EnvironmentState s = new EnvironmentState(20, 50, 8, dir, 0, Weather.CLEAR, 0);
            assertEquals(8, Math.hypot(s.windNorth(), s.windEast()), 1e-9,
                    "magnitude should equal windSpeed at dir=" + dir);
        }
    }

    @Test
    void visibilityFromWeather() {
        assertEquals(10_000, new EnvironmentState(20, 50, 0, 0, 0, Weather.CLEAR, 0).visibilityM());
        assertEquals(10_000, new EnvironmentState(20, 50, 0, 0, 0, Weather.CLOUDY, 0).visibilityM());
        assertEquals(3_000, new EnvironmentState(20, 50, 0, 0, 0, Weather.RAIN, 0).visibilityM());
        assertEquals(1_000, new EnvironmentState(20, 50, 0, 0, 0, Weather.SNOW, 0).visibilityM());
        assertEquals(300, new EnvironmentState(20, 50, 0, 0, 0, Weather.FOG, 0).visibilityM());
    }

    @Test
    void condensationRiskFoggy() {
        // 高湿 + 低温 → 凝露风险
        assertTrue(new EnvironmentState(3, 96, 0, 0, 0, Weather.FOG, 0).condensationRisk());
        // 高湿但温度高 → 无风险
        assertFalse(new EnvironmentState(20, 96, 0, 0, 0, Weather.CLEAR, 0).condensationRisk());
        // 低温但湿度低 → 无风险
        assertFalse(new EnvironmentState(-5, 50, 0, 0, 0, Weather.CLEAR, 0).condensationRisk());
    }

    @Test
    void weatherOfUnknownCodeFallsBackClear() {
        assertEquals(Weather.CLEAR, Weather.of(99));
        assertEquals(Weather.CLEAR, Weather.of(-1));
        assertEquals(Weather.RAIN, Weather.of(2));
    }
}