package io.aerofleet.cloud.mission.emergency;

import java.util.Objects;

/**
 * 地理目标（无人机侦察发现的目标）。
 * <p>
 * 封装无人机航拍画面中识别出的目标信息，包括经纬度、海拔、目标类型、
 * 置信度和来源无人机系统 ID。由 {@link AirGroundCoordinationService#triggerPtzTracking(GeoTarget)}
 * 用于触发安防设备 PTZ 联动跟踪。
 * <p>
 * 不可变值对象：所有字段在构造后不可变，线程安全。
 *
 * @see AirGroundCoordinationService
 */
public final class GeoTarget {

    /** 目标类型枚举。 */
    public enum TargetType {
        /** 人员。 */
        PERSON,
        /** 车辆。 */
        VEHICLE,
        /** 建筑/设施。 */
        STRUCTURE,
        /** 火源/烟雾。 */
        FIRE_SOURCE,
        /** 其他/未分类。 */
        UNKNOWN
    }

    /** 纬度（WGS84，度）。 */
    public final double lat;
    /** 经度（WGS84，度）。 */
    public final double lon;
    /** 海拔（米）。 */
    public final double alt;
    /** 目标类型。 */
    public final TargetType targetType;
    /** 识别置信度（0.0~1.0）。 */
    public final double confidence;
    /** 来源无人机系统 ID（MAVLink sysid）。 */
    public final int sourceSysid;

    /**
     * 构造地理目标。
     *
     * @param lat         纬度（WGS84，度）
     * @param lon         经度（WGS84，度）
     * @param alt         海拔（米）
     * @param targetType  目标类型
     * @param confidence  识别置信度（0.0~1.0）
     * @param sourceSysid 来源无人机系统 ID
     */
    public GeoTarget(double lat, double lon, double alt, TargetType targetType,
                     double confidence, int sourceSysid) {
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.targetType = targetType == null ? TargetType.UNKNOWN : targetType;
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.sourceSysid = sourceSysid;
    }

    /**
     * 判断置信度是否达到联动触发阈值。
     * <p>
     * 默认阈值为 0.6，低于此值的目标不触发 PTZ 联动，避免误报。
     *
     * @return true 若 confidence >= 0.6
     */
    public boolean isReliable() {
        return confidence >= 0.6;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GeoTarget)) return false;
        GeoTarget that = (GeoTarget) o;
        return Double.compare(that.lat, lat) == 0
                && Double.compare(that.lon, lon) == 0
                && Double.compare(that.alt, alt) == 0
                && sourceSysid == that.sourceSysid
                && targetType == that.targetType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(lat, lon, alt, targetType, sourceSysid);
    }

    @Override
    public String toString() {
        return "GeoTarget{lat=" + lat
                + ", lon=" + lon
                + ", alt=" + alt
                + ", type=" + targetType
                + ", confidence=" + confidence
                + ", sysid=" + sourceSysid + "}";
    }
}