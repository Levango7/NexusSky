package io.aerofleet.mavlink.enums;

/**
 * 障碍物类型枚举（M3 感知成像增强，FR-13/FR-19）。
 * <ul>
 *   <li>STATIC：静态障碍物（建筑物/树木/地形）</li>
 *   <li>DYNAMIC：动态障碍物（其他无人机/飞鸟/车辆）</li>
 *   <li>UNKNOWN：未知类型（深度数据无分类信息）</li>
 * </ul>
 */
public enum ObstacleType {
    STATIC,
    DYNAMIC,
    UNKNOWN
}