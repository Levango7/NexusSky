package io.aerofleet.cloud.inspection;

/**
 * 航点（巡检航线中的一个节点）。
 * <p>
 * 每个航点包含位置、航向、速度、动作与停留时间，由 {@link RoutePlannerService}
 * 根据巡检模板与区域生成，下发给无人机执行。
 */
public final class Waypoint {

    /** 航点动作类型。 */
    public enum Action {
        /** 飞越该点（不停留）。 */
        FLY,
        /** 到达该点后拍照。 */
        PHOTO,
        /** 到达该点后悬停。 */
        HOVER,
        /** 到达该点后执行扫描（多角度拍照）。 */
        SCAN
    }

    /** 序号（从 0 开始，按执行顺序递增）。 */
    public final int seq;
    /** 纬度（WGS84, degrees）。 */
    public final double lat;
    /** 经度（WGS84, degrees）。 */
    public final double lon;
    /** 海拔高度（m）。 */
    public final double alt;
    /** 航向角（degrees, 0~360, 正北为 0）。 */
    public final double headingDeg;
    /** 目标速度（m/s）。 */
    public final double speedMps;
    /** 到达该航点后执行的动作。 */
    public final Action action;
    /** 停留时间（秒），仅对 PHOTO/HOVER/SCAN 有效。 */
    public final double holdTimeSec;

    public Waypoint(int seq, double lat, double lon, double alt,
                    double headingDeg, double speedMps, Action action, double holdTimeSec) {
        this.seq = seq;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.headingDeg = headingDeg;
        this.speedMps = speedMps;
        this.action = action;
        this.holdTimeSec = holdTimeSec;
    }

    /** 创建一个仅飞越的航点（holdTime=0）。 */
    public static Waypoint fly(int seq, double lat, double lon, double alt,
                               double headingDeg, double speedMps) {
        return new Waypoint(seq, lat, lon, alt, headingDeg, speedMps, Action.FLY, 0);
    }

    /** 创建一个拍照航点。 */
    public static Waypoint photo(int seq, double lat, double lon, double alt,
                                 double headingDeg, double speedMps, double holdTimeSec) {
        return new Waypoint(seq, lat, lon, alt, headingDeg, speedMps, Action.PHOTO, holdTimeSec);
    }

    @Override
    public String toString() {
        return "Waypoint{seq=" + seq + ", lat=" + lat + ", lon=" + lon
                + ", alt=" + alt + ", action=" + action + "}";
    }
}