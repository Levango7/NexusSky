package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actuator 接口契约单测（FR-02/FR-03/FR-04）。
 * <p>
 * 验证 SprayPump 与 Gripper 均 implements Actuator，
 * enable/disable/setRate/getState 契约一致。
 */
class ActuatorTest {

    @Test
    void sprayPumpImplementsActuator() {
        SprayPump pump = new SprayPump(20, 2000, 5, 6, 5, 3, null, null);
        assertTrue(pump instanceof Actuator, "SprayPump must implement Actuator");
    }

    @Test
    void gripperImplementsActuator() {
        Gripper gripper = new Gripper(10);
        assertTrue(gripper instanceof Actuator, "Gripper must implement Actuator");
    }

    @Test
    void sprayPumpEnableDisableContract() {
        SprayPump pump = new SprayPump(20, 2000, 5, 6, 5, 3, null, null);
        // 初始 DISABLED
        assertEquals(ActuatorState.DISABLED, pump.getState().state());
        assertFalse(pump.getState().enabled());
        // enable → IDLE
        pump.enable();
        assertTrue(pump.getState().enabled());
        assertEquals(ActuatorState.IDLE, pump.getState().state());
        // disable → DISABLED + rate=0
        pump.disable();
        assertFalse(pump.getState().enabled());
        assertEquals(0.0, pump.getState().rate());
        assertEquals(ActuatorState.DISABLED, pump.getState().state());
    }

    @Test
    void gripperEnableDisableContract() {
        Gripper gripper = new Gripper(10);
        // 初始 DISABLED
        assertEquals(ActuatorState.DISABLED, gripper.getState().state());
        assertFalse(gripper.getState().enabled());
        // enable → IDLE
        gripper.enable();
        assertTrue(gripper.getState().enabled());
        assertEquals(ActuatorState.IDLE, gripper.getState().state());
        // disable → DISABLED
        gripper.disable();
        assertFalse(gripper.getState().enabled());
        assertEquals(ActuatorState.DISABLED, gripper.getState().state());
    }

    @Test
    void stateEnumHasFourValues() {
        // FR-03：状态枚举 {DISABLED, IDLE, ACTIVE, FAULT}
        ActuatorState[] values = ActuatorState.values();
        assertEquals(4, values.length);
        assertArrayEquals(
                new ActuatorState[]{ActuatorState.DISABLED, ActuatorState.IDLE,
                        ActuatorState.ACTIVE, ActuatorState.FAULT},
                values);
    }
}