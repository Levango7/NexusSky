package io.aerofleet.cloud.gateway;

/**
 * Aggregated state of one drone, updated from incoming MAVLink messages.
 * All fields are volatile: the UDP receive thread writes, HTTP/WS threads read.
 * Field names map directly to the camelCase JSON contract of gcs-web
 * (sysid, online, battery, voltage, mode, lat, lon, relativeAlt, groundspeed,
 * heading, satellites, missionSeq, missionTotal, ...).
 */
public final class DroneSnapshot {

    public final int sysid;

    // --- heartbeat / liveness ---
    public volatile long lastHeartbeatMs;      // epoch ms of last HEARTBEAT
    public volatile boolean online;
    public volatile int customMode;             // HEARTBEAT custom_mode (PX4 nav state)
    public volatile int baseMode;              // MAV_MODE_FLAG bits
    public volatile int systemStatus;          // MAV_STATE
    public volatile boolean armed;             // derived from baseMode SAFETY_ARMED bit
    public volatile String mode = "UNKNOWN";   // derived flight-mode label

    // --- battery (SYS_STATUS) ---
    public volatile int battery = -1;          // %
    public volatile int voltage = -1;          // mV
    public volatile int current = -1;          // 10 mA units, -1 unknown
    public volatile int load = -1;             // permille

    // --- position (GLOBAL_POSITION_INT) ---
    public volatile double lat = Double.NaN;
    public volatile double lon = Double.NaN;
    public volatile double relativeAlt = Double.NaN;
    public volatile double amslAlt = Double.NaN;
    public volatile double vx = Double.NaN;    // m/s NED
    public volatile double vy = Double.NaN;
    public volatile double vz = Double.NaN;

    // --- instruments ---
    public volatile double roll = Double.NaN;   // deg
    public volatile double pitch = Double.NaN;  // deg
    public volatile double yaw = Double.NaN;    // deg
    public volatile double groundspeed = Double.NaN;   // m/s (VFR_HUD)
    public volatile double airspeed = Double.NaN;
    public volatile double climb = Double.NaN;  // m/s
    public volatile double heading = Double.NaN;  // deg, from VFR_HUD/GPS
    public volatile int throttle = -1;

    // --- gps ---
    public volatile int fixType;               // GPS_RAW_INT fix_type
    public volatile int satellites = -1;
    public volatile int eph = -1;               // HDOP*100
    /** Derived: 3D fix with enough satellites; false during gps-loss scenarios. */
    public volatile boolean gpsHealthy = true;

    // --- link quality (RADIO_STATUS, E1) ---
    /** Downlink RSSI [dBm]; NaN before the first RADIO_STATUS. */
    public volatile double rssiDbm = Double.NaN;
    /** Uplink RSSI [dBm] (remrssi); NaN when not reported. */
    public volatile double remRssiDbm = Double.NaN;

    // --- mission progress (MISSION_CURRENT) ---
    public volatile int missionSeq = -1;
    public volatile int missionTotal = -1;
    public volatile int missionState = 0;       // MISSION_STATE_*

    // --- histories ---
    public final BoundedHistory<TrackPoint> track = new BoundedHistory<>(200);
    public final BoundedHistory<AlertEntry> alerts = new BoundedHistory<>(20);

    public DroneSnapshot(int sysid) {
        this.sysid = sysid;
    }

    /** Distance-independent freshness probe: ms since last heartbeat. */
    public long msSinceHeartbeat() {
        return System.currentTimeMillis() - lastHeartbeatMs;
    }
}
