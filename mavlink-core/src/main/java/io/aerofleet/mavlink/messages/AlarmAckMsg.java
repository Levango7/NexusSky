package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * ALARM_ACK (msgId=478, LEN=12) —— NexusSky M14 安防报警自定义扩展消息。
 * <p>
 * 无人机确认收到报警：承载报警事件 ID + 确认的无人机 sysid + 确认结果 +
 * 预计到达时间 + 时间戳。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段                 类型   单位/精度
 * 0    alarmId              u32    报警事件 ID
 * 4    timestamp            u32    时间戳（ms）
 * 8    estimatedArrivalSec  u16    预计到达时间（秒）
 * 10   droneSysid           u8     确认的无人机 sysid
 * 11   ackResult            u8     0=已收到, 1=已开始响应, 2=无法响应, 3=拒绝
 * </pre>
 * CRC_EXTRA = 262（M14 自定义扩展）。
 */
public final class AlarmAckMsg extends MavlinkMessage {

    public static final int ID = 478;
    public static final int LEN = 12;
    public static final int CRC_EXTRA = 262;

    public final long alarmId;              // 报警事件 ID
    public final long timestamp;            // ms
    public final int estimatedArrivalSec;   // 预计到达时间（秒）
    public final int droneSysid;            // 确认的无人机 sysid
    public final int ackResult;             // 0=已收到, 1=已开始响应, 2=无法响应, 3=拒绝

    public AlarmAckMsg(long alarmId, long timestamp, int estimatedArrivalSec,
                       int droneSysid, int ackResult) {
        this.alarmId = alarmId;
        this.timestamp = timestamp;
        this.estimatedArrivalSec = estimatedArrivalSec;
        this.droneSysid = droneSysid;
        this.ackResult = ackResult;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, alarmId);
        PayloadCodec.putU32(buf, 4, timestamp);
        PayloadCodec.putU16(buf, 8, estimatedArrivalSec);
        PayloadCodec.putU8(buf, 10, droneSysid);
        PayloadCodec.putU8(buf, 11, ackResult);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static AlarmAckMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new AlarmAckMsg(
                PayloadCodec.u32(b, 0),
                len > 7 ? PayloadCodec.u32(b, 4) : 0,
                len > 9 ? PayloadCodec.u16(b, 8) : 0,
                len > 10 ? PayloadCodec.u8(b, 10) : 0,
                len > 11 ? PayloadCodec.u8(b, 11) : 0);
    }

    @Override
    public String toString() {
        return "AlarmAckMsg{alarmId=" + alarmId + ", drone=" + droneSysid
                + ", result=" + ackResult
                + ", eta=" + estimatedArrivalSec + "s"
                + ", ts=" + timestamp + "}";
    }
}