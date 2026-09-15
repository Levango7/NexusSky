package io.aerofleet.mavlink.enums;

/**
 * 障碍物威胁等级枚举（M3 感知成像增强，FR-13）。
 * <p>
 * 用于 {@link io.aerofleet.mavlink.messages.ObstacleReportMsg} 的 threat 字段，
 * 取值为 ordinal()，编入 MAVLink u8 字段。
 * <ul>
 *   <li>NONE：无威胁（距离 ≥ 4×安全距离）</li>
 *   <li>LOW：远距（2×安全距离 ≤ 距离 &lt; 4×安全距离）</li>
 *   <li>MEDIUM：中距（安全距离 ≤ 距离 &lt; 2×安全距离）</li>
 *   <li>HIGH：近距需避障（紧急悬停阈值 ≤ 距离 &lt; 安全距离）</li>
 *   <li>CRITICAL：紧急悬停（距离 &lt; 紧急悬停阈值）</li>
 * </ul>
 */
public enum ThreatLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}