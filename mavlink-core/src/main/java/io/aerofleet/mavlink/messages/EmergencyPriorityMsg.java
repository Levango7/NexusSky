package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * EMERGENCY_PRIORITY (msgId=467, LEN=50) —— NexusSky M9 应急任务编排自定义扩展消息。
 * <p>
 * 承载优先级调度事件：planId + taskId + 优先级 + 动作 + 被抢占任务 ID + 原因 + 时间戳。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型     单位/精度
 * 0    planId            u32     编排计划 ID
 * 4    taskId            u32     任务 ID
 * 8    priority          u8      1=搜救, 2=指挥, 3=测绘, 4=常规
 * 9    action            u8      0=提交, 1=调整, 2=抢占, 3=完成, 4=取消
 * 10   preemptedTaskId   u32     被抢占的任务 ID（0=无）
 * 14   reason            char[32] 原因描述（UTF-8，0 填充）
 * 46   timestamp         u32     时间戳（ms）
 * </pre>
 * CRC_EXTRA = 251（M9 自定义扩展）。
 */
public final class EmergencyPriorityMsg extends MavlinkMessage {

    public static final int ID = 467;
    public static final int LEN = 50;
    public static final int CRC_EXTRA = 251;
    private static final int REASON_LEN = 32;

    public final long planId;            // 编排计划 ID
    public final long taskId;            // 任务 ID
    public final int priority;           // 1=搜救, 2=指挥, 3=测绘, 4=常规
    public final int action;             // 0=提交, 1=调整, 2=抢占, 3=完成, 4=取消
    public final long preemptedTaskId;   // 被抢占的任务 ID（0=无）
    public final String reason;          // 原因描述
    public final long timestamp;         // ms

    public EmergencyPriorityMsg(long planId, long taskId, int priority, int action,
                                long preemptedTaskId, String reason, long timestamp) {
        this.planId = planId;
        this.taskId = taskId;
        this.priority = priority;
        this.action = action;
        this.preemptedTaskId = preemptedTaskId;
        this.reason = reason;
        this.timestamp = timestamp;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, planId);
        PayloadCodec.putU32(buf, 4, taskId);
        PayloadCodec.putU8(buf, 8, priority);
        PayloadCodec.putU8(buf, 9, action);
        PayloadCodec.putU32(buf, 10, preemptedTaskId);
        PayloadCodec.putChars(buf, 14, reason, REASON_LEN);
        PayloadCodec.putU32(buf, 46, timestamp);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static EmergencyPriorityMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new EmergencyPriorityMsg(
                PayloadCodec.u32(b, 0),
                len > 7 ? PayloadCodec.u32(b, 4) : 0,
                len > 8 ? PayloadCodec.u8(b, 8) : 0,
                len > 9 ? PayloadCodec.u8(b, 9) : 0,
                len > 13 ? PayloadCodec.u32(b, 10) : 0,
                len > 14 ? PayloadCodec.chars(b, 14, REASON_LEN) : "",
                len > 49 ? PayloadCodec.u32(b, 46) : 0);
    }

    @Override
    public String toString() {
        return "EmergencyPriorityMsg{planId=" + planId
                + ", task=" + taskId
                + ", pri=" + priority + ", action=" + action
                + ", preempted=" + preemptedTaskId
                + ", reason='" + reason + "'}";
    }
}