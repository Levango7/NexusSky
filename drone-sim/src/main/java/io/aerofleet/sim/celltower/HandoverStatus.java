package io.aerofleet.sim.celltower;

/**
 * 漫游切换状态枚举（M6 移动基站载荷抽象，FR-HO-02）。
 */
public enum HandoverStatus {
    /** 已发起，等待目标机确认。 */
    INITIATED,
    /** 目标机确认注册成功，源机已释放。 */
    COMPLETED,
    /** 目标机拒绝或 mesh 不可达，回滚至源机。 */
    ROLLED_BACK,
    /** 切换超时（>500ms 未确认），回滚至源机。 */
    TIMEOUT
}