package io.aerofleet.cloud.orch.enums;

/**
 * 计划暂停原因枚举。
 *
 * 用于区分计划是因为应急、手动操作还是条件触发而暂停，
 * 以便在应急结束时只自动恢复因应急暂停的计划。
 */
public enum PauseReason {
    /** 因应急启动而暂停 */
    EMERGENCY,
    /** 因手动操作而暂停 */
    MANUAL,
    /** 因条件触发器而暂停 */
    CONDITION
}