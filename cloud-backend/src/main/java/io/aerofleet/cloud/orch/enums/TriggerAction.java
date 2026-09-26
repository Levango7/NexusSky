package io.aerofleet.cloud.orch.enums;

/**
 * 触发动作枚举
 */
public enum TriggerAction {

    /** 暂停计划 */
    PAUSE_PLAN,

    /** 恢复计划 */
    RESUME_PLAN,

    /** 启动计划 */
    START_PLAN,

    /** 发送通知 */
    NOTIFY
}