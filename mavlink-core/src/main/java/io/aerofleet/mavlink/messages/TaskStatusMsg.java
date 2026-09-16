package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * TASK_STATUS (msgId=470, LEN=11) —— NexusSky M10 多机协同任务状态自定义扩展消息。
 * <p>
 * 承载单架无人机任务执行状态：任务 ID + 状态 + 进度百分比 + 时间戳。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段             类型   单位/精度
 * 0    taskId           u32   任务 ID
 * 4    timestamp        u32   时间戳（ms）
 * 8    sysId            u8    发送方系统 ID
 * 9    status           u8    0=已分配, 1=执行中, 2=已完成, 3=失败, 4=中止（{@link io.aerofleet.mavlink.enums.TaskStatusEnum}）
 * 10   progressPercent  u8    进度百分比（0-100）
 * </pre>
 * CRC_EXTRA = 254（M10 自定义扩展）。
 */
public final class TaskStatusMsg extends MavlinkMessage {

    public static final int ID = 470;
    public static final int LEN = 11;
    public static final int CRC_EXTRA = 254;

    public final long taskId;            // 任务 ID
    public final long timestamp;         // ms
    public final int sysId;              // 发送方系统 ID
    public final int status;             // 0=已分配, 1=执行中, 2=已完成, 3=失败, 4=中止
    public final int progressPercent;    // 0-100

    public TaskStatusMsg(long taskId, long timestamp, int sysId, int status, int progressPercent) {
        this.taskId = taskId;
        this.timestamp = timestamp;
        this.sysId = sysId;
        this.status = status;
        this.progressPercent = progressPercent;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, taskId);
        PayloadCodec.putU32(buf, 4, timestamp);
        PayloadCodec.putU8(buf, 8, sysId);
        PayloadCodec.putU8(buf, 9, status);
        PayloadCodec.putU8(buf, 10, progressPercent);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static TaskStatusMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new TaskStatusMsg(
                PayloadCodec.u32(b, 0),
                len > 7 ? PayloadCodec.u32(b, 4) : 0,
                len > 8 ? PayloadCodec.u8(b, 8) : 0,
                len > 9 ? PayloadCodec.u8(b, 9) : 0,
                len > 10 ? PayloadCodec.u8(b, 10) : 0);
    }

    @Override
    public String toString() {
        return "TaskStatusMsg{sysId=" + sysId
                + ", task=" + taskId + ", status=" + status
                + ", progress=" + progressPercent + "%}";
    }
}