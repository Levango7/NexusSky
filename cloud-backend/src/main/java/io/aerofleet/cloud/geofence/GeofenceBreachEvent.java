package io.aerofleet.cloud.geofence;

/**
 * 围栏越界事件。
 * <p>
 * 当无人机位置相对于围栏区域发生状态切换时生成：
 * <ul>
 *   <li>{@link BreachType#ENTER ENTER}：进入禁飞区（围栏区域为禁止进入时）。</li>
 *   <li>{@link BreachType#EXIT EXIT}：离开允许区（围栏区域为允许活动时）。</li>
 * </ul>
 * <p>
 * 不可变值对象；由 {@link GeofenceMonitor} 生成并存入 {@link GeofenceStore}。
 */
public final class GeofenceBreachEvent {

    /** 越界类型：进入禁飞区 / 离开允许区。 */
    public enum BreachType { ENTER, EXIT }

    private final int sysid;
    private final int zoneId;
    private final String zoneName;
    private final BreachType breachType;
    private final double lat;
    private final double lon;
    private final long timestampMs;

    public GeofenceBreachEvent(int sysid, int zoneId, String zoneName,
                               BreachType breachType,
                               double lat, double lon, long timestampMs) {
        this.sysid = sysid;
        this.zoneId = zoneId;
        this.zoneName = zoneName;
        this.breachType = breachType;
        this.lat = lat;
        this.lon = lon;
        this.timestampMs = timestampMs;
    }

    public int getSysid() { return sysid; }
    public int getZoneId() { return zoneId; }
    public String getZoneName() { return zoneName; }
    public BreachType getBreachType() { return breachType; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public long getTimestampMs() { return timestampMs; }

    @Override
    public String toString() {
        return "GeofenceBreachEvent{sysid=" + sysid + ", zoneId=" + zoneId
                + ", zone='" + zoneName + "', type=" + breachType
                + ", lat=" + lat + ", lon=" + lon + ", ts=" + timestampMs + "}";
    }
}