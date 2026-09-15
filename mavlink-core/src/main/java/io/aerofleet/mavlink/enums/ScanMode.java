package io.aerofleet.mavlink.enums;

/**
 * 雷达扫描模式枚举（M4 硬件抽象，FR-01/FR-04）。
 * <ul>
 *   <li>{@link #SECTOR_SCAN} 扇扫：在方位角范围内往返扫描</li>
 *   <li>{@link #STARE} 凝视：固定波束指向目标</li>
 *   <li>{@link #TRACK_WHILE_SCAN} 边扫边跟：扫描同时维持目标跟踪</li>
 * </ul>
 */
public enum ScanMode {
    SECTOR_SCAN,
    STARE,
    TRACK_WHILE_SCAN
}