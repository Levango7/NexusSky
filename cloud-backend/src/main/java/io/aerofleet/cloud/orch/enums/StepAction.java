package io.aerofleet.cloud.orch.enums;

/**
 * 步骤操作类型枚举
 */
public enum StepAction {

    /** 创建任务 */
    CREATE_TASK,

    /** 启动任务 */
    START_TASK,

    /** 中止任务 */
    ABORT_TASK,

    /** 创建并启动任务 */
    CREATE_AND_START_TASK,

    /** 创建编队 */
    CREATE_FORMATION,

    /** 解散编队 */
    DISSOLVE_FORMATION,

    /** 向编队下达指令 */
    COMMAND_FORMATION,

    /** 启动应急响应 */
    START_EMERGENCY,

    /** 中止应急响应 */
    ABORT_EMERGENCY
}