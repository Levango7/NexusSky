package io.aerofleet.cloud.regulator.model;

/**
 * 飞行状态枚举。
 * <p>
 * 表示无人机当前的飞行状态，用于遥测上报：
 * <ul>
 *   <li>{@link #AIRBORNE} — 空中飞行中</li>
 *   <li>{@link #GROUND}   — 地面停驻/滑行</li>
 * </ul>
 */
public enum FlightStatus {
    /** 空中飞行中 */
    AIRBORNE,
    /** 地面停驻/滑行 */
    GROUND
}