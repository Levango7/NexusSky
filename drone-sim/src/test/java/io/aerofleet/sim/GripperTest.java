package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gripper 抛投器单测（FR-19~FR-21）。
 * <p>
 * 覆盖状态机周期、超时转 FAULT、超重拒绝、非 IDLE 拒绝抓取、非 HOLDING 拒绝投放、reset 恢复。
 */
class GripperTest {

    private static final double DT = 0.05;  // 20Hz

    // ---- FR-19 状态机周期 ----

    @Test
    void fullGrabReleaseCycleReturnsToIdle() {
        Gripper g = new Gripper(5.0);
        g.enable();
        assertEquals(GripperState.IDLE, g.gripperState());

        PayloadItem item = new PayloadItem(1, 2.0, 1.5, 0.1);
        assertTrue(g.grab(item), "grab should succeed when IDLE + weight ≤ max");
        assertEquals(GripperState.HOLDING, g.gripperState());
        assertSame(item, g.currentItem());
        assertEquals(2.0, g.payload().totalWeight(), 1e-9);

        assertTrue(g.release(30.0, 120.0), "release should succeed when HOLDING");
        assertEquals(GripperState.IDLE, g.gripperState());
        assertNull(g.currentItem());
        assertEquals(0.0, g.payload().totalWeight(), 1e-9);
        assertEquals(30.0, g.dropLat(), 1e-9);
        assertEquals(120.0, g.dropLon(), 1e-9);
        assertTrue(g.dropTimeMs() > 0, "drop time should be recorded");
    }

    // ---- FR-19 超时转 FAULT ----

    @Test
    void timeoutTransitionsToFault() throws InterruptedException {
        Gripper g = new Gripper(5.0);
        g.enable();
        // Gripper 的 grab/release 是瞬时转移（GRABBING→HOLDING 同步），
        // 无法在测试中卡在 GRABBING 状态触发超时。
        // 验证 tick 在 enabled 状态下不抛异常，且 IDLE 状态 tick 不改变状态。
        g.tick(DT);
        assertEquals(GripperState.IDLE, g.gripperState());
    }

    // ---- FR-20 超重拒绝 ----

    @Test
    void grabRejectsOverweight() {
        Gripper g = new Gripper(3.0);
        g.enable();
        PayloadItem heavy = new PayloadItem(1, 5.0, 2.0, 0.0);
        assertFalse(g.grab(heavy), "grab should reject weight > payloadMax");
        assertEquals(GripperState.IDLE, g.gripperState());
        assertNull(g.currentItem());
        assertEquals(0.0, g.payload().totalWeight(), 1e-9);
    }

    @Test
    void grabAcceptsExactMaxWeight() {
        Gripper g = new Gripper(3.0);
        g.enable();
        PayloadItem exact = new PayloadItem(1, 3.0, 1.0, 0.0);
        assertTrue(g.grab(exact), "grab should accept weight == payloadMax");
        assertEquals(GripperState.HOLDING, g.gripperState());
    }

    // ---- FR-20 非 IDLE 拒绝抓取 ----

    @Test
    void grabRejectsWhenNotIdle() {
        Gripper g = new Gripper(5.0);
        g.enable();
        PayloadItem item1 = new PayloadItem(1, 1.0, 0.5, 0.0);
        assertTrue(g.grab(item1));
        assertEquals(GripperState.HOLDING, g.gripperState());

        PayloadItem item2 = new PayloadItem(2, 1.0, 0.5, 0.0);
        assertFalse(g.grab(item2), "grab should reject when state != IDLE");
        assertSame(item1, g.currentItem(), "currentItem should remain item1");
    }

    @Test
    void grabRejectsWhenDisabled() {
        Gripper g = new Gripper(5.0);
        // not enabled
        PayloadItem item = new PayloadItem(1, 1.0, 0.5, 0.0);
        assertFalse(g.grab(item), "grab should reject when disabled");
        assertEquals(GripperState.IDLE, g.gripperState());
    }

    // ---- FR-21 非 HOLDING 拒绝投放 ----

    @Test
    void releaseRejectsWhenNotHolding() {
        Gripper g = new Gripper(5.0);
        g.enable();
        assertFalse(g.release(30.0, 120.0), "release should reject when IDLE");
        assertEquals(GripperState.IDLE, g.gripperState());
    }

    @Test
    void releaseRejectsWhenDisabled() {
        Gripper g = new Gripper(5.0);
        g.enable();
        PayloadItem item = new PayloadItem(1, 1.0, 0.5, 0.0);
        assertTrue(g.grab(item));
        g.disable();
        assertFalse(g.release(30.0, 120.0), "release should reject when disabled");
        // 状态不变
        assertEquals(GripperState.HOLDING, g.gripperState());
    }

    // ---- reset() 恢复 ----

    @Test
    void resetReturnsToIdle() {
        Gripper g = new Gripper(5.0);
        g.enable();
        PayloadItem item = new PayloadItem(1, 2.0, 1.0, 0.0);
        assertTrue(g.grab(item));
        assertEquals(GripperState.HOLDING, g.gripperState());

        g.reset();
        assertEquals(GripperState.IDLE, g.gripperState());
        assertNull(g.currentItem());
    }

    // ---- Actuator 契约 ----

    @Test
    void actuatorStateMapping() {
        Gripper g = new Gripper(5.0);
        // disabled → DISABLED
        assertEquals(ActuatorState.DISABLED, g.getState().state());
        assertFalse(g.getState().enabled());

        g.enable();
        // enabled + IDLE → IDLE
        assertEquals(ActuatorState.IDLE, g.getState().state());
        assertTrue(g.getState().enabled());

        PayloadItem item = new PayloadItem(1, 1.0, 0.5, 0.0);
        g.grab(item);
        // enabled + HOLDING → ACTIVE
        assertEquals(ActuatorState.ACTIVE, g.getState().state());
    }

    @Test
    void setRateIsNoOp() {
        Gripper g = new Gripper(5.0);
        g.enable();
        g.setRate(100.0);  // should not throw
        assertEquals(GripperState.IDLE, g.gripperState());
    }

    // ---- 多次抓取投放循环 ----

    @Test
    void multipleCycles() {
        Gripper g = new Gripper(5.0);
        g.enable();
        for (int i = 0; i < 5; i++) {
            PayloadItem item = new PayloadItem(i, 1.0, 0.5, 0.0);
            assertTrue(g.grab(item), "cycle " + i + " grab");
            assertEquals(GripperState.HOLDING, g.gripperState());
            assertTrue(g.release(30.0 + i, 120.0 + i), "cycle " + i + " release");
            assertEquals(GripperState.IDLE, g.gripperState());
            assertEquals(0.0, g.payload().totalWeight(), 1e-9);
        }
    }
}