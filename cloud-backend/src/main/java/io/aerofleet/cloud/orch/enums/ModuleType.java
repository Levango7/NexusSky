package io.aerofleet.cloud.orch.enums;

/**
 * 业务模块类型枚举
 */
public enum ModuleType {

    /** 编队模块，负责飞行编队的组建与管理 */
    FORMATION,

    /** 交付模块，负责物资或任务的交付 */
    DELIVERY,

    /** 映射模块，负责航线或区域的映射规划 */
    MAPPING,

    /** 展示模块，负责数据可视化与态势展示 */
    SHOW,

    /** 应急模块，负责应急响应与处置 */
    EMERGENCY
}