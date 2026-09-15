package io.aerofleet.mavlink.enums;

/**
 * 雷达目标跟踪状态枚举（M4 硬件抽象，FR-02/FR-05）。
 * <ul>
 *   <li>{@link #DETECTED} 初次探测</li>
 *   <li>{@link #TRACKING} 稳定跟踪</li>
 *   <li>{@link #COASTING} 航迹外推，暂无探测</li>
 *   <li>{@link #LOST} 丢失</li>
 * </ul>
 */
public enum TrackState {
    DETECTED,
    TRACKING,
    COASTING,
    LOST
}