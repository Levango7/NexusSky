package io.aerofleet.sim;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Virtual drone physical state and simplified kinematics.
 * Position is tracked in local meters (north/east) relative to the fixed home
 * reference, plus altitude relative to home. Motion is "move toward target"
 * at constant speed per tick: no real aerodynamics, just plausible telemetry.
 *
 * All mutable fields are confined to the simulator tick thread.
 */
public final class DronePhysics {

    /** Compensated battery: full voltage (V). */
    public static final double VOLT_FULL = 15.8;
    /** Compensated battery: empty voltage (V). */
    public static final double VOLT_EMPTY = 14.0;
    /** Flight seconds assumed for the full voltage range. */
    public static final double BATTERY_SECONDS = 18.0 * 60.0;

    // --- configuration ---
    private final double homeLat;
    private final double homeLon;
    private final double cruiseSpeed;

    // --- state ---
    // volatile: tick 线程写，其他线程通过 getter 读取，保证跨线程可见性
    private volatile double north;      // m from home, +N
    private volatile double east;       // m from home, +E
    private volatile double alt;       // m relative to home
    private volatile double yawRad;      // heading, 0 = north, CW positive
    private volatile double groundSpeed; // m/s horizontal
    private volatile double vz;         // m/s vertical, +up
    private volatile double rollRad;
    private volatile double pitchRad;

    // AtomicReference: 命令线程写（setTarget/holdAt/clearTarget），tick 线程读
    // 保证 4 个 target 字段的原子读写，避免 volatile 多字段写的部分更新问题。
    private static final class TargetState {
        final double north, east, alt, speed;
        TargetState(double north, double east, double alt, double speed) {
            this.north = north;
            this.east = east;
            this.alt = alt;
            this.speed = speed;
        }
        static final TargetState NONE = new TargetState(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        boolean hasTarget() { return !Double.isNaN(north); }
    }
    private final AtomicReference<TargetState> target = new AtomicReference<>(TargetState.NONE);

    private final long bootMillis;
    private volatile long lastTickMs;
    private volatile double airborneSeconds;
    /** Mode-weighted energy seconds (E4): hover/climb cost more, drift drains battery via batteryVoltage(). */
    private volatile double drainSeconds;
    /**
     * 温度影响电池能耗的乘性因子（FR-11，M0b 环境气象）。
     * 默认 1.0（常温行为不变，DFX 4.5）；由 {@link EnvironmentModel#tempDrainFactor()} 注入。
     * T < 5°C → 1.3（低温电池内阻增大）；T > 40°C → 1.15（高温散热负荷）；常温 → 1.0。
     * <p>volatile: 外部线程（EnvironmentModel）写，tick 线程读，保证可见性。
     */
    private volatile double tempDrainFactor = 1.0;

    /** Energy multipliers: cruise=1.0 baseline, hover/climb above, descent below. */
    private static final double HOVER_DRAIN = 1.3;
    private static final double CLIMB_DRAIN = 1.5;
    private static final double DESCENT_DRAIN = 0.6;
    /** Energy cost of one photo: camera + gimbal + storage load, in cruise-seconds. */
    public static final double PHOTO_ENERGY_SEC = 5.0;

    /** Vertical speed used while climbing/descending to a target (m/s). */
    private static final double VERT_SPEED = 2.0;
    /** Acceptance: horizontal distance (m). */
    public static final double ACCEPT_XY = 0.5;
    /** Acceptance: altitude difference (m). */
    public static final double ACCEPT_Z = 0.3;

    public DronePhysics(double homeLat, double homeLon, double alt, double cruiseSpeed) {
        this.homeLat = homeLat;
        this.homeLon = homeLon;
        this.cruiseSpeed = cruiseSpeed;
        this.alt = alt;
        this.bootMillis = System.currentTimeMillis();
        this.lastTickMs = System.currentTimeMillis();
    }

    // ---- setters for flight control ----

    /**
     * MANUAL_CONTROL velocity command: body-frame (forward, right, up) in
     * m/s, and yaw rate in rad/s. Reuses the v2 integrator (accelerations
     * and attitude stay physical) but steers in BODY frame: forward is
     * along the current heading.
     */
    public void setManualVelocity(double fwd, double right, double up, double yawRate) {
        this.manualFwd = fwd;
        this.manualRight = right;
        this.manualUp = up;
        this.manualYawRate = yawRate;
        this.manualActive = true;
    }

    /** Stop manual steering (stick timeout / mode exit): hover in place. */
    public void clearManual() {
        this.manualActive = false;
    }

    /** Fly toward the given local target at the cruise speed. */
    public void setTarget(double north, double east, double alt) {
        target.set(new TargetState(north, east, alt, cruiseSpeed));
    }

    /** Same target but with an explicit speed override (e.g. RTL approach). */
    public void setTarget(double north, double east, double alt, double speed) {
        target.set(new TargetState(north, east, alt, speed));
    }

    /** Hold position at current spot at the given altitude (hover / takeoff / land). */
    public void holdAt(double alt) {
        target.set(new TargetState(north, east, alt, 0));
    }

    public void clearTarget() {
        target.set(TargetState.NONE);
        // v2: no target means "stop where we are" - kill residual velocity.
        this.velN = 0;
        this.velE = 0;
    }

    public boolean hasTarget() {
        return target.get().hasTarget();
    }

    /** True when within acceptance radius of the current target. */
    public boolean targetReached() {
        TargetState ts = target.get();
        if (!ts.hasTarget()) {
            return true;
        }
        double dn = ts.north - north;
        double de = ts.east - east;
        double dz = ts.alt - alt;
        return Math.hypot(dn, de) < ACCEPT_XY && Math.abs(dz) < ACCEPT_Z;
    }

    // ---- fault-injection effects (called by VirtualDrone before tick) ----

    /**
     * Wind drift: displaces the vehicle every tick while airborne; the vehicle
     * "fights" part of it (a position controller would), so only the residual
     * leaks into the flown track. Wind also biases the roll estimate.
     * v2: the drift is tracked separately so the attitude solver can exclude
     * it - a steady wind must not look like an acceleration spike.
     */
    public void applyWind(double dt, double windNorth, double windEast) {
        windDriftN = 0;
        windDriftE = 0;
        if (alt > 0.05) {
            // The autopilot holds the commanded line but leaves a 25% residual
            // cross-track error - enough to see on a track, not enough to crash.
            double leak = 0.25;
            windDriftN = windNorth * leak * dt;
            windDriftE = windEast * leak * dt;
            north += windDriftN;
            east += windDriftE;
            rollRad += windEast * 0.004; // steady banks a touch against crosswind
        }
    }

    /** Wind displacement applied this tick (excluded from the attitude solve). */
    private volatile double windDriftN;
    private volatile double windDriftE;

    // ---- MANUAL_CONTROL steering state (body frame) ----
    // volatile: 命令线程写（setManualVelocity/clearManual），tick 线程读
    private volatile boolean manualActive;
    private volatile double manualFwd;
    private volatile double manualRight;
    private volatile double manualUp;
    private volatile double manualYawRate;

    // ---- simulation step ----

    /**
     * Advance the physical state by dt seconds toward the current target.
     * Also integrates attitude tilt and battery drain.
     */
    public void tick(double dt) {
        long now = System.currentTimeMillis();
        if (dt <= 0) {
            dt = Math.max(0.001, (now - lastTickMs) / 1000.0);
        }
        lastTickMs = now;

        double prevNorth = north;
        double prevEast = east;
        double prevAlt = alt;
        // v2: previous velocity for the acceleration solve (displacement/dt
        // IS the new velocity, so the delta must be taken against the OLD one).
        double prevVelN = velN;
        double prevVelE = velE;

        if (manualActive) {
            stepManual(dt);
        } else {
            // 读取一次 TargetState 快照，避免 hasTarget() 与 stepTowardTarget() 之间的 TOCTOU 竞态
            TargetState ts = target.get();
            if (ts.hasTarget()) {
                stepTowardTarget(dt, ts);
            } else {
                velN = 0;
                velE = 0;
                groundSpeed = 0;
                vz = 0;
            }
        }

        // Battery drains only while off the ground, at a mode-weighted rate
        // (E4): hovering costs more than cruise (no translational lift),
        // climbing costs the most, descent is cheap (partial autorotation).
        if (alt > 0.05 || groundSpeed > 0.1 || vz != 0) {
            airborneSeconds += dt;
            drainSeconds += dt * drainFactor() * tempDrainFactor;
        }

        // Derived speeds from displacement difference.
        groundSpeed = Math.hypot(north - prevNorth, east - prevEast) / dt;
        vz = (alt - prevAlt) / dt;

        // Attitude v2: tilt along the REAL acceleration vector this tick
        // (a multirotor tilts exactly into the acceleration it produces),
        // plus a small sine wobble so it looks alive on a HUD. Wind drift is
        // excluded - a steady wind must not masquerade as an acceleration.
        double ax = (velN - prevVelN) / dt;
        double ay = (velE - prevVelE) / dt;
        double accelMag = Math.hypot(ax, ay);
        double tilt = Math.min(MAX_TILT, Math.atan(accelMag / G));
        double wobble = 0.03 * Math.sin(airborneSeconds * 1.7);
        if (accelMag > 0.05) {
            // tilt direction = acceleration direction; roll/pitch in body frame
            double tiltHeading = Math.atan2(ay, ax);
            double rel = normalizeAngleSigned(tiltHeading - yawRad);
            // roll banks into lateral acceleration, pitch tips nose-down on axial
            rollRad = tilt * Math.sin(rel) + wobble;
            pitchRad = -tilt * Math.cos(rel) * 0.6 + wobble;
        } else {
            rollRad = wobble;
            pitchRad = wobble;
        }
    }

    /** Velocity components kept across ticks (v2: acceleration-limited motion). */
    private volatile double velN;
    private volatile double velE;

    /** Mode-dependent power draw multiplier (E4). */
    private double drainFactor() {
        if (vz > 0.5) {
            return CLIMB_DRAIN;      // climbing is the most expensive regime
        }
        if (vz < -0.5) {
            return DESCENT_DRAIN;    // descending is cheap
        }
        if (groundSpeed < 0.5) {
            return HOVER_DRAIN;      // hover > cruise: no translational lift
        }
        return 1.0;                  // cruise baseline
    }

    /** Energy-seconds consumed by a photo (camera/gimbal/storage load). */
    public void drainForPhoto() {
        drainSeconds += PHOTO_ENERGY_SEC;
    }

    /**
     * 设置温度影响电池能耗的乘性因子（FR-11，M0b 环境气象）。
     * 由 {@link EnvironmentModel#tempDrainFactor()} 在 tick 前注入；默认 1.0 常温不变（FR-13/DFX 4.5）。
     *
     * @param factor 温度因子（1.0=常温基线，>1=能耗增加）
     */
    public void setTempDrainFactor(double factor) {
        this.tempDrainFactor = factor;
    }

    private static final double G = 9.81;
    /** Max horizontal acceleration (m/s^2): sporty multirotor ballpark. */
    private static final double ACCEL_MAX = 3.0;
    /** Max bank angle (rad): structural/comfort limit, bounds the turn rate. */
    private static final double MAX_TILT = Math.toRadians(30);
    /** Max yaw rate (rad/s): multiroters yaw far slower than they translate. */
    private static final double YAW_RATE_MAX = 1.8;

    /**
     * MANUAL flight: rotate the stick's body-frame velocity command into
     * the local frame and chase it with the same ACCEL_MAX-limited v2
     * integrator, so manual flight feels like mission flight (smooth spool,
     * real tilt). Yaw integrates the commanded rate.
     */
    private void stepManual(double dt) {
        // body (fwd/right) -> local (north/east) via current heading.
        // yaw: 0 = north, CCW positive; forward vector = (cos yaw, sin yaw)
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double wantN = manualFwd * cos + manualRight * sin;
        double wantE = -manualFwd * sin + manualRight * cos;

        // Accelerate toward the commanded velocity (same bound as autopilot).
        double dvn = wantN - velN;
        double dve = wantE - velE;
        double dvMag = Math.hypot(dvn, dve);
        if (dvMag > ACCEL_MAX * dt) {
            double k = (ACCEL_MAX * dt) / dvMag;
            dvn *= k;
            dve *= k;
        }
        velN += dvn;
        velE += dve;

        // Vertical: stick throttle maps to climb rate [-3, +3] m/s.
        double wantVz = manualUp;
        double dvz = wantVz - vz;
        double vzMax = 3.0 * dt;
        vz += Math.max(-vzMax, Math.min(vzMax, dvz));

        // Integrate. (Wind is applied by the caller before tick().)
        yawRad += manualYawRate * dt;

        north += velN * dt;
        east += velE * dt;
        alt += vz * dt;
        if (alt < 0) {
            alt = 0;
            if (vz < 0) {
                vz = 0;
            }
        }
        groundSpeed = Math.hypot(velN, velE);
    }

    private void stepTowardTarget(double dt, TargetState ts) {
        double dn = ts.north - north;
        double de = ts.east - east;
        double dz = ts.alt - alt;
        double distXy = Math.hypot(dn, de);

        double speedCmd = Double.isNaN(ts.speed) ? cruiseSpeed : Math.max(0, ts.speed);

        if (distXy > ACCEPT_XY) {
            // ---- v2: acceleration-limited velocity steering ----
            // Slow down near the target so we do not overshoot the acceptance
            // radius (braking distance = v^2 / (2a); start easing inside it).
            double vNow = Math.hypot(velN, velE);
            double brakeDist = vNow * vNow / (2 * ACCEL_MAX);
            double vCmd = speedCmd;
            if (distXy < brakeDist + 0.5) {
                vCmd = Math.max(0.5, speedCmd * distXy / Math.max(0.5, brakeDist + 0.5));
            }
            double dirN = dn / distXy;
            double dirE = de / distXy;
            double wantN = dirN * vCmd;
            double wantE = dirE * vCmd;

            // Bound the velocity change by ACCEL_MAX*dt (vector-wise).
            double dVn = wantN - velN;
            double dVe = wantE - velE;
            double dV = Math.hypot(dVn, dVe);
            double maxDv = ACCEL_MAX * dt;
            if (dV > maxDv && dV > 1e-9) {
                dVn *= maxDv / dV;
                dVe *= maxDv / dV;
            }
            velN += dVn;
            velE += dVe;

            north += velN * dt;
            east += velE * dt;

            // ---- coordinated turn: nose follows the velocity vector ----
            double speed = Math.hypot(velN, velE);
            if (speed > 0.3) {
                double wantYaw = Math.atan2(velE, velN);
                double dyaw = normalizeAngleSigned(wantYaw - yawRad);
                double maxDyaw = YAW_RATE_MAX * dt;
                dyaw = Math.max(-maxDyaw, Math.min(maxDyaw, dyaw));
                yawRad = normalizeAngle(yawRad + dyaw);
            }
        } else {
            // Inside acceptance: come to a stop at the target point.
            velN = 0;
            velE = 0;
            north = ts.north;
            east = ts.east;
        }

        if (Math.abs(dz) > 0.02) {
            double stepZ = Math.min(Math.abs(dz), VERT_SPEED * dt);
            alt += Math.signum(dz) * stepZ;
        } else {
            alt = ts.alt;
        }
    }

    /** Wrap angle to (-PI, PI]. */
    private static double normalizeAngleSigned(double a) {
        double r = a % (Math.PI * 2);
        if (r > Math.PI) {
            r -= Math.PI * 2;
        } else if (r <= -Math.PI) {
            r += Math.PI * 2;
        }
        return r;
    }

    /** Wrap angle to [0, 2*PI). */
    public static double normalizeAngle(double a) {
        double twoPi = Math.PI * 2;
        double r = a % twoPi;
        if (r < 0) {
            r += twoPi;
        }
        return r;
    }

    // ---- telemetry views ----

    /**
     * Reported GPS latitude. With noiseRadius > 0 the reported fix wanders
     * around the true position (the vehicle itself keeps flying the true one);
     * px4 reports EKF positions, but for fault-injection training the reported
     * GLOBAL_POSITION_INT drift is what the GCS should notice.
     */
    public double reportedLat(double noiseRadius) {
        double lat = lat();
        if (noiseRadius <= 0) {
            return lat;
        }
        return lat + (ThreadLocalRandom.current().nextDouble(-1, 1) * noiseRadius) / 111_320.0;
    }

    public double reportedLon(double noiseRadius) {
        double lon = lon();
        if (noiseRadius <= 0) {
            return lon;
        }
        // P1: 极点附近 cos(homeLat)=0 会导致除零产生 Infinity；
        // 极点处经度无意义，直接返回原经度不添加噪声。
        double cosLat = Math.cos(Math.toRadians(homeLat));
        if (Math.abs(cosLat) < 1e-6) {
            return lon;
        }
        return lon + (ThreadLocalRandom.current().nextDouble(-1, 1) * noiseRadius)
                / (111_320.0 * cosLat);
    }

    public double lat() {
        return GeoUtil.latOf(homeLat, homeLon, north, east);
    }

    public double lon() {
        return GeoUtil.lonOf(homeLat, homeLon, north, east);
    }

    public double north() {
        return north;
    }

    public double east() {
        return east;
    }

    public double alt() {
        return alt;
    }

    /** Current target altitude (NaN when no target). */
    public double targetAlt() {
        return target.get().alt;
    }

    public double yawRad() {
        return yawRad;
    }

    /** Heading in degrees [0, 360). */
    public int headingDeg() {
        return (int) Math.round(Math.toDegrees(normalizeAngle(yawRad))) % 360;
    }

    public double groundSpeed() {
        return groundSpeed;
    }

    public double vz() {
        return vz;
    }

    public double rollRad() {
        return rollRad;
    }

    public double pitchRad() {
        return pitchRad;
    }

    public long bootMillis() {
        return System.currentTimeMillis() - bootMillis;
    }

    /** Airborne (motor-running) seconds, drives the battery model. */
    public double airborneSeconds() {
        return airborneSeconds;
    }

    /** Battery voltage 15.8V -> 14.0V linear over mode-weighted flight time (E4). */
    public double batteryVoltage() {
        double f = Math.min(1.0, drainSeconds / BATTERY_SECONDS);
        return VOLT_FULL - (VOLT_FULL - VOLT_EMPTY) * f;
    }

    /** Battery remaining percentage consistent with the weighted voltage model. */
    public int batteryRemainingPct() {
        double f = Math.min(1.0, drainSeconds / BATTERY_SECONDS);
        return (int) Math.round(100.0 * (1.0 - f));
    }

    /** Mode-weighted energy seconds (E4 test hook). */
    public double drainSeconds() {
        return drainSeconds;
    }
}
