package io.aerofleet.mavlink.enums;

/**
 * 决策类型枚举（M11 自主决策，msgId=471 DecisionEventMsg）。
 * <p>
 * 取值为 ordinal()，编入 MAVLink u8 字段。
 * <ul>
 *   <li>RTL：返航（Return To Launch）</li>
 *   <li>AVOID：避障</li>
 *   <li>ADAPT_PATH：自适应航径调整</li>
 *   <li>EMERGENCY_LAND：紧急降落</li>
 * </ul>
 */
public enum DecisionType {
    RTL,
    AVOID,
    ADAPT_PATH,
    EMERGENCY_LAND
}