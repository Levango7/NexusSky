package io.aerofleet.sim;

import io.aerofleet.mavlink.messages.ExtendedSysState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 飞行阶段上报口径（EXTENDED_SYS_STATE.landed_state，2026-10-29 接线）。
 * <p>
 * 此前 sim 从不发这条消息，后端 landedState 恒 -1，GCS 的「起飞中 / 降落中」只能靠
 * HEARTBEAT.custom_mode 猜（PX4 与 ArduPilot 对同一数字解释不同）。这里钉的是映射
 * 本身的边界：只由「是否上锁 / 爬升率 / 相对高度」决定，不读 custom_mode。
 */
class FlightPhaseReportTest {

    private static int map(FlightState s, double alt, double climb) {
        return VirtualDrone.landedStateFor(s, alt, climb);
    }

    @Test
    void notArmedIsAlwaysOnGround() {
        assertEquals(ExtendedSysState.LANDED_ON_GROUND, map(FlightState.INIT, 0, 0));
        // 上锁状态即便读数在爬升（例如上一轮的余量）也不该报「起飞中」
        assertEquals(ExtendedSysState.LANDED_ON_GROUND, map(FlightState.STANDBY, 0, 2));
        assertEquals(ExtendedSysState.LANDED_ON_GROUND, map(FlightState.ARMED, 0.2, 0));
    }

    @Test
    void climbingIsTakeoff() {
        assertEquals(ExtendedSysState.LANDED_TAKEOFF, map(FlightState.MISSION, 1, 1));
        assertEquals(ExtendedSysState.LANDED_TAKEOFF, map(FlightState.RTL, 30, 0.8));
    }

    @Test
    void descendingIsLanding() {
        assertEquals(ExtendedSysState.LANDED_LANDING, map(FlightState.RTL, 5, -1.5));
        // 贴着地面但仍在下降：还是「降落中」，直到真正触地才转「地面」
        assertEquals(ExtendedSysState.LANDED_LANDING, map(FlightState.RTL, 0.1, -0.6));
    }

    @Test
    void levelFlightAboveGroundIsInAir() {
        assertEquals(ExtendedSysState.LANDED_IN_AIR, map(FlightState.MISSION, 25, 0));
        assertEquals(ExtendedSysState.LANDED_IN_AIR, map(FlightState.MANUAL, 2, -0.4));
        assertEquals(ExtendedSysState.LANDED_IN_AIR, map(FlightState.HOLD, 4, 0.4));
    }

    @Test
    void armedButStillOnTheGroundIsOnGround() {
        assertEquals(ExtendedSysState.LANDED_ON_GROUND, map(FlightState.ARMED, 0.3, 0));
        assertEquals(ExtendedSysState.LANDED_ON_GROUND, map(FlightState.MISSION, 0, 0));
    }

    @Test
    void crashedReportsOnGroundEvenWithAltitudeReading() {
        assertEquals(ExtendedSysState.LANDED_ON_GROUND, map(FlightState.CRASHED, 12, 0));
    }

    @Test
    void fixedVersionIntegerRendersAsPx4StyleString() {
        assertEquals((1 << 24) | (14 << 16) | 7, VirtualDrone.FLIGHT_SW_VERSION);
    }
}
