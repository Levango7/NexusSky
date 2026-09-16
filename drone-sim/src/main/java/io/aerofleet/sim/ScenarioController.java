package io.aerofleet.sim;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Fault-injection scenario controller: mutates the virtual drone's sensors and
 * link behavior on a scripted timeline so the cloud/GCS reaction chain can be
 * regression-tested without hardware.
 *
 * A scenario is "kind:offset[:duration[:param]]" (times in seconds):
 *   gps-loss:30:20          GPS fix drops 30s after boot, recovers 20s later
 *   link-loss:45:15         telemetry+commands black-hole for 15s at t=45s
 *   battery-fault:60        at t=60s battery jumps to a critical level
 *   wind:0:9999:6           sustained 6 m/s crosswind for the whole flight
 *   gps-noise:20:40:3       GPS position wanders within 3 m for 40s
 *   imu-bias:40:60:2        attitude estimate drifts at 2 deg/s for 60s
 *   baro-drift:30:60:0.1    reported altitude drifts 0.1 m/s for 60s
 *   mag-interference:50:20: yaw estimate wanders +/-15deg for 20s
 *
 * All state is confined to the simulator tick thread (called from tick()).
 */
public final class ScenarioController {

    public enum Kind { GPS_LOSS, LINK_LOSS, BATTERY_FAULT, WIND, GPS_NOISE,
                       IMU_BIAS, BARO_DRIFT, MAG_INTERFERENCE }

    public static final class Event {
        public final Kind kind;
        final double startSec;    // seconds since sim boot
        final double durationSec; // seconds the fault stays active
        final double param;       // kind-specific (wind m/s, noise m, battery %)

        Event(Kind kind, double startSec, double durationSec, double param) {
            this.kind = kind;
            this.startSec = startSec;
            this.durationSec = durationSec;
            this.param = param;
        }

        boolean activeAt(double t) {
            return t >= startSec && t < startSec + durationSec;
        }

        @Override
        public String toString() {
            return kind.name().toLowerCase().replace('_', '-') + "@" + startSec + "s";
        }
    }

    private final java.util.List<Event> events = new java.util.ArrayList<>();
    private final String scenarioSpec;
    private boolean announcedActive = false;

    public ScenarioController(String spec) {
        this.scenarioSpec = spec;
        parse(spec);
    }

    private void parse(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("none")) {
            return;
        }
        for (String part : spec.split(",")) {
            String[] seg = part.trim().split(":");
            if (seg.length < 2) {
                SimLog.warn("scenario segment ignored (need kind:offset): " + part);
                continue;
            }
            try {
                Kind kind = switch (seg[0].trim().toLowerCase()) {
                    case "gps-loss" -> Kind.GPS_LOSS;
                    case "link-loss" -> Kind.LINK_LOSS;
                    case "battery-fault" -> Kind.BATTERY_FAULT;
                    case "wind" -> Kind.WIND;
                    case "gps-noise" -> Kind.GPS_NOISE;
                    case "imu-bias" -> Kind.IMU_BIAS;
                    case "baro-drift" -> Kind.BARO_DRIFT;
                    case "mag-interference" -> Kind.MAG_INTERFERENCE;
                    default -> null;
                };
                if (kind == null) {
                    SimLog.warn("unknown scenario kind: " + seg[0]);
                    continue;
                }
                double start = Double.parseDouble(seg[1]);
                double duration = seg.length > 2 ? Double.parseDouble(seg[2]) : defaultDuration(kind);
                double param = seg.length > 3 ? Double.parseDouble(seg[3]) : defaultParam(kind);
                events.add(new Event(kind, start, duration, param));
                SimLog.info("scenario armed: " + kind.name().toLowerCase().replace('_', '-')
                        + " start=" + start + "s duration=" + duration + "s param=" + param);
            } catch (NumberFormatException e) {
                SimLog.warn("scenario segment ignored (bad number): " + part);
            }
        }
    }

    private static double defaultDuration(Kind k) {
        return switch (k) {
            case GPS_LOSS -> 20;
            case LINK_LOSS -> 15;
            case BATTERY_FAULT -> 9999; // sticky for the rest of the flight
            case WIND -> 9999;
            case GPS_NOISE -> 30;
            case IMU_BIAS -> 60;
            case BARO_DRIFT -> 60;
            case MAG_INTERFERENCE -> 20;
        };
    }

    private static double defaultParam(Kind k) {
        return switch (k) {
            case GPS_LOSS -> 0;
            case LINK_LOSS -> 0;
            case BATTERY_FAULT -> 12; // % remaining after the fault
            case WIND -> 5;           // m/s
            case GPS_NOISE -> 3;      // m radius
            case IMU_BIAS -> 2;       // deg/s drift rate
            case BARO_DRIFT -> 0.1;  // m/s altitude drift rate
            case MAG_INTERFERENCE -> 15; // deg wander amplitude
        };
    }

    public boolean enabled() {
        return !events.isEmpty();
    }

    /** True while any event is currently active (announces transitions once). */
    public boolean anyActive(double bootSec) {
        boolean active = false;
        for (Event e : events) {
            active = active || e.activeAt(bootSec);
        }
        if (active && !announcedActive) {
            announcedActive = true;
            SimLog.warn("scenario ACTIVE at t=" + (int) bootSec + "s (" + scenarioSpec + ")");
        }
        return active;
    }

    public boolean gpsLost(double bootSec) {
        return active(Kind.GPS_LOSS, bootSec);
    }

    public boolean linkLost(double bootSec) {
        return active(Kind.LINK_LOSS, bootSec);
    }

    public boolean batteryFault(double bootSec) {
        return active(Kind.BATTERY_FAULT, bootSec);
    }

    public double batteryFaultPct(double bootSec) {
        Event e = find(Kind.BATTERY_FAULT, bootSec);
        return e != null ? e.param : Double.NaN;
    }

    /** Wind vector (north, east m/s) while a WIND event is active, else (0,0). */
    public double[] windVector(double bootSec) {
        double[] out = new double[2];
        windVector(bootSec, out);
        return out;
    }

    /**
     * P2-2: 写入预分配数组，避免每 tick 分配 new double[2]。
     * 语义与 {@link #windVector(double)} 完全一致，结果写入 out[0]/out[1]。
     */
    public void windVector(double bootSec, double[] out) {
        Event e = find(Kind.WIND, bootSec);
        if (e == null) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        // Fixed pseudo-random direction per boot so flights are reproducible.
        double dirRad = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        out[0] = Math.cos(dirRad) * e.param;
        out[1] = Math.sin(dirRad) * e.param;
    }

    public boolean gpsNoisy(double bootSec) {
        return active(Kind.GPS_NOISE, bootSec);
    }

    /** GPS noise radius in meters while active, else 0. */
    public double gpsNoiseRadius(double bootSec) {
        Event e = find(Kind.GPS_NOISE, bootSec);
        return e != null ? e.param : 0;
    }

    /**
     * IMU gyro bias: accumulated attitude drift in DEGREES while the event is
     * active (rate * elapsed-since-start). Grows monotonically like a real
     * temperature-dependent gyro bias; the vehicle keeps flying true values.
     */
    public double imuBiasDeg(double bootSec) {
        Event e = find(Kind.IMU_BIAS, bootSec);
        if (e == null) {
            return 0;
        }
        return e.param * Math.max(0, bootSec - e.startSec);
    }

    /**
     * Barometer drift: accumulated altitude offset in METERS while active
     * (rate * elapsed). Reported altitude slowly diverges from truth - the
     * classic vertical-channel sensor failure look on a GCS.
     */
    public double baroDriftM(double bootSec) {
        Event e = find(Kind.BARO_DRIFT, bootSec);
        if (e == null) {
            return 0;
        }
        return e.param * Math.max(0, bootSec - e.startSec);
    }

    /**
     * Magnetometer interference: yaw wander in DEGREES while active. A slow
     * random walk bounded by the event's param amplitude (flying near power
     * lines / steel structures).
     */
    public double magWanderDeg(double bootSec) {
        Event e = find(Kind.MAG_INTERFERENCE, bootSec);
        if (e == null) {
            return 0;
        }
        // deterministic pseudo-walk from bootSec so telemetry is repeatable
        double phase = bootSec * 0.9;
        return e.param * Math.sin(phase) * (0.6 + 0.4 * Math.sin(phase * 0.37));
    }

    private boolean active(Kind kind, double t) {
        return find(kind, t) != null;
    }

    private Event find(Kind kind, double t) {
        for (Event e : events) {
            if (e.kind == kind && e.activeAt(t)) {
                return e;
            }
        }
        return null;
    }
}
