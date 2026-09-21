package io.aerofleet.cloud.orch.enums;

/**
 * 编排计划状态枚举
 */
public enum PlanStatus {

    /** 草稿状态，计划已创建但尚未启动 */
    DRAFT,

    /** 运行中状态，计划正在执行 */
    RUNNING,

    /** 暂停状态，计划被手动或触发器暂停 */
    PAUSED,

    /** 已完成状态，计划中所有步骤均已成功执行 */
    COMPLETED,

    /** 已中止状态，计划被异常终止 */
    ABORTED
}