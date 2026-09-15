package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.TrackState;

/**
 * 雷达目标报告（M4 硬件抽象，FR-02/数据约束 6.2）。
 * <p>
 * 紧凑构造校验所有字段在物理范围内。
 *
 * @param targetId       目标 ID（≥0）
 * @param distance       距离（>0，米）
 * @param azimDeg        方位角（0-359°）
 * @param elevDeg        俯仰角（-90~90°）
 * @param radialVelocity 径向速度（m/s，正值远离）
 * @param heading        航向（0-359°）
 * @param rcs            雷达截面积（dBsm，-60~60）
 * @param trackState     跟踪状态
 * @param timestamp      探测时间戳（毫秒）
 */
public record RadarTargetReport(int targetId, double distance, double azimDeg, double elevDeg,
                                double radialVelocity, double heading, double rcs,
                                TrackState trackState, long timestamp) {
    public RadarTargetReport {
        if (distance <= 0) {
            throw new IllegalArgumentException("distance must be > 0, got " + distance);
        }
        if (azimDeg < 0 || azimDeg > 359) {
            throw new IllegalArgumentException("azimDeg must be 0-359, got " + azimDeg);
        }
        if (elevDeg < -90 || elevDeg > 90) {
            throw new IllegalArgumentException("elevDeg must be -90~90, got " + elevDeg);
        }
        if (heading < 0 || heading > 359) {
            throw new IllegalArgumentException("heading must be 0-359, got " + heading);
        }
        if (rcs < -60 || rcs > 60) {
            throw new IllegalArgumentException("rcs must be -60~60 dBsm, got " + rcs);
        }
        if (trackState == null) {
            throw new IllegalArgumentException("trackState must be non-null");
        }
    }
}