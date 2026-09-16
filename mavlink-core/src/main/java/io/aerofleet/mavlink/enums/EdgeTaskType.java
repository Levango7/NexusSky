package io.aerofleet.mavlink.enums;

/**
 * 边缘任务类型枚举（M12 边缘计算，msgId=473 EdgeTaskStatusMsg）。
 * <p>
 * 取值为 ordinal()，编入 MAVLink u8 字段。
 * <ul>
 *   <li>VIDEO_ANALYSIS：视频分析</li>
 *   <li>SENSOR_FUSION：传感器融合</li>
 *   <li>OBJECT_DETECT：目标检测</li>
 * </ul>
 */
public enum EdgeTaskType {
    VIDEO_ANALYSIS,
    SENSOR_FUSION,
    OBJECT_DETECT
}