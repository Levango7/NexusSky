package io.aerofleet.sim;

/**
 * Small local-tangent-plane geodesy helpers: lat/lon to local meters and back,
 * using a fixed reference point (equirectangular approximation, plenty for a simulator).
 */
public final class GeoUtil {

    /** Earth radius in meters. */
    public static final double EARTH_R = 6371000.0;

    private GeoUtil() {
    }

    /** Meters per degree of latitude at the given latitude. */
    public static double metersPerDegLat(double refLatDeg) {
        return Math.PI / 180.0 * EARTH_R;
    }

    /** Meters per degree of longitude at the given latitude. */
    public static double metersPerDegLon(double refLatDeg) {
        return Math.PI / 180.0 * EARTH_R * Math.cos(Math.toRadians(refLatDeg));
    }

    /** East offset in meters from (refLat, refLon) to (lat, lon). */
    public static double east(double refLat, double refLon, double lat, double lon) {
        return (lon - refLon) * metersPerDegLon(refLat);
    }

    /** North offset in meters from (refLat, refLon) to (lat, lon). */
    public static double north(double refLat, double refLon, double lat, double lon) {
        return (lat - refLat) * metersPerDegLat(refLat);
    }

    /** Latitude for local north/east meters around the reference. */
    public static double latOf(double refLat, double refLon, double northM, double eastM) {
        return refLat + northM / metersPerDegLat(refLat);
    }

    /** Longitude for local north/east meters around the reference. */
    public static double lonOf(double refLat, double refLon, double northM, double eastM) {
        return refLon + eastM / metersPerDegLon(refLat);
    }
}
