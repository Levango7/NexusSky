package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * DEPTH_DATA (msgId=433, LEN=20) —— NexusSky M3 自定义扩展消息。
 * 承载深度感知数据摘要（最近距离/方向/点云密度/点数 + sysid），由 drone-sim
 * {@code SimulatedDepthSource} 产出（FR-22）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段                 类型   单位/精度
 * 0    nearestDistance     f32    最近障碍距离（米）
 * 4    nearestDirection    f32    最近障碍方向（度，0-359）
 * 8    pointCloudDensity   f32    点云密度（点/m³）
 * 12   pointCount          u32    点云点数
 * 16   sysid               u8     源飞机 sysid
 * 17   reserved            u8     保留（0）
 * 18   reserved2           u16    保留（0）
 * </pre>
 * CRC_EXTRA = 204。
 */
public final class DepthDataMsg extends MavlinkMessage {

    public static final int ID = 433;
    public static final int LEN = 20;
    public static final int CRC_EXTRA = 204;

    public final float nearestDistance;
    public final float nearestDirection;
    public final float pointCloudDensity;
    public final long pointCount;
    public final int sysid;

    public DepthDataMsg(float nearestDistance, float nearestDirection,
                        float pointCloudDensity, long pointCount, int sysid) {
        this.nearestDistance = nearestDistance;
        this.nearestDirection = nearestDirection;
        this.pointCloudDensity = pointCloudDensity;
        this.pointCount = pointCount;
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
        PayloadCodec.putF32(buf, 4, nearestDirection);
        PayloadCodec.putF32(buf, 8, pointCloudDensity);
        PayloadCodec.putU32(buf, 12, pointCount);
        PayloadCodec.putU8(buf, 16, sysid);
        PayloadCodec.putU8(buf, 17, 0);
        PayloadCodec.putU16(buf, 18, 0);
        return buf;
    }

    public static DepthDataMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new DepthDataMsg(
                PayloadCodec.f32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.u32(b, 12),
                len > 16 ? PayloadCodec.u8(b, 16) : 0);
    }
}