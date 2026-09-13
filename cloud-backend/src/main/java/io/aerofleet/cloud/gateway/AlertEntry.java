package io.aerofleet.cloud.gateway;

/**
 * One STATUSTEXT alert line received from a drone.
 */
public final class AlertEntry {

    public final int severity;      // MAV_SEVERITY_*
    public final String text;
    public final long ts;

    public AlertEntry(int severity, String text, long ts) {
        this.severity = severity;
        this.text = text;
        this.ts = ts;
    }
}
