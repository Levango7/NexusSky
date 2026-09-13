package io.aerofleet.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Synthetic ground-target world: vehicles and pedestrians moving around a
 * home reference in lat/lon. This is the "reality" the camera sees - the
 * geolocation solver (P3-3) and the oracle detector compare against it,
 * closing the loop honestly (the pipeline must find what is actually there).
 *
 * Config: "kind:lat,lon[:speedMps[:headingDeg[:turnRateDegPerS]]]" segments
 * joined by SEMICOLONS (the coordinate pair itself contains a comma).
 *   vehicle = fast, straight lines with rare turns
 *   pedestrian = slow random walk
 *   static = parked object
 * Example --targets "vehicle:22.5925,113.9360:14:90:0.5;pedestrian:22.5915,113.9355:1.2"
 *
 * Motion is integrated in a local N/E meter frame (fast + exact) and converted
 * back to lat/lon on demand.
 */
public final class TargetSimulator {

    public enum Kind { VEHICLE, PEDESTRIAN, STATIC }

    public static final class Target {
        public final int id;
        public final Kind kind;
        double north;      // meters from home
        double east;
        final double speedMps;
        double headingRad; // world frame, 0 = north
        final double turnRateRadS; // vehicles: gentle wander
        final Random rng = new Random();

        Target(int id, Kind kind, double north, double east,
               double speedMps, double headingRad, double turnRateRadS) {
            this.id = id;
            this.kind = kind;
            this.north = north;
            this.east = east;
            this.speedMps = speedMps;
            this.headingRad = headingRad;
            this.turnRateRadS = turnRateRadS;
        }
    }

    private final double homeLat;
    private final double homeLon;
    private final List<Target> targets = new ArrayList<>();
    private int nextId = 1;
    private double simTimeSec;
    private static final double M_PER_DEG_LAT = 111_320.0;

    public TargetSimulator(double homeLat, double homeLon) {
        this.homeLat = homeLat;
        this.homeLon = homeLon;
    }

    /** Parse "kind:lat,lon[:speed[:heading[:turnRate]]]" segments. */
    public static TargetSimulator parse(double homeLat, double homeLon, String spec) {
        TargetSimulator sim = new TargetSimulator(homeLat, homeLon);
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("none")) {
            return sim;
        }
        for (String part : spec.split(";")) {
            // vehicle:22.5925,113.9360:14:90:0.5 - split on ':'; the coords
            // pair itself is the second field and contains a comma by design.
            String[] seg = part.trim().split(":");
            if (seg.length < 2) {
                SimLog.warn("target ignored (need kind:lat,lon[:speed]): " + part);
                continue;
            }
            Kind kind = switch (seg[0].trim().toLowerCase()) {
                case "vehicle", "car" -> Kind.VEHICLE;
                case "pedestrian", "person" -> Kind.PEDESTRIAN;
                case "static", "parked" -> Kind.STATIC;
                default -> null;
            };
            if (kind == null) {
                SimLog.warn("target kind unknown (vehicle|pedestrian|static): " + seg[0]);
                continue;
            }
            String[] coords = seg[1].trim().split(",");
            if (coords.length != 2) {
                SimLog.warn("target coords need lat,lon: " + seg[1]);
                continue;
            }
            try {
                double lat = Double.parseDouble(coords[0]);
                double lon = Double.parseDouble(coords[1]);
                double speed = seg.length > 2 ? Double.parseDouble(seg[2])
                        : (kind == Kind.VEHICLE ? 12 : kind == Kind.PEDESTRIAN ? 1.3 : 0);
                double heading = seg.length > 3 ? Double.parseDouble(seg[3]) : 0;
                double turn = seg.length > 4 ? Double.parseDouble(seg[4]) : 0.3;
                double[] ne = latLonToNe(homeLat, homeLon, lat, lon);
                sim.add(new Target(sim.nextId++, kind, ne[0], ne[1], speed,
                        Math.toRadians(heading), Math.toRadians(turn)));
            } catch (NumberFormatException e) {
                SimLog.warn("target number malformed: " + part);
            }
        }
        return sim;
    }

    void add(Target t) {
        targets.add(t);
    }

    /** All live targets (ground truth; used by the camera projection). */
    public java.util.List<Target> listTargets() {
        return java.util.Collections.unmodifiableList(targets);
    }

    public boolean isEmpty() {
        return targets.isEmpty();
    }

    public int size() {
        return targets.size();
    }

    /** Advance all targets by dt seconds (single-threaded with the tick loop). */
    public void tick(double dt) {
        simTimeSec += dt;
        for (Target t : targets) {
            if (t.kind == Kind.STATIC) {
                continue;
            }
            // Wander: heading += turnRate * slow random walk + baseline turn
            double walk = (t.rng.nextDouble() - 0.5) * 2 * t.turnRateRadS;
            t.headingRad += walk * dt;
            // Pedestrians meander more; vehicles hold lanes longer.
            t.north += Math.cos(t.headingRad) * t.speedMps * dt;
            t.east += Math.sin(t.headingRad) * t.speedMps * dt;
        }
    }

    /** JSON snapshot of the world (ground truth for detection + geolocation). */
    public String snapshotJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Target t : targets) {
            double[] ll = neToLatLon(homeLat, homeLon, t.north, t.east);
            out.add(Map.of(
                    "id", t.id,
                    "kind", t.kind.name().toLowerCase(),
                    "lat", ll[0],
                    "lon", ll[1],
                    "speedMps", t.speedMps,
                    "headingDeg", Math.toDegrees(t.headingRad)));
        }
        return toJson(out);
    }

    private static String toJson(Object v) {
        // tiny hand-rolled serializer: no Jackson dependency in drone-sim
        StringBuilder sb = new StringBuilder();
        appendJson(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJson(StringBuilder sb, Object v) {
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                appendJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJson(sb, o);
            }
            sb.append(']');
        } else if (v instanceof String s) {
            sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else if (v instanceof Double d) {
            if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
                sb.append(d.longValue()); // 22.0 -> 22 keeps JSON tidy
            } else {
                sb.append(d);
            }
        } else {
            sb.append(v); // Integer/Boolean
        }
    }

    // ---- geo helpers ----

    static double[] latLonToNe(double homeLat, double homeLon, double lat, double lon) {
        double dn = (lat - homeLat) * M_PER_DEG_LAT;
        double de = (lon - homeLon) * M_PER_DEG_LAT * Math.cos(Math.toRadians(homeLat));
        return new double[]{dn, de};
    }

    static double[] neToLatLon(double homeLat, double homeLon, double north, double east) {
        double lat = homeLat + north / M_PER_DEG_LAT;
        double lon = homeLon + east / (M_PER_DEG_LAT * Math.cos(Math.toRadians(homeLat)));
        return new double[]{lat, lon};
    }

    /** Static wrapper for other classes (camera model, geolocation). */
    public static double[] neToLatLonStatic(double homeLat, double homeLon,
                                            double north, double east) {
        return neToLatLon(homeLat, homeLon, north, east);
    }
}
