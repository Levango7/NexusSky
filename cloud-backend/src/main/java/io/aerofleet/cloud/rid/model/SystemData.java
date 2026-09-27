package io.aerofleet.cloud.rid.model;

/**
 * 系统数据，对应 ASTM F3411 Remote ID System Message。
 * <p>
 * 包含操作者位置和飞行区域信息：
 * <ul>
 *   <li>{@code operatorLocationType} — 操作者位置类型（0=Takeoff, 1=Live GNSS, 2=Fixed）</li>
 *   <li>{@code operatorLatitude} — 操作者纬度（度）</li>
 *   <li>{@code operatorLongitude} — 操作者经度（度）</li>
 *   <li>{@code areaCount} — 区域内无人机数量</li>
 *   <li>{@code areaRadius} — 区域半径（m）</li>
 *   <li>{@code areaCeiling} — 区域上限高度（m）</li>
 *   <li>{@code areaFloor} — 区域下限高度（m）</li>
 * </ul>
 *
 * @param operatorLocationType 操作者位置类型
 * @param operatorLatitude     操作者纬度
 * @param operatorLongitude    操作者经度
 * @param areaCount            区域内无人机数量
 * @param areaRadius           区域半径（m）
 * @param areaCeiling          区域上限高度（m）
 * @param areaFloor            区域下限高度（m）
 */
public record SystemData(
        int operatorLocationType,
        double operatorLatitude,
        double operatorLongitude,
        int areaCount,
        int areaRadius,
        float areaCeiling,
        float areaFloor
) {
}