package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * LIDAR_DATA (msgId=440, LEN=20) —— NexusSky M4 硬件抽象自定义扩展消息。
 * 承载 LiDAR 点云数据摘要（最近距离/点数/密度/平均强度/sysid），由 drone-sim
 * {@code SimulatedLiDARSource} 产出并上报至 cloud-backend（FR-21）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段            类型   单位/精度
 * 0    nearestDistance f32   最近距离（米）
 * 4    pointCount      u32   点云点数
 * 8    density         f32   点云密度（0.0-1.0）
 * 12   avgIntensity    f32   平均强度（0.0-1.0）
 * 16   sysid           u8    源飞机 sysid
 * 17   reserved        u8    保留（0）
 * 18   reserved2       u16   保留（0）
 * </pre>
 * CRC_EXTRA = 214（M4 自定义扩展）。
 */
public final class LidarDataMsg extends MavlinkMessage {

    public static final int ID = 440;
    public static final int LEN = 20;
    public static final int CRC_EXTRA = 214;

    public final float nearestDistance; // 米
    public final long pointCount;
    public final float density;         // 0.0-1.0
    public final float avgIntensity;    // 0.0-1.0
    public final int sysid;

    public LidarDataMsg(float nearestDistance, long pointCount, float density,
                        float avgIntensity, int sysid) {
        this.nearestDistance = nearestDistance;
        this.pointCount = pointCount;
        this.density = density;
        this.avgIntensity = avgIntensity;
        this.sysid = sysid;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, nearestDistance);
        PayloadCodec.putU32(buf, 4, pointCount);
        PayloadCodec.putF32(buf, 8, density);
        PayloadCodec.putF32(buf, 12, avgIntensity);
        PayloadCodec.putU8(buf, 16, sysid);
        PayloadCodec.putU8(buf, 17, 0);
        PayloadCodec.putU16(buf, 18, 0);
        return buf;
    }

    public static LidarDataMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new LidarDataMsg(
                PayloadCodec.f32(b, 0),
                PayloadCodec.u32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.f32(b, 12),
                len > 16 ? PayloadCodec.u8(b, 16) : 0);
    }
}