package io.aerofleet.mavlink.hardware;

/**
 * 航点数据类：任务中的一个导航点。
 * <p>
 * 不可变对象，构造时进行范围验证，确保航点数据合法。
 */
public final class Waypoint {

    private final double lat;        // 纬度（度，-90 ~ 90）
    private final double lon;        // 经度（度，-180 ~ 180）
    private final double alt;        // 高度（米，相对起飞点，>= 0）
    private final double holdTime;   // 悬停时间（秒，>= 0）
    private final int command;       // MAVLink 命令 ID（如 MAV_CMD_NAV_WAYPOINT=16）

    /**
     * 构造航点并进行范围验证。
     *
     * @param lat      纬度，范围 [-90, 90]
     * @param lon      经度，范围 [-180, 180]
     * @param alt      高度（米），>= 0
     * @param holdTime 悬停时间（秒），>= 0
     * @param command  MAVLink 命令 ID
     * @throws IllegalArgumentException 如果参数超出合法范围
     */
    public Waypoint(double lat, double lon, double alt, double holdTime, int command) {
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("纬度超出范围 [-90, 90]: " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new IllegalArgumentException("经度超出范围 [-180, 180]: " + lon);
        }
        if (alt < 0) {
            throw new IllegalArgumentException("高度不能为负数: " + alt);
        }
        if (holdTime < 0) {
            throw new IllegalArgumentException("悬停时间不能为负数: " + holdTime);
        }
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.holdTime = holdTime;
        this.command = command;
    }

    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public double getAlt() { return alt; }
    public double getHoldTime() { return holdTime; }
    public int getCommand() { return command; }

    @Override
    public String toString() {
        return "Waypoint{lat=" + lat
                + ", lon=" + lon
                + ", alt=" + alt
                + ", holdTime=" + holdTime
                + ", command=" + command
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Waypoint waypoint = (Waypoint) o;
        return Double.compare(waypoint.lat, lat) == 0
                && Double.compare(waypoint.lon, lon) == 0
                && Double.compare(waypoint.alt, alt) == 0
                && Double.compare(waypoint.holdTime, holdTime) == 0
                && command == waypoint.command;
    }

    @Override
    public int hashCode() {
        int result = 1;
        long temp;
        temp = Double.doubleToLongBits(lat); result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(lon); result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(alt); result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(holdTime); result = 31 * result + (int) (temp ^ (temp >>> 32));
        result = 31 * result + command;
        return result;
    }
}