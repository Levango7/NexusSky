package io.aerofleet.cloud.mission.delivery;

/**
 * 配送站点状态枚举（FR-24）。
 * <p>
 * 状态推进：{@link #PENDING} → {@link #EN_ROUTE} → {@link #DROPPED}（投放成功）/ {@link #SKIPPED}（超时/失败）。
 */
public enum DeliverySiteState {
    PENDING,
    EN_ROUTE,
    DROPPED,
    SKIPPED
}
