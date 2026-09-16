package io.aerofleet.mavlink.enums;

/**
 * 冲突类型枚举（M10 多机协同冲突告警，msgId=469 ConflictAlertMsg）。
 * <p>
 * 取值为 ordinal()，编入 MAVLink u8 字段。
 * <ul>
 *   <li>AIRSPACE：空域冲突</li>
 *   <li>PATH：航径冲突</li>
 *   <li>COLLISION：碰撞冲突</li>
 * </ul>
 */
public enum ConflictType {
    AIRSPACE,
    PATH,
    COLLISION
}