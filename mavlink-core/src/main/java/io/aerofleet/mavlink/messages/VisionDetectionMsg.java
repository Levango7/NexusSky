package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * VISION_DETECTION (msgId=434, LEN=20) —— NexusSky M3 自定义扩展消息。
 * 承载单个视觉检测结果（u/v/kind/confidence/trackId + sysid），由 drone-sim
 * {@code SimulatedVisionSource} 产出（FR-23）。一个帧内多个目标分多条消息上报。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段        类型   单位/精度
 * 0    u          f32    像素 u 坐标（0-based）
 * 4    v          f32    像素 v 坐标（0-based）
 * 8    confidence f32    置信度（[0,1]）
 * 12   kind       u8     目标类别枚举（0=vehicle,1=person,2=animal,...）
 * 13   trackId    u8     跟踪 ID（0-255，0xFF=未关联）
 * 14   sysid      u8     源飞机 sysid
 * 15   timestamp  u32    检测时间戳（ms）
 * </pre>
 * 注意：kind 在消息层用 u8 枚举码（节省字节），调用方需自行映射字符串↔枚举。
 * CRC_EXTRA = 205。
 */
public final class VisionDetectionMsg extends MavlinkMessage {

    public static final int ID = 434;
    public static final int LEN = 20;
    public static final int CRC_EXTRA = 205;
    /** trackId 字段未关联时的占位值（255）。 */
    public static final int TRACK_ID_NONE = 0xFF;

    public final float u;
    public final float v;
    public final float confidence;
    public final int kind;        // 枚举码
    public final int trackId;     // 0-255，TRACK_ID_NONE=未关联
    public final int sysid;
    public final long timestamp;

    public VisionDetectionMsg(float u, float v, float confidence,
                              int kind, int trackId, int sysid, long timestamp) {
        this.u = u;
        this.v = v;
        this.confidence = confidence;
        this.kind = kind;
        this.trackId = trackId;
        this.sysid = sysid;
        this.timestamp = timestamp;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, u);
        PayloadCodec.putF32(buf, 4, v);
        PayloadCodec.putF32(buf, 8, confidence);
        PayloadCodec.putU8(buf, 12, kind);
        PayloadCodec.putU8(buf, 13, trackId);
        PayloadCodec.putU8(buf, 14, sysid);
        PayloadCodec.putU32(buf, 15, timestamp);
        return buf;
    }

    public static VisionDetectionMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new VisionDetectionMsg(
                PayloadCodec.f32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.u8(b, 12),
                PayloadCodec.u8(b, 13),
                len > 14 ? PayloadCodec.u8(b, 14) : 0,
                len > 15 ? PayloadCodec.u32(b, 15) : 0);
    }
}