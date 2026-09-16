package io.aerofleet.mavlink.enums;

/**
 * 任务类型枚举（M10 多机协同任务分配，msgId=468 TaskAssignmentMsg）。
 * <p>
 * 取值为 ordinal()，编入 MAVLink u8 字段。
 * <ul>
 *   <li>SURVEY：测绘巡查</li>
 *   <li>SPRAY：喷洒作业</li>
 *   <li>RELAY：中继通信</li>
 *   <li>RESCUE：搜救应急</li>
 * </ul>
 */
public enum TaskType {
    SURVEY,
    SPRAY,
    RELAY,
    RESCUE
}