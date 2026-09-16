package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * EDGE_TASK_STATUS (msgId=473, LEN=13) —— NexusSky M12 边缘计算任务状态自定义扩展消息。
 * <p>
 * 承载边缘计算任务执行状态：任务 ID + 任务类型 + 状态 + 处理耗时 + 结果大小。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段              类型   单位/精度
 * 0    edgeTaskId        u32   边缘任务 ID
 * 4    processingTimeMs  u32   处理耗时（ms）
 * 8    resultSize        u16   结果数据大小（字节）
 * 10   sysId             u8    发送方系统 ID
 * 11   taskType          u8    0=视频分析, 1=传感器融合, 2=目标检测（{@link io.aerofleet.mavlink.enums.EdgeTaskType}）
 * 12   status            u8    0=待处理, 1=处理中, 2=完成, 3=失败
 * </pre>
 * CRC_EXTRA = 257（M12 自定义扩展）。
 */
public final class EdgeTaskStatusMsg extends MavlinkMessage {

    public static final int ID = 473;
    public static final int LEN = 13;
    public static final int CRC_EXTRA = 257;

    public final long edgeTaskId;          // 边缘任务 ID
    public final long processingTimeMs;    // ms
    public final int resultSize;           // 字节
    public final int sysId;                // 发送方系统 ID
    public final int taskType;             // 0=视频分析, 1=传感器融合, 2=目标检测
    public final int status;               // 0=待处理, 1=处理中, 2=完成, 3=失败

    public EdgeTaskStatusMsg(long edgeTaskId, long processingTimeMs, int resultSize,
                             int sysId, int taskType, int status) {
        this.edgeTaskId = edgeTaskId;
        this.processingTimeMs = processingTimeMs;
        this.resultSize = resultSize;
        this.sysId = sysId;
        this.taskType = taskType;
        this.status = status;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, edgeTaskId);
        PayloadCodec.putU32(buf, 4, processingTimeMs);
        PayloadCodec.putU16(buf, 8, resultSize);
        PayloadCodec.putU8(buf, 10, sysId);
        PayloadCodec.putU8(buf, 11, taskType);
        PayloadCodec.putU8(buf, 12, status);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static EdgeTaskStatusMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new EdgeTaskStatusMsg(
                PayloadCodec.u32(b, 0),
                len > 7 ? PayloadCodec.u32(b, 4) : 0,
                len > 9 ? PayloadCodec.u16(b, 8) : 0,
                len > 10 ? PayloadCodec.u8(b, 10) : 0,
                len > 11 ? PayloadCodec.u8(b, 11) : 0,
                len > 12 ? PayloadCodec.u8(b, 12) : 0);
    }

    @Override
    public String toString() {
        return "EdgeTaskStatusMsg{sysId=" + sysId
                + ", task=" + edgeTaskId + ", type=" + taskType
                + ", status=" + status
                + ", time=" + processingTimeMs + "ms"
                + ", resultSize=" + resultSize + "B}";
    }
}