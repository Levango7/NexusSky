package io.aerofleet.cloud.health;

/**
 * 无人机关键部件类型枚举（P1-2 健康管理与预测性维护）。
 * <p>
 * 涵盖动力、传感、通信等核心子系统，用于健康评分与维护预测的部件维度划分。
 */
public enum ComponentType {

    /** 电池：电量、电压、循环次数。 */
    BATTERY,

    /** 电机：转速稳定性、电流负载。 */
    MOTOR,

    /** 振动：整机振动幅值（g）。 */
    VIBRATION,

    /** 温度：核心部件温度（°C）。 */
    TEMPERATURE,

    /** 通信：链路 RSSI、丢包率。 */
    COMMUNICATION,

    /** IMU：陀螺仪/加速度计漂移。 */
    IMU,

    /** GPS：卫星数、HDOP、定位精度。 */
    GPS
}