package io.aerofleet.cloud.orch.enums;

/**
 * 触发器类型枚举
 */
public enum TriggerType {

    /** 应急启动触发 */
    EMERGENCY_START,

    /** 应急结束触发 */
    EMERGENCY_END,

    /** 步骤完成触发 */
    STEP_COMPLETE,

    /** 定时器触发 */
    TIMER
}