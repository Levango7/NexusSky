package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DronePhysics v2 kinematics unit tests: acceleration limit, coordinated
 * turns (bounded turn radius), braking before the target, and stop-in-place.
 */
class DronePhysicsTest {

    private static final double DT = 0.05;   // 20 Hz, same as the tick loop
    private static final double EPS = 1e-9;

    /** Advance n ticks toward the current target. */
    private static void run(DronePhysics p, int n) {
        for (int i = 0; i < n; i++) {
            p.tick(DT);
        }
    }

    @Test
    void acceleratesSmoothlyInsteadOfJumping() {
        DronePhysics p = new DronePhysics(22.5907, 113.9345, 0, 8.0);
        p.setTarget(100, 0, 10);
        // First tick: the speed must be bounded by ACCEL_MAX*dt = 0.15 m/s,
        // not the full 8 m/s cruise (v1 snapped to cruise instantly).
        p.tick(DT);
        assertTrue(p.groundSpeed() <= 3.0 * DT + 1e-6,
                "first-tick speed must be accel-bounded, got " + p.groundSpeed());
        // Over one second the speed cannot exceed ACCEL_MAX * 1s.
        run(p, 19);
        assertTrue(p.groundSpeed() <= 3.0 * (1.0 + DT) + 0.2,
                "speed after 1s must respect the 3 m/s^2 limit, got " + p.groundSpeed());
    }

    @Test
    void reachesDistantTarget() {
        DronePhysics p = new DronePhysics(22.5907, 113.9345, 0, 8.0);
        p.setTarget(80, 0, 10);
        // 80 m at avg ~7 m/s: well under 60 s of sim time.
        run(p, 20 * 60);
        assertTrue(p.targetReached(), "should reach an 80 m target within 60 s");
        assertEquals(80.0, p.north(), 0.5);
        assertEquals(10.0, p.alt(), 0.31);
    }

    @Test
    void ninetyDegreeTurnHasFiniteRadius() {
        DronePhysics p = new DronePhysics(22.5907, 113.9345, 10, 8.0);
        // Fly north 40 m, then aim east: the velocity vector must rotate
        // smoothly; instant yaw snap (v1) would produce a velocity direction
        // discontinuity - here we check the per-tick heading change is bounded.
        p.setTarget(40, 0, 10);
        run(p, 20 * 20);
        assertTrue(p.targetReached());
        p.setTarget(40, 60, 10);
        double prevYaw = p.yawRad();
        double maxTurnPerTick = 0;
        for (int i = 0; i < 20 * 30; i++) {
            p.tick(DT);
            double d = Math.abs(angleDiff(p.yawRad(), prevYaw));
            maxTurnPerTick = Math.max(maxTurnPerTick, d);
            prevYaw = p.yawRad();
            if (p.targetReached()) {
                break;
            }
        }
        // Yaw rate limit 1.8 rad/s -> per tick <= 0.09 rad
        assertTrue(maxTurnPerTick <= 1.8 * DT + 1e-6,
                "yaw change per tick must be bounded, got " + maxTurnPerTick);
        assertTrue(p.targetReached(), "should reach the second leg target");
    }

    @Test
    void doesNotOvershootTargetBadly() {
        DronePhysics p = new DronePhysics(22.5907, 113.9345, 10, 8.0);
        p.setTarget(30, 0, 10);
        run(p, 20 * 30);   // up to 30 s: plenty to arrive
        double overshoot = Math.abs(p.north() - 30);
        assertTrue(overshoot <= 2.0, "overshoot beyond acceptance must be small, got " + overshoot);
    }

    @Test
    void clearTargetStopsInPlace() {
        DronePhysics p = new DronePhysics(22.5907, 113.9345, 10, 8.0);
        p.setTarget(100, 0, 10);
        run(p, 20 * 2);   // 2 s: cruising
        double nAt = p.north();
        p.clearTarget();
        run(p, 20 * 2);
        assertEquals(nAt, p.north(), 0.01, "must stop dead after clearTarget");
    }

    @Test
    void attitudeTiltsIntoAcceleration() {
        DronePhysics p = new DronePhysics(22.5907, 113.9345, 10, 8.0);
        p.setTarget(100, 0, 10);
        p.tick(DT);
        // Accelerating north while yawed north: pitch should dip nose-down,
        // roll should stay tiny.
        assertTrue(Math.abs(p.pitchRad()) > 0.01, "pitch should respond to axial accel");
        assertTrue(Math.abs(p.rollRad()) < 0.1, "roll should stay small on a straight run");
    }

    private static double angleDiff(double a, double b) {
        double d = (a - b) % (Math.PI * 2);
        if (d > Math.PI) {
            d -= Math.PI * 2;
        } else if (d < -Math.PI) {
            d += Math.PI * 2;
        }
        return d;
    }

    // ---- E4: mode-weighted energy model ----

    @Test
    void hoverCostsMoreThanCruise() {
        // Two drones, same wall-clock: one hovers in place, one cruises away.
        DronePhysics hover = new DronePhysics(22.5907, 113.9345, 0, 8.0);
        hover.setTarget(0, 0, 10);          // climb then hold at 10 m
        DronePhysics cruise = new DronePhysics(22.5907, 113.9345, 0, 8.0);
        cruise.setTarget(200, 0, 10);       // climb then cruise 200 m
        // Give both time to climb and settle (10 s), then run 20 s more.
        run(hover, 20 * 10);
        run(cruise, 20 * 10);
        double hoverBefore = hover.drainSeconds();
        double cruiseBefore = cruise.drainSeconds();
        run(hover, 20 * 20);
        run(cruise, 20 * 20);
        double hoverDrain = hover.drainSeconds() - hoverBefore;
        double cruiseDrain = cruise.drainSeconds() - cruiseBefore;
        assertTrue(hoverDrain > cruiseDrain + 1.0,
                "hover must drain faster: " + hoverDrain + " vs " + cruiseDrain);
    }

    @Test
    void photoDrainsEnergy() {
        DronePhysics p = new DronePhysics(22.5907, 113.9345, 0, 8.0);
        double before = p.drainSeconds();
        p.drainForPhoto();
        assertEquals(DronePhysics.PHOTO_ENERGY_SEC, p.drainSeconds() - before, 1e-9);
    }

    @Test
    void batteryPctNeverRises() {
        DronePhysics p = new DronePhysics(22.5907, 113.9345, 0, 8.0);
        p.setTarget(100, 0, 20);
        int prev = p.batteryRemainingPct();
        assertEquals(100, prev);
        for (int i = 0; i < 20 * 60; i++) {
            p.tick(DT);
            int now = p.batteryRemainingPct();
            assertTrue(now <= prev, "battery % must be monotonic non-increasing");
            prev = now;
        }
    }
}
