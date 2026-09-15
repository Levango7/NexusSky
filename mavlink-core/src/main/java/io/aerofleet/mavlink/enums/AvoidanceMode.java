package io.aerofleet.mavlink.enums;

/**
 * 避障响应策略枚举（M3 感知成像增强，FR-15/FR-16）。
 * <ul>
 *   <li>WAYPOINT_OFFSET：航点偏移（DO_REPOSITION 向障碍反方向偏移）</li>
 *   <li>SPEED_LIMIT：速度限制（DO_CHANGE_SPEED 降速）</li>
 *   <li>EMERGENCY_HOVER：紧急悬停（DO_SET_MODE → hover，CRITICAL 优先）</li>
 *   <li>DISABLED：禁用避障</li>
 * </ul>
 */
public enum AvoidanceMode {
    WAYPOINT_OFFSET,
    SPEED_LIMIT,
    EMERGENCY_HOVER,
    DISABLED
}