package io.aerofleet.cloud.mission;

/**
 * 喷洒段 record（FR-13）。
 * <p>
 * 不可变值对象，描述航线上相邻两航点间的一段喷洒：
 * <ul>
 *   <li>{@code index}：段序号（0-based）</li>
 *   <li>{@code startLat/startLon}：起点坐标</li>
 *   <li>{@code endLat/endLon}：终点坐标</li>
 *   <li>{@code segmentLengthM}：段长 m（Haversine 公式计算）</li>
 *   <li>{@code targetRate}：目标流量 mL/s</li>
 * </ul>
 *
 * @param index           段序号
 * @param startLat        起点纬度
 * @param startLon        起点经度
 * @param endLat          终点纬度
 * @param endLon          终点经度
 * @param segmentLengthM  段长 m
 * @param targetRate      目标流量 mL/s
 */
public record SpraySegment(
        int index,
        double startLat, double startLon,
        double endLat, double endLon,
        double segmentLengthM,
        double targetRate
) {
}