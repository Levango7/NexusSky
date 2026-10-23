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

    // --- tenant isolation ---
    public volatile Integer tenantId;

    // --- heartbeat / liveness ---
    public volatile long lastHeartbeatMs;      // epoch ms of last HEARTBEAT
    public volatile boolean online;
    /** 设备接入协议（F5 双栈）：mavlink（缺省，既有注册路径）/ dji-cloud（Cloud API 栈标记）。 */
    public volatile String protocol = "mavlink";
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

    /** F6 RTK 状态标签（MAVLink GPS_FIX_TYPE 口径：5=RTK_FLOAT/6=RTK_FIXED/3=3D_FIX）。 */
    public String rtkStatus() {
        return switch (fixType) {
            case 6 -> "RTK_FIXED";
            case 5 -> "RTK_FLOAT";
            default -> fixType >= 3 ? "STANDALONE" : "NONE";
        };
    }
    public volatile int satellites = -1;
    public volatile int eph = -1;               // HDOP*100
    /** Derived: 3D fix with enough satellites; false during gps-loss scenarios. */
    public volatile boolean gpsHealthy = true;

    // --- 飞行阶段（EXTENDED_SYS_STATE, 2026-10-24）---
    /**
     * MAV_LANDED_STATE：0=UNDEFINED 1=ON_GROUND 2=IN_AIR 3=TAKEOFF 4=LANDING；-1 = 尚未收到该消息。
     * <p>
     * 为什么不能只用 {@link #mode}：mode 来自 HEARTBEAT.custom_mode，是**固件相关的导航状态**，
     * PX4 与 ArduPilot 对同一数字的解释不同；landed_state 是官方定义的、与机型无关的飞行阶段。
     * 接真机后"起飞中 / 降落中"这两句话只有它有资格说。
     */
    public volatile int landedState = -1;
    /** MAV_VTOL_STATE：0=UNDEFINED 1=TRANS_TO_FW 2=TRANS_TO_MC 3=MC 4=FW；-1 = 尚未收到。 */
    public volatile int vtolState = -1;

    // --- 固件版本（AUTOPILOT_VERSION, 2026-10-25）---
    /**
     * PX4 口径的固件版本整数：{@code (major<<24)|(minor<<16)|patch}；0 = 尚未收到/未上报。
     * 接真机后"这是哪版固件、支不支持某项能力"只有这条消息能回答。
     */
    public volatile int flightSwVersion = 0;

    /** 同上的人类可读形式（"1.14.7"）；null = 尚未收到。 */
    public volatile String firmwareVersion = null;

    // --- link quality (RADIO_STATUS, E1) ---
    /** Downlink RSSI [dBm]; NaN before the first RADIO_STATUS. */
    public volatile double rssiDbm = Double.NaN;
    /** Uplink RSSI [dBm] (remrssi); NaN when not reported. */
    public volatile double remRssiDbm = Double.NaN;

    // --- M0b 环境气象状态（ENVIRONMENT_STATUS, FR-24）---
    /** 环境温度 °C；NaN before the first ENVIRONMENT_STATUS。 */
    public volatile double envTemperature = Double.NaN;
    /** 环境湿度 %。 */
    public volatile double envHumidity = Double.NaN;
    /** 环境风速 m/s。 */
    public volatile double envWindSpeed = Double.NaN;
    /** 风向 deg。 */
    public volatile double envWindDirection = Double.NaN;
    /** 阵风 m/s。 */
    public volatile double envGust = Double.NaN;
    /** 天气枚举 code（0=CLEAR,1=CLOUDY,2=RAIN,3=SNOW,4=FOG）。 */
    public volatile int envWeather = 0;
    /** 能见度 m。 */
    public volatile int envVisibility = 10000;
    /** 降雨率 mm/h。 */
    public volatile int envRainRate = 0;

    // --- M3 热成像（THERMAL_DATA 30012，2026-10-24 接线，P2 #19）---
    /** 热像分析均值（℃）；NaN before the first THERMAL_DATA。 */
    public volatile double thermalTempMean = Double.NaN;
    public volatile double thermalTempMin = Double.NaN;
    public volatile double thermalTempMax = Double.NaN;
    public volatile double thermalTempStdDev = Double.NaN;
    /** 热点数量；-1 = 从未上报。 */
    public volatile int thermalHotspotCount = -1;
    /** 最近一帧 THERMAL_DATA 的到达时刻（epoch ms）；0 = 从未上报。 */
    public volatile long thermalUpdatedAtMs;

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
