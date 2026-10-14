package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FailsafeController unit tests: trigger edges, precedence, and recovery.
 */
class FailsafeControllerTest {

    private static final long NOW = 1_700_000_000_000L;

    private FailsafeController fx() {
        // bootMs 取 NOW - 60s：沉默基线足够长，"从未收到 GCS 包"这条路径
        // 会被真正走到，而不是被 silence=0 悄悄豁免。
        return fx(NOW - 60_000);
    }

    private FailsafeController fx(long bootMs) {
        return new FailsafeController(true, 15_000, 22, bootMs);
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
        // fx() 的 bootMs = NOW-60s 且 lastGcsRxMs=0 ⇒ silence=60s > 15s 门限，
        // 数据链 failsafe 的触发条件已经满足。STANDBY 下仍必须是 NONE ——
        // 这条断言现在真正证明了 armed 门控，而不是被 silence=0 提前短路掉。
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.STANDBY, 0, NOW, 80, false));
    }

    /**
     * 回归护栏：从未收到过任何 GCS 包的飞行器，数据链 failsafe 必须照常触发。
     * <p>
     * 旧实现把 {@code lastGcsRxMs == 0} 折成 {@code silence = 0}，于是这条路径
     * 永远免疫 —— "连上后中断"能 RTL，"从未连上就起飞"反而没人管。
     */
    @Test
    void linkLossTriggersEvenWhenGcsNeverConnected() {
        FailsafeController f = fx(NOW - 60_000);
        // 开机 60s、从未收到 GCS 包、正在飞行 → 必须 RTL
        assertEquals(FailsafeController.Action.ENTER_RTL,
                f.evaluate(FlightState.MISSION, 0, NOW, 80, false));
    }

    /**
     * 门限之前不能误触发：开机 10s（小于 15s 宽限）且从未收到 GCS 包时，
     * 仍应保持 NONE —— 证明"从 boot 起算"没有把宽限期吃掉。
     */
    @Test
    void gcsNeverConnected_respectsGracePeriod() {
        FailsafeController f = fx(NOW - 10_000);
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.MISSION, 0, NOW, 80, false));
        // 越过门限后同一控制器转为 RTL
        assertEquals(FailsafeController.Action.ENTER_RTL,
                f.evaluate(FlightState.MISSION, 0, NOW + 6_000, 80, false));
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
        FailsafeController f = new FailsafeController(false, 0, 100, NOW - 60_000);
        assertEquals(FailsafeController.Action.NONE,
                f.evaluate(FlightState.MISSION, 0, NOW, 5, true));
    }
}
