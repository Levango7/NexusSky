package io.aerofleet.sim.edge;

public class FusedState {
    public final double lat, lon, alt, heading, velocity;
    public final double accuracy;
    public final int sensorMask; // bit0=GPS bit1=IMU bit2=Vision bit3=LiDAR

    public FusedState(double lat, double lon, double alt, double heading, double velocity, double accuracy, int mask) {
        this.lat = lat; this.lon = lon; this.alt = alt; this.heading = heading;
        this.velocity = velocity; this.accuracy = accuracy; this.sensorMask = mask;
    }
}
