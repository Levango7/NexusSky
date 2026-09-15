package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatedRotorAerodynamics 单测（M4 硬件抽象，FR-07/FR-08/FR-09）。
 */
class SimulatedRotorAerodynamicsTest {

    private static final int SYSID = 1;
    private static final double G = 9.81;

    private RotorConfig quadConfig() {
        return new RotorConfig(SYSID, 4, 0.25, 5.0, 10000, 1.225);
    }

    private RotorAerodynamics.FlightState hoverState() {
        return new RotorAerodynamics.FlightState(1.5, 0, 0, 0, 0);
    }

    @Test
    void hoverThrustEqualsGravity() {
        SimulatedRotorAerodynamics aero = new SimulatedRotorAerodynamics();
        RotorAerodynamics.RotorAeroResult result = aero.compute(quadConfig(), hoverState());
        // 总推力 ≈ 质量 × G = 1.5 × 9.81 ≈ 14.715N
        assertEquals(1.5 * G, result.totalThrust(), 0.1);
    }

    @Test
    void thrustPerRotorCorrect() {
        SimulatedRotorAerodynamics aero = new SimulatedRotorAerodynamics();
        RotorAerodynamics.RotorAeroResult result = aero.compute(quadConfig(), hoverState());
        // 4 旋翼 → 各推力 ≈ 14.715 / 4 ≈ 3.679N
        assertEquals(4, result.rotors().size());
        for (RotorAerodynamics.RotorResult r : result.rotors()) {
            assertEquals(1.5 * G / 4, r.thrust(), 0.05);
        }
    }

    @Test
    void powerPositive() {
        SimulatedRotorAerodynamics aero = new SimulatedRotorAerodynamics();
        RotorAerodynamics.RotorAeroResult result = aero.compute(quadConfig(), hoverState());
        assertTrue(result.totalPower() > 0);
        for (RotorAerodynamics.RotorResult r : result.rotors()) {
            assertTrue(r.powerConsumption() > 0);
        }
    }

    @Test
    void rpmInReasonableRange() {
        SimulatedRotorAerodynamics aero = new SimulatedRotorAerodynamics();
        RotorAerodynamics.RotorAeroResult result = aero.compute(quadConfig(), hoverState());
        for (RotorAerodynamics.RotorResult r : result.rotors()) {
            assertTrue(r.rpm() > 1000, "rpm too low: " + r.rpm());
            assertTrue(r.rpm() < 20000, "rpm too high: " + r.rpm());
        }
    }

    @Test
    void invalidRpmRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RotorAerodynamics.RotorResult(0, -1, 1, 0.8, 1));
    }

    @Test
    void invalidEfficiencyRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RotorAerodynamics.RotorResult(0, 5000, 1, 1.5, 1));
    }
}