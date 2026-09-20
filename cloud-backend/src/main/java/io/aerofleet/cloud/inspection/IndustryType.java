package io.aerofleet.cloud.inspection;

/**
 * 巡检行业类型枚举。
 * <p>
 * 不同行业对应不同的巡检模板预设与异常检测策略：
 * <ul>
 *   <li>{@link #POWER_LINE} 电力线路 — 绝缘子破损检测</li>
 *   <li>{@link #OIL_GAS_PIPE} 油气管道 — 管道泄漏检测</li>
 *   <li>{@link #RAILWAY} 铁路沿线 — 轨道裂缝检测</li>
 *   <li>{@link #SOLAR_FARM} 光伏电站 — 面板裂纹检测</li>
 *   <li>{@link #WIND_FARM} 风力电场 — 叶片损伤检测</li>
 *   <li>{@link #BRIDGE} 桥梁结构 — 结构锈蚀检测</li>
 * </ul>
 */
public enum IndustryType {
    /** 电力线路巡检。 */
    POWER_LINE,
    /** 油气管道巡检。 */
    OIL_GAS_PIPE,
    /** 铁路沿线巡检。 */
    RAILWAY,
    /** 光伏电站巡检。 */
    SOLAR_FARM,
    /** 风力电场巡检。 */
    WIND_FARM,
    /** 桥梁结构巡检。 */
    BRIDGE
}