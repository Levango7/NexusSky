package io.aerofleet.cloud.spi;

/**
 * 航路点，表示航线规划结果中的一个节点。
 * <p>
 * 每个航路点包含经纬度、高度和可选的动作类型（如拍照、悬停等）。
 */
public class Waypoint {

    /** 纬度（度） */
    private final double latitude;

    /** 经度（度） */
    private final double longitude;

    /** 高度（米） */
    private final double altitude;

    /** 航路点动作类型（如 "waypoint"、"loiter"、"photo" 等） */
    private final String action;

    /** 在该航路点的停留时间（秒，0 表示不停留） */
    private final double loiterTime;

    public Waypoint(double latitude, double longitude, double altitude) {
        this(latitude, longitude, altitude, "waypoint", 0.0);
    }

    public Waypoint(double latitude, double longitude, double altitude, String action, double loiterTime) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.action = action;
        this.loiterTime = loiterTime;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getAltitude() {
        return altitude;
    }

    public String getAction() {
        return action;
    }

    public double getLoiterTime() {
        return loiterTime;
    }

    @Override
    public String toString() {
        return "Waypoint{lat=" + latitude + ", lon=" + longitude
                + ", alt=" + altitude + ", action='" + action + "', loiter=" + loiterTime + '}';
    }
}