package io.aerofleet.mavlink.enums;

/**
 * 任务状态枚举（M10 多机协同任务状态，msgId=470 TaskStatusMsg）。
 * <p>
 * 取值为 ordinal()，编入 MAVLink u8 字段。
 * <ul>
 *   <li>ASSIGNED：已分配</li>
 *   <li>IN_PROGRESS：执行中</li>
 *   <li>COMPLETED：已完成</li>
 *   <li>FAILED：失败</li>
 *   <li>ABORTED：中止</li>
 * </ul>
 */
public enum TaskStatusEnum {
    ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    ABORTED
}