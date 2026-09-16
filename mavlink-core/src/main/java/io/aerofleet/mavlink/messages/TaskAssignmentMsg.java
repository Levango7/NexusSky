package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * TASK_ASSIGNMENT (msgId=468, LEN=18) —— NexusSky M10 多机协同任务分配自定义扩展消息。
 * <p>
 * 承载单架无人机任务分配：任务 ID + 任务类型 + 优先级 + 目标位置 + 被分配系统 ID。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段            类型   单位/精度
 * 0    taskId          u32   任务 ID
 * 4    targetLat       i32   目标纬度（1E7 度）
 * 8    targetLon       i32   目标经度（1E7 度）
 * 12   targetAlt       i16   目标高度（m，相对起降点）
 * 14   sysId           u8    发送方系统 ID
 * 15   taskType        u8    0=测绘, 1=喷洒, 2=中继, 3=搜救（{@link io.aerofleet.mavlink.enums.TaskType}）
 * 16   priority        u8    优先级（1-4）
 * 17   assignedSysId   u8    被分配无人机系统 ID
 * </pre>
 * CRC_EXTRA = 252（M10 自定义扩展，避开 M0-M9 已占用的 233-251）。
 */
public final class TaskAssignmentMsg extends MavlinkMessage {

    public static final int ID = 468;
    public static final int LEN = 18;
    public static final int CRC_EXTRA = 252;

    public final long taskId;           // 任务 ID
    public final int targetLat;         // 1E7 度
    public final int targetLon;         // 1E7 度
    public final int targetAlt;         // m
    public final int sysId;             // 发送方系统 ID
    public final int taskType;          // 0=测绘, 1=喷洒, 2=中继, 3=搜救
    public final int priority;          // 1-4
    public final int assignedSysId;     // 被分配无人机系统 ID

    public TaskAssignmentMsg(long taskId, int targetLat, int targetLon, int targetAlt,
                             int sysId, int taskType, int priority, int assignedSysId) {
        this.taskId = taskId;
        this.targetLat = targetLat;
        this.targetLon = targetLon;
        this.targetAlt = targetAlt;
        this.sysId = sysId;
        this.taskType = taskType;
        this.priority = priority;
        this.assignedSysId = assignedSysId;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, taskId);
        PayloadCodec.putI32(buf, 4, targetLat);
        PayloadCodec.putI32(buf, 8, targetLon);
        PayloadCodec.putI16(buf, 12, targetAlt);
        PayloadCodec.putU8(buf, 14, sysId);
        PayloadCodec.putU8(buf, 15, taskType);
        PayloadCodec.putU8(buf, 16, priority);
        PayloadCodec.putU8(buf, 17, assignedSysId);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static TaskAssignmentMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new TaskAssignmentMsg(
                PayloadCodec.u32(b, 0),
                len > 7 ? PayloadCodec.i32(b, 4) : 0,
                len > 11 ? PayloadCodec.i32(b, 8) : 0,
                len > 13 ? PayloadCodec.i16(b, 12) : 0,
                len > 14 ? PayloadCodec.u8(b, 14) : 0,
                len > 15 ? PayloadCodec.u8(b, 15) : 0,
                len > 16 ? PayloadCodec.u8(b, 16) : 0,
                len > 17 ? PayloadCodec.u8(b, 17) : 0);
    }

    @Override
    public String toString() {
        return "TaskAssignmentMsg{taskId=" + taskId
                + ", target=(" + targetLat + "," + targetLon + "," + targetAlt + "m)"
                + ", sysId=" + sysId + ", taskType=" + taskType
                + ", pri=" + priority + ", assigned=" + assignedSysId + "}";
    }
}