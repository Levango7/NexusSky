package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * RADAR_TARGET (msgId=438, LEN=28) —— NexusSky M4 硬件抽象自定义扩展消息。
 * 承载雷达目标报告（目标 ID/距离/方位/俯仰/径向速度/RCS/跟踪状态/sysid），由 drone-sim
 * {@code SimulatedRadar} 产出并上报至 cloud-backend（FR-19）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段            类型   单位/精度
 * 0    targetId       u16   目标 ID
 * 2    distance       f32   距离（米）
 * 6    azimDeg        f32   方位角（度，0-359）
 * 10   elevDeg        f32   俯仰角（度，-90~90）
 * 14   radialVelocity f32  径向速度（m/s，正值远离）
 * 18   rcs            f32   雷达截面积（dBsm）
 * 22   trackState     u8    TrackState.ordinal()（0-3）
 * 23   sysid          u8    源飞机 sysid
 * 24   timestamp      u32   时间戳（ms）
 * </pre>
 * CRC_EXTRA = 212（M4 自定义扩展）。
 */
public final class RadarTargetMsg extends MavlinkMessage {

    public static final int ID = 438;
    public static final int LEN = 28;
    public static final int CRC_EXTRA = 212;

    public final int targetId;
    public final float distance;       // 米
    public final float azimDeg;        // 度（0-359）
    public final float elevDeg;        // 度（-90~90）
    public final float radialVelocity; // m/s
    public final float rcs;            // dBsm
    public final int trackState;       // TrackState.ordinal()
    public final int sysid;
    public final long timestamp;       // ms

    public RadarTargetMsg(int targetId, float distance, float azimDeg, float elevDeg,
                          float radialVelocity, float rcs, int trackState,
                          int sysid, long timestamp) {
        this.targetId = targetId;
        this.distance = distance;
        this.azimDeg = azimDeg;
        this.elevDeg = elevDeg;
        this.radialVelocity = radialVelocity;
        this.rcs = rcs;
        this.trackState = trackState;
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
        PayloadCodec.putU16(buf, 0, targetId);
        PayloadCodec.putF32(buf, 2, distance);
        PayloadCodec.putF32(buf, 6, azimDeg);
        PayloadCodec.putF32(buf, 10, elevDeg);
        PayloadCodec.putF32(buf, 14, radialVelocity);
        PayloadCodec.putF32(buf, 18, rcs);
        PayloadCodec.putU8(buf, 22, trackState);
        PayloadCodec.putU8(buf, 23, sysid);
        PayloadCodec.putU32(buf, 24, timestamp);
        return buf;
    }

    public static RadarTargetMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new RadarTargetMsg(
                PayloadCodec.u16(b, 0),
                PayloadCodec.f32(b, 2),
                PayloadCodec.f32(b, 6),
                PayloadCodec.f32(b, 10),
                PayloadCodec.f32(b, 14),
                PayloadCodec.f32(b, 18),
                PayloadCodec.u8(b, 22),
                len > 23 ? PayloadCodec.u8(b, 23) : 0,
                len > 27 ? PayloadCodec.u32(b, 24) : 0L);
    }
}