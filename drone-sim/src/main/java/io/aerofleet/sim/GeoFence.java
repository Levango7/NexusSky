package io.aerofleet.sim;

import java.util.List;

/**
 * Geofence: a convex-or-concave polygon in local north/east meters plus a
 * ceiling altitude, matching what PX4's GF_* parameters enforce. A mission
 * or a manual flight that leaves the polygon or exceeds the ceiling must
 * trigger the fence failsafe (GF_ACTION=RTL default).
 *
 * Parsed from --fence "n1,e1:n2,e2:...[:ceilingM]": the last segment may be
 * a bare number, which is the max AMSL-relative altitude. Example:
 *   --fence "-600,-600:600,-600:600,600:-600,600:120"
 * = a 1.2 km square around home with a 120 m ceiling.
 *
 * Immutable and thread-safe.
 */
public final class GeoFence {

    private final double[] xs;      // north
    private final double[] ys;      // east
    private final double ceilingM;
    private final boolean enabled;

    private GeoFence(double[] xs, double[] ys, double ceilingM, boolean enabled) {
        this.xs = xs;
        this.ys = ys;
        this.ceilingM = ceilingM;
        this.enabled = enabled;
    }

    /** Disabled fence: everything is inside (default when --fence is absent). */
    public static GeoFence disabled() {
        return new GeoFence(new double[0], new double[0], Double.MAX_VALUE, false);
    }

    /**
     * Parse a fence spec. Malformed input disables the fence with a warning
     * (fail-open like a GCS typo, the pilot notices on the startup log).
     */
    public static GeoFence parse(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("off")) {
            return disabled();
        }
        try {
            java.util.List<double[]> pts = new java.util.ArrayList<>();
            double ceiling = 500;
            for (String part : spec.split(":")) {
                String[] xy = part.trim().split(",");
                if (xy.length == 1) {
                    ceiling = Double.parseDouble(xy[0]);   // trailing ceiling
                    continue;
                }
                if (xy.length != 2) {
                    throw new IllegalArgumentException("segment '" + part + "'");
                }
                pts.add(new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
            }
            if (pts.size() < 3) {
                throw new IllegalArgumentException("need at least 3 polygon vertices");
            }
            double[] xs = new double[pts.size()];
            double[] ys = new double[pts.size()];
            for (int i = 0; i < pts.size(); i++) {
                xs[i] = pts.get(i)[0];
                ys[i] = pts.get(i)[1];
            }
            return new GeoFence(xs, ys, ceiling, true);
        } catch (RuntimeException e) {
            SimLog.warn("fence spec ignored (" + e.getMessage() + "): " + spec);
            return disabled();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public double ceilingM() {
        return ceilingM;
    }

    /** Inside-polygon test (ray casting, works for concave polygons too). */
    public boolean contains(double north, double east) {
        if (!enabled) {
            return true;
        }
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            boolean crosses = (ys[i] > east) != (ys[j] > east);
            if (crosses) {
                double xAt = xs[j] + (xs[i] - xs[j]) * (east - ys[j]) / (ys[i] - ys[j]);
                if (north < xAt) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /** Violation verdict for a position/altitude, null when fully inside. */
    public Violation violationAt(double north, double east, double alt) {
        if (!enabled) {
            return null;
        }
        if (!contains(north, east)) {
            return Violation.LATERAL;
        }
        if (alt > ceilingM) {
            return Violation.CEILING;
        }
        return null;
    }

    public enum Violation { LATERAL, CEILING }

    /** First polygon vertex (for logging). */
    public String summary() {
        if (!enabled) {
            return "disabled";
        }
        return xs.length + "-vertex polygon, ceiling " + ceilingM + "m";
    }
}
