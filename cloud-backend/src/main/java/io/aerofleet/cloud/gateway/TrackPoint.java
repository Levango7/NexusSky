package io.aerofleet.cloud.gateway;

/**
 * One track point of the flight path, kept per drone for replay.
 * Plain values so Jackson can serialize directly (camelCase JSON fields).
 */
public final class TrackPoint {

    public final double lat;
    public final double lon;
    public final double alt;
    public final long ts;

    public TrackPoint(double lat, double lon, double alt, long ts) {
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.ts = ts;
    }
}
