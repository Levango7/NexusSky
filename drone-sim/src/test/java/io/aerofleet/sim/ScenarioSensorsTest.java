package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sensor-fault scenario unit tests: IMU bias drift, barometer drift, and
 * magnetometer wander values behave like real sensor failures.
 */
class ScenarioSensorsTest {

    @Test
    void imuBiasAccumulates() {
        ScenarioController sc = new ScenarioController("imu-bias:10:60:2");
        assertEquals(0.0, sc.imuBiasDeg(5), 1e-9, "before start: no drift");
        assertEquals(0.0, sc.imuBiasDeg(10), 1e-9, "at start edge: no drift yet");
        assertEquals(10.0, sc.imuBiasDeg(15), 1e-9, "5s at 2 deg/s -> 10 deg");
        assertEquals(20.0, sc.imuBiasDeg(20), 1e-9, "10s at 2 deg/s -> 20 deg");
        assertEquals(0.0, sc.imuBiasDeg(75), 1e-9, "after the event ends: back to 0");
    }

    @Test
    void baroDriftAccumulates() {
        ScenarioController sc = new ScenarioController("baro-drift:30:60:0.5");
        assertEquals(0.0, sc.baroDriftM(29), 1e-9);
        assertEquals(5.0, sc.baroDriftM(40), 1e-9, "10s at 0.5 m/s -> +5 m");
        assertEquals(15.0, sc.baroDriftM(60), 1e-9, "30s at 0.5 m/s -> +15 m");
        assertEquals(0.0, sc.baroDriftM(95), 1e-9, "after end: 0 again");
    }

    @Test
    void magWanderIsBounded() {
        ScenarioController sc = new ScenarioController("mag-interference:20:40:15");
        assertEquals(0.0, sc.magWanderDeg(10), 1e-9, "before start: quiet");
        for (double t = 20; t < 60; t += 0.7) {
            double v = sc.magWanderDeg(t);
            assertTrue(Math.abs(v) <= 15.0 + 1e-9,
                    "wander must stay within amplitude, got " + v + " at t=" + t);
        }
        assertEquals(0.0, sc.magWanderDeg(65), 1e-9, "after end: quiet");
    }

    @Test
    void combinedScenariosParse() {
        ScenarioController sc = new ScenarioController(
                "imu-bias:5:30:1,baro-drift:10:20:0.2,mag-interference:15:10:8");
        assertEquals(10.0, sc.imuBiasDeg(15), 1e-9);
        assertEquals(1.0, sc.baroDriftM(15), 1e-9);
        assertTrue(Math.abs(sc.magWanderDeg(20)) <= 8.0 + 1e-9);
    }

    @Test
    void unknownKindIgnored() {
        ScenarioController sc = new ScenarioController("frobnicate:10:20");
        assertFalse(sc.enabled());
        assertEquals(0.0, sc.imuBiasDeg(50), 1e-9);
    }
}
