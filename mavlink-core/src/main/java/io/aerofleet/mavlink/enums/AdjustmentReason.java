package io.aerofleet.mavlink.enums;

/**
 * 航径调整原因枚举（M11 自适应航径，msgId=472 AdaptivePathMsg）。
 * <p>
 * 取值为 ordinal()，编入 MAVLink u8 字段。
 * <ul>
 *   <li>WIND：风场影响</li>
 *   <li>OBSTACLE：障碍物规避</li>
 *   <li>TERRAIN：地形适配</li>
 *   <li>BATTERY：电量约束</li>
 * </ul>
 */
public enum AdjustmentReason {
    WIND,
    OBSTACLE,
    TERRAIN,
    BATTERY
}