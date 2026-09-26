package io.aerofleet.cloud.orch.enums;

/**
 * 任务步骤状态枚举
 */
public enum StepStatus {

    /** 待执行状态，步骤尚未开始 */
    PENDING,

    /** 分配中状态，正在为步骤分配资源 */
    ALLOCATING,

    /** 执行中状态，步骤正在运行 */
    EXECUTING,

    /** 已完成状态，步骤执行成功 */
    DONE,

    /** 失败状态，步骤执行出错 */
    FAILED,

    /** 已跳过状态，步骤因条件不满足而被跳过 */
    SKIPPED
}