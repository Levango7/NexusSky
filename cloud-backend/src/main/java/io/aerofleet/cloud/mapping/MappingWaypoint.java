package io.aerofleet.cloud.mapping;

/**
 * 测绘航点（测绘航线中的一个节点）。
 * <p>
 * 每个航点包含位置、航向、相机角度、动作类型与照片编号，
 * 由 {@link MappingRoutePlanner} 根据测绘类型与区域生成。
 */
public final class MappingWaypoint {

    /** 航点动作类型。 */
    public enum Action {
        /** 飞越该点（不停留）。 */
        FLY,
        /** 到达该点后拍照。 */
        PHOTO,
        /** 到达该点后悬停。 */
        HOVER
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
    /** 相机俯仰角（degrees, 0=垂直向下, 90=水平向前）。 */
    public final double cameraAngleDeg;
    /** 到达该航点后执行的动作。 */
    public final Action action;
    /** 拍照编号（仅 PHOTO 动作有效，从 1 开始递增）。 */
    public final int photoId;

    public MappingWaypoint(int seq, double lat, double lon, double alt,
                           double headingDeg, double cameraAngleDeg,
                           Action action, int photoId) {
        this.seq = seq;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.headingDeg = headingDeg;
        this.cameraAngleDeg = cameraAngleDeg;
        this.action = action;
        this.photoId = photoId;
    }

    /** 创建一个飞越航点（cameraAngleDeg=0, photoId=0）。 */
    public static MappingWaypoint fly(int seq, double lat, double lon, double alt,
                                      double headingDeg) {
        return new MappingWaypoint(seq, lat, lon, alt, headingDeg, 0.0, Action.FLY, 0);
    }

    /** 创建一个拍照航点。 */
    public static MappingWaypoint photo(int seq, double lat, double lon, double alt,
                                        double headingDeg, double cameraAngleDeg, int photoId) {
        return new MappingWaypoint(seq, lat, lon, alt, headingDeg, cameraAngleDeg, Action.PHOTO, photoId);
    }

    /** 创建一个悬停航点。 */
    public static MappingWaypoint hover(int seq, double lat, double lon, double alt,
                                        double headingDeg) {
        return new MappingWaypoint(seq, lat, lon, alt, headingDeg, 0.0, Action.HOVER, 0);
    }

    @Override
    public String toString() {
        return "MappingWaypoint{seq=" + seq + ", lat=" + lat + ", lon=" + lon
                + ", alt=" + alt + ", heading=" + headingDeg
                + ", cameraAngle=" + cameraAngleDeg + ", action=" + action
                + ", photoId=" + photoId + "}";
    }
}