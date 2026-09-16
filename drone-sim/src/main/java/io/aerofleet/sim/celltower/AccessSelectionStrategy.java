package io.aerofleet.sim.celltower;

/**
 * 接入选择策略枚举（M6 移动基站载荷抽象，FR-TERM-03）。
 * <p>
 * 终端在有多个可达基站时选择接入目标的决策规则。
 */
public enum AccessSelectionStrategy {
    /** 最强信号（默认）。 */
    STRONGEST_SIGNAL,
    /** 最近距离。 */
    NEAREST_DISTANCE,
    /** 最低负载。 */
    LOWEST_LOAD
}