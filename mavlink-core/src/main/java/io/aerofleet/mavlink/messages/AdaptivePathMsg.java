package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * ADAPTIVE_PATH (msgId=472, LEN=20) —— NexusSky M11 自适应航径调整自定义扩展消息。
 * <p>
 * 承载航径自适应调整：原始航点序号 + 新目标位置 + 调整原因 + 风速 + 风向。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段                 类型   单位/精度
 * 0    newLat               i32   新纬度（1E7 度）
 * 4    newLon               i32   新经度（1E7 度）
 * 8    windSpeed            f32   风速（m/s）
 * 12   originalWaypointSeq  u16   原始航点序号
 * 14   newAlt               i16   新高度（m，相对起降点）
 * 16   windDirection        u16   风向（cdeg，0-36000 表示 0-360°）
 * 18   sysId                u8    发送方系统 ID
 * 19   adjustmentReason     u8    0=风场, 1=障碍物, 2=地形, 3=电量（{@link io.aerofleet.mavlink.enums.AdjustmentReason}）
 * </pre>
 * CRC_EXTRA = 256（M11 自定义扩展）。
 */
public final class AdaptivePathMsg extends MavlinkMessage {

    public static final int ID = 472;
    public static final int LEN = 20;
    public static final int CRC_EXTRA = 256;

    public final int newLat;                // 1E7 度
    public final int newLon;                // 1E7 度
    public final float windSpeed;           // m/s
    public final int originalWaypointSeq;   // 原始航点序号
    public final int newAlt;                // m
    public final int windDirection;         // cdeg
    public final int sysId;                 // 发送方系统 ID
    public final int adjustmentReason;      // 0=风场, 1=障碍物, 2=地形, 3=电量

    public AdaptivePathMsg(int newLat, int newLon, float windSpeed,
                           int originalWaypointSeq, int newAlt, int windDirection,
                           int sysId, int adjustmentReason) {
        this.newLat = newLat;
        this.newLon = newLon;
        this.windSpeed = windSpeed;
        this.originalWaypointSeq = originalWaypointSeq;
        this.newAlt = newAlt;
        this.windDirection = windDirection;
        this.sysId = sysId;
        this.adjustmentReason = adjustmentReason;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putI32(buf, 0, newLat);
        PayloadCodec.putI32(buf, 4, newLon);
        PayloadCodec.putF32(buf, 8, windSpeed);
        PayloadCodec.putU16(buf, 12, originalWaypointSeq);
        PayloadCodec.putI16(buf, 14, newAlt);
        PayloadCodec.putU16(buf, 16, windDirection);
        PayloadCodec.putU8(buf, 18, sysId);
        PayloadCodec.putU8(buf, 19, adjustmentReason);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static AdaptivePathMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new AdaptivePathMsg(
                len > 3 ? PayloadCodec.i32(b, 0) : 0,
                len > 7 ? PayloadCodec.i32(b, 4) : 0,
                len > 11 ? PayloadCodec.f32(b, 8) : 0f,
                len > 13 ? PayloadCodec.u16(b, 12) : 0,
                len > 15 ? PayloadCodec.i16(b, 14) : 0,
                len > 17 ? PayloadCodec.u16(b, 16) : 0,
                len > 18 ? PayloadCodec.u8(b, 18) : 0,
                len > 19 ? PayloadCodec.u8(b, 19) : 0);
    }

    @Override
    public String toString() {
        return "AdaptivePathMsg{sysId=" + sysId
                + ", wp=" + originalWaypointSeq
                + ", newPos=(" + newLat + "," + newLon + "," + newAlt + "m)"
                + ", reason=" + adjustmentReason
                + ", wind=" + windSpeed + "m/s @" + (windDirection / 100.0) + "deg}";
    }
}