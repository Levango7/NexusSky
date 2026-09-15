package io.aerofleet.sim;

import io.aerofleet.mavlink.messages.EnvironmentStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnvironmentModel 单测（FR-04/07/09/11/18/24/28）：门面演化、风向量含阵风、
 * 温度因子、告警检测、状态消息构造、运行时覆盖。
 */
class EnvironmentModelTest {

    private static final double DT = 0.05;

    private static EnvironmentModel newModel(EnvScenario scen, long seed) {
        EnvironmentSource source = new SimulatedEnvSource(scen, seed);
        EnvAlertEngine alerts = new EnvAlertEngine(EnvThresholds.defaults(), 5000);
        return new EnvironmentModel(source, alerts, seed);
    }

    @Test
    void evolutionUpdatesSnapshot() {
        EnvironmentModel m = newModel(EnvScenario.WINDY, 42);
        EnvironmentState before = m.snapshot();
        m.evolve(DT);
        EnvironmentState after = m.snapshot();
        // 演化后状态应合法（可能等于 before 若伪随机恰好回到基线，但字段必须在定义域）
        assertNotNull(after);
        assertTrue(after.temperature() >= -40 && after.temperature() <= 55);
        assertTrue(after.humidity() >= 0 && after.humidity() <= 100);
    }

    @Test
    void windVectorIncludesGust() {
        EnvironmentModel m = newModel(EnvScenario.STORM, 1);
        m.evolve(DT);
        double[] w = m.windVector();
        assertEquals(2, w.length);
        // 风向量分量有限
        assertTrue(Double.isFinite(w[0]) && Double.isFinite(w[1]));
    }

    @Test
    void tempDrainFactorCold() {
        // T < 5 → 1.3
        EnvironmentModel m = newModel(EnvScenario.COLD, 1);
        // COLD 基线温度 -10 < 5 → 1.3
        assertEquals(1.3, m.tempDrainFactor(), 1e-9);
    }

    @Test
    void tempDrainFactorHot() {
        // T > 40 → 1.15
        EnvironmentModel m = newModel(EnvScenario.HOT, 1);
        // HOT 基线温度 45 > 40 → 1.15
        assertEquals(1.15, m.tempDrainFactor(), 1e-9);
    }

    @Test
    void tempDrainFactorNormal() {
        // 常温 20 → 1.0
        EnvironmentModel m = newModel(EnvScenario.CALM, 1);
        // CALM 基线温度 20 → 1.0
        assertEquals(1.0, m.tempDrainFactor(), 1e-9);
    }

    @Test
    void checkAlertsReturnsList() {
        EnvironmentModel m = newModel(EnvScenario.STORM, 1);
        m.evolve(DT);
        List<EnvAlert> alerts = m.checkAlerts();
        assertNotNull(alerts, "checkAlerts should return a list (possibly empty)");
    }

    @Test
    void toStatusMessageUnitsCorrect() {
        EnvironmentModel m = newModel(EnvScenario.CALM, 1);
        EnvironmentStatus msg = m.toStatusMessage();
        // CALM 基线：temp=20°C → 2000 c°C, humidity=50%, windSpeed=2 m/s → 200 cm/s
        assertEquals(2000, msg.temperature, 1, "20C -> 2000 cC");
        assertEquals(50, msg.humidity, "humidity 50%");
        assertEquals(200, msg.windSpeed, 1, "2 m/s -> 200 cm/s");
        assertEquals(Weather.CLEAR.code, msg.weather);
        assertEquals(10_000, msg.visibility, "CLEAR visibility 10km");
    }

    @Test
    void overrideWindUpdatesSnapshot() {
        EnvironmentModel m = newModel(EnvScenario.CALM, 1);
        m.overrideWind(25, 90);
        EnvironmentState s = m.snapshot();
        assertEquals(25, s.windSpeed(), 1e-9, "overrideWind should set windSpeed");
        assertEquals(90, s.windDirection(), 1e-9, "overrideWind should set windDirection");
    }

    @Test
    void overrideWeatherUpdatesSnapshot() {
        EnvironmentModel m = newModel(EnvScenario.CALM, 1);
        m.overrideWeather(Weather.RAIN, 20);
        EnvironmentState s = m.snapshot();
        assertEquals(Weather.RAIN, s.weather());
        assertEquals(20, s.rainRate());
    }

    @Test
    void disableSetsEnabledFalse() {
        EnvironmentModel m = newModel(EnvScenario.CALM, 1);
        assertTrue(m.isEnabled());
        m.disable();
        assertFalse(m.isEnabled());
    }

    @Test
    void fromFactoryConvertsUnits() {
        EnvironmentState s = new EnvironmentState(25.5, 60, 3.5, 180, 1.2, Weather.RAIN, 15);
        EnvironmentStatus msg = EnvironmentModel.from(s);
        assertEquals(2550, msg.temperature, 1, "25.5C -> 2550 cC");
        assertEquals(60, msg.humidity);
        assertEquals(350, msg.windSpeed, 1, "3.5 m/s -> 350 cm/s");
        assertEquals(18000, msg.windDirection, 1, "180 deg -> 18000 cdeg");
        assertEquals(120, msg.gust, 1, "1.2 m/s -> 120 cm/s");
        assertEquals(Weather.RAIN.code, msg.weather);
        assertEquals(3_000, msg.visibility, "RAIN visibility 3km");
        assertEquals(15, msg.rainRate);
    }
}