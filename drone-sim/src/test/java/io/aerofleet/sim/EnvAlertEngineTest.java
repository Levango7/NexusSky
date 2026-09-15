package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.MavEnums;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnvAlertEngine 单测（FR-18~22）：风速/温度/天气告警、分级对齐 MAV_SEVERITY、5s 去抖。
 */
class EnvAlertEngineTest {

    private static final EnvThresholds TH = EnvThresholds.defaults();  // (8, 12, 0, -10, 45, 50, 95)

    @Test
    void windSpeedWarningAlert() {
        EnvAlertEngine eng = new EnvAlertEngine(TH, 5000);
        // 风速 10 > windWarn(8) but < windCrit(12) → WARNING
        EnvironmentState s = new EnvironmentState(20, 50, 10, 0, 0, Weather.CLEAR, 0);
        List<EnvAlert> alerts = eng.check(s);
        assertTrue(alerts.stream().anyMatch(a -> a.type() == EnvAlertType.WIND
                && a.severity() == MavEnums.MAV_SEVERITY_WARNING),
                "wind 10 m/s should trigger WARNING");
    }

    @Test
    void windSpeedCriticalAlert() {
        EnvAlertEngine eng = new EnvAlertEngine(TH, 5000);
        EnvironmentState s = new EnvironmentState(20, 50, 15, 0, 0, Weather.CLEAR, 0);
        List<EnvAlert> alerts = eng.check(s);
        assertTrue(alerts.stream().anyMatch(a -> a.type() == EnvAlertType.WIND
                && a.severity() == MavEnums.MAV_SEVERITY_CRITICAL),
                "wind 15 m/s should trigger CRITICAL");
    }

    @Test
    void tempAbnormalAlert() {
        EnvAlertEngine eng = new EnvAlertEngine(TH, 5000);
        // 高温 48 > tempHighWarn(45) but < tempHighCrit(50) → WARNING
        EnvironmentState hot = new EnvironmentState(48, 50, 0, 0, 0, Weather.CLEAR, 0);
        List<EnvAlert> alerts = eng.check(hot);
        assertTrue(alerts.stream().anyMatch(a -> a.type() == EnvAlertType.TEMP),
                "temp 48C should trigger TEMP alert");

        // 低温 -5 < tempLowWarn(0) but > tempLowCrit(-10) → WARNING
        EnvAlertEngine eng2 = new EnvAlertEngine(TH, 5000);
        EnvironmentState cold = new EnvironmentState(-5, 50, 0, 0, 0, Weather.CLEAR, 0);
        List<EnvAlert> coldAlerts = eng2.check(cold);
        assertTrue(coldAlerts.stream().anyMatch(a -> a.type() == EnvAlertType.TEMP),
                "temp -5C should trigger TEMP alert");
    }

    @Test
    void weatherDegradeAlert() {
        EnvAlertEngine eng = new EnvAlertEngine(TH, 5000);
        // 先 CLEAR（初始 lastWeather=CLEAR），再 RAIN → 恶化
        eng.check(new EnvironmentState(20, 50, 0, 0, 0, Weather.CLEAR, 0));
        List<EnvAlert> alerts = eng.check(new EnvironmentState(20, 80, 0, 0, 0, Weather.RAIN, 10));
        assertTrue(alerts.stream().anyMatch(a -> a.type() == EnvAlertType.WEATHER),
                "CLEAR -> RAIN should trigger WEATHER degrade alert");
    }

    @Test
    void severityAlignsMavSeverity() {
        EnvAlertEngine eng = new EnvAlertEngine(TH, 5000);
        // 触发各类告警
        EnvironmentState s = new EnvironmentState(48, 50, 15, 0, 0, Weather.CLEAR, 0);
        List<EnvAlert> alerts = eng.check(s);
        for (EnvAlert a : alerts) {
            assertTrue(a.severity() == MavEnums.MAV_SEVERITY_CRITICAL
                            || a.severity() == MavEnums.MAV_SEVERITY_WARNING
                            || a.severity() == MavEnums.MAV_SEVERITY_INFO,
                    "severity must be in {2,4,6}, got " + a.severity());
        }
    }

    @Test
    void alertDebounce() {
        EnvAlertEngine eng = new EnvAlertEngine(TH, 5000);
        EnvironmentState s = new EnvironmentState(20, 50, 15, 0, 0, Weather.CLEAR, 0);
        // 第一次触发
        List<EnvAlert> first = eng.check(s);
        assertFalse(first.isEmpty(), "first check should trigger");
        // 5s 内再次检查同类型 → 去抖跳过
        List<EnvAlert> second = eng.check(s);
        assertTrue(second.stream().noneMatch(a -> a.type() == EnvAlertType.WIND),
                "second check within debounce window should not re-trigger WIND");
    }

    @Test
    void alertContainsValueAndThreshold() {
        EnvAlertEngine eng = new EnvAlertEngine(TH, 5000);
        EnvironmentState s = new EnvironmentState(20, 50, 15, 0, 0, Weather.CLEAR, 0);
        List<EnvAlert> alerts = eng.check(s);
        EnvAlert wind = alerts.stream()
                .filter(a -> a.type() == EnvAlertType.WIND)
                .findFirst().orElseThrow();
        assertEquals(15, wind.value(), 1e-9, "value should be the measured wind speed");
        assertEquals(TH.windCrit, wind.threshold(), 1e-9, "threshold should be windCrit");
    }
}