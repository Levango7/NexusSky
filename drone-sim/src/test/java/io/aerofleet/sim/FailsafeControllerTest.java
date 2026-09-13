package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FailsafeController unit tests: trigger edges, precedence, and recovery.
 */
class FailsafeControllerTest {

    private static final long NOW = 1_700_000_000_000L;

    private FailsafeController fx() {
        return new FailsafeController(true, 15_000, 22);
    }

    @Test
    void noActionWhileHealthy() {
        FailsafeController f = fx();
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.MISSION, NOW - 1000, NOW, 80, false));
    }

    @Test
    void linkLossTriggersRtlOnce() {
        FailsafeController f = fx();
        // silent for 16s while flying -> RTL on the edge...
        assertEquals(FailsafeController.Action.ENTER_RTL,
                f.evaluate(FlightState.MISSION, NOW - 16_000, NOW, 80, false));
        // ...but not repeatedly: subsequent ticks return NONE (already in RTL)
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.RTL, NOW - 20_000, NOW, 80, false));
        // and link recovery clears the edge so a second loss triggers again
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.RTL, NOW - 1000, NOW, 80, false));
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.MISSION, NOW - 1000, NOW, 80, false));
        assertEquals(FailsafeController.Action.ENTER_RTL,
                f.evaluate(FlightState.MISSION, NOW - 16_000, NOW, 80, false));
    }

    @Test
    void linkLossIgnoredWhileDisarmed() {
        FailsafeController f = fx();
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.STANDBY, 0, NOW, 80, false));
    }

    @Test
    void batteryCritTriggersRtl() {
        FailsafeController f = fx();
        assertEquals(FailsafeController.Action.ENTER_RTL,
                f.evaluate(FlightState.MISSION, NOW - 1000, NOW, 22, false));
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.RTL, NOW - 1000, NOW, 18, false));
    }

    @Test
    void batteryBeatsLink() {
        FailsafeController f = fx();
        // both battery and link triggers active: battery wins (RTL either way,
        // but the battery edge must fire first and consume the tick action)
        assertEquals(FailsafeController.Action.ENTER_RTL,
                f.evaluate(FlightState.MISSION, NOW - 60_000, NOW, 20, false));
    }

    @Test
    void gpsLossHoldsThenResumes() {
        FailsafeController f = fx();
        assertEquals(FailsafeController.Action.ENTER_HOLD,
                f.evaluate(FlightState.MISSION, NOW - 1000, NOW, 80, true));
        // staying lost: no repeated actions
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.HOLD, NOW - 1000, NOW, 80, true));
        // fix restored: resume the mission
        assertEquals(FailsafeController.Action.RESUME_MISSION,
                f.evaluate(FlightState.HOLD, NOW - 1000, NOW, 80, false));
        // healthy again
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.MISSION, NOW - 1000, NOW, 80, false));
    }

    @Test
    void gpsLossWhileArmedHoverHolds() {
        FailsafeController f = fx();
        assertEquals(FailsafeController.Action.ENTER_HOLD,
                f.evaluate(FlightState.ARMED, NOW - 1000, NOW, 80, true));
        assertEquals(FailsafeController.Action.RESUME_MISSION,
                f.evaluate(FlightState.HOLD, NOW - 1000, NOW, 80, false));
    }

    @Test
    void disabledFailsafeInert() {
        FailsafeController f = new FailsafeController(false, 0, 100);
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.MISSION, 0, NOW, 5, true));
    }
}
