package io.aerofleet.cloud.twin;

public class TwinState {
    public final int sysid;
    public final double lat, lon, alt, heading, velocity, battery;
    public final long syncTimestamp;
    public final double driftMeters;

    public TwinState(int sysid, double lat, double lon, double alt, double heading, double velocity, double battery, long ts, double drift) {
        this.sysid = sysid; this.lat = lat; this.lon = lon; this.alt = alt;
        this.heading = heading; this.velocity = velocity; this.battery = battery;
        this.syncTimestamp = ts; this.driftMeters = drift;
    }
}
