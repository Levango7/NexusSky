package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * OBSTACLE_REPORT (msgId=430, LEN=20) —— NexusSky M3 自定义扩展消息。
 * 承载障碍物检测报告（距离/方向/威胁等级/障碍类型/sysid），由 drone-sim 5Hz
 * {@code ObstacleDetector} 产出并上报至 cloud-backend（FR-19）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段        类型   单位/精度
 * 0    distance    f32    最近障碍距离（米）
 * 4    direction   f32    障碍方向（度，0-359，相对无人机航向）
 * 8    timestamp   u32    检测时间戳（ms，boot 毫秒）
 * 12   threat      u8     ThreatLevel.ordinal()（0-4）
 * 13   type        u8     ObstacleType.ordinal()（0-2）
 * 14   sysid       u8     源飞机 sysid
 * 15   reserved    u8     保留（0）
 * 16   reserved2   u16    保留（0）
 * </pre>
 * CRC_EXTRA = 201（M3 自定义扩展，按 MAVLink 扩展区间分配）。
 */
public final class ObstacleReportMsg extends MavlinkMessage {

    public static final int ID = 430;
    public static final int LEN = 20;
    public static final int CRC_EXTRA = 201;

    public final float distance;       // 米
    public final float direction;      // 度（0-359）
    public final long timestamp;       // ms
    public final int threat;           // ThreatLevel.ordinal()
    public final int type;             // ObstacleType.ordinal()
    public final int sysid;

    public ObstacleReportMsg(float distance, float direction, long timestamp,
                             int threat, int type, int sysid) {
        this.distance = distance;
        this.direction = direction;
        this.timestamp = timestamp;
        this.threat = threat;
        this.type = type;
        this.sysid = sysid;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, distance);
        PayloadCodec.putF32(buf, 4, direction);
        PayloadCodec.putU32(buf, 8, timestamp);
        PayloadCodec.putU8(buf, 12, threat);
        PayloadCodec.putU8(buf, 13, type);
        PayloadCodec.putU8(buf, 14, sysid);
        PayloadCodec.putU8(buf, 15, 0);
        PayloadCodec.putU16(buf, 16, 0);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0，与既有自定义消息一致）。 */
    public static ObstacleReportMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new ObstacleReportMsg(
                PayloadCodec.f32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.u32(b, 8),
                PayloadCodec.u8(b, 12),
                PayloadCodec.u8(b, 13),
                len > 14 ? PayloadCodec.u8(b, 14) : 0);
    }
}