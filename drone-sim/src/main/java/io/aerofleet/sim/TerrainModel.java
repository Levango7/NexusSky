package io.aerofleet.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple analytic terrain: a flat ground plane plus Gaussian hills, queried
 * in local north/east meters around the home reference. Good enough to make
 * mission planning mistakes (a waypoint behind a ridge) observable and to
 * give the ground-collision guard something real to check against.
 *
 * Configured from the --terrain argument: "hill:north:east:radiusM:heightM"
 * segments, comma-joined. Example:
 *   --terrain hill:300:100:150:80,hill:-500:-200:200:40
 * (a 80 m hill centered 300 m north / 100 m east of home, 150 m wide,
 *  plus a 40 m hill to the southwest).
 *
 * All state is immutable and thread-safe.
 */
public final class TerrainModel {

    public static final class Hill {
        public final double north;
        public final double east;
        public final double radiusM;
        public final double heightM;

        Hill(double north, double east, double radiusM, double heightM) {
            this.north = north;
            this.east = east;
            this.radiusM = radiusM;
            this.heightM = heightM;
        }
    }

    private final List<Hill> hills;

    private TerrainModel(List<Hill> hills) {
        this.hills = hills;
    }

    /** Flat world: no hills (default when --terrain is absent). */
    public static TerrainModel flat() {
        return new TerrainModel(List.of());
    }

    /**
     * Parse a terrain spec: comma-joined "hill:north:east:radius:height" segments.
     * Malformed segments are skipped with a warning (fail-soft, like PX4 params).
     */
    public static TerrainModel parse(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("flat")) {
            return flat();
        }
        List<Hill> hills = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] seg = part.trim().split(":");
            if (seg.length != 5 || !seg[0].trim().equalsIgnoreCase("hill")) {
                SimLog.warn("terrain segment ignored (need hill:north:east:radius:height): " + part);
                continue;
            }
            try {
                hills.add(new Hill(Double.parseDouble(seg[1]), Double.parseDouble(seg[2]),
                        Double.parseDouble(seg[3]), Double.parseDouble(seg[4])));
            } catch (NumberFormatException e) {
                SimLog.warn("terrain segment ignored (bad number): " + part);
            }
        }
        return new TerrainModel(List.copyOf(hills));
    }

    /** Terrain elevation (m above home ground level) at a local north/east point. */
    public double elevationAt(double north, double east) {
        double elev = 0;
        for (Hill h : hills) {
            double d = Math.hypot(north - h.north, east - h.east);
            elev += h.heightM * Math.exp(-(d * d) / (2 * h.radiusM * h.radiusM));
        }
        return elev;
    }

    /** True when the vehicle at this position/altitude is below the terrain. */
    public boolean collided(double north, double east, double alt) {
        return alt < elevationAt(north, east) - 0.5;
    }

    public List<Hill> hills() {
        return hills;
    }

    /** One-line summary for the startup log. */
    public String summary() {
        if (hills.isEmpty()) {
            return "flat";
        }
        StringBuilder sb = new StringBuilder();
        for (Hill h : hills) {
            sb.append(String.format("hill(n=%.0f,e=%.0f,r=%.0f,h=%.0f) ", h.north, h.east,
                    h.radiusM, h.heightM));
        }
        return sb.toString().trim();
    }
}
