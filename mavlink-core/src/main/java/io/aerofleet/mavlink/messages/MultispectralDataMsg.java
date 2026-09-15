package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * MULTISPECTRAL_DATA (msgId=431, LEN=24) —— NexusSky M3 自定义扩展消息。
 * 承载多光谱 NDVI 计算结果摘要（均值/最小/最大/植被覆盖率 + sysid），由 drone-sim
 * {@code SimulatedMultispectralSource} 产出（FR-20）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段                  类型   单位/精度
 * 0    ndviMean             f32    NDVI 均值（[-1,1]）
 * 4    ndviMin              f32    NDVI 最小
 * 8    ndviMax              f32    NDVI 最大
 * 12   vegetationCoverage   f32    植被覆盖率（[0,1]）
 * 16   timestamp            u32    采集时间戳（ms）
 * 20   sysid                u8     源飞机 sysid
 * 21   reserved             u8     保留（0）
 * 22   reserved2            u16    保留（0）
 * </pre>
 * CRC_EXTRA = 202。
 */
public final class MultispectralDataMsg extends MavlinkMessage {

    public static final int ID = 431;
    public static final int LEN = 24;
    public static final int CRC_EXTRA = 202;

    public final float ndviMean;
    public final float ndviMin;
    public final float ndviMax;
    public final float vegetationCoverage;
    public final long timestamp;
    public final int sysid;

    public MultispectralDataMsg(float ndviMean, float ndviMin, float ndviMax,
                                float vegetationCoverage, long timestamp, int sysid) {
        this.ndviMean = ndviMean;
        this.ndviMin = ndviMin;
        this.ndviMax = ndviMax;
        this.vegetationCoverage = vegetationCoverage;
        this.timestamp = timestamp;
        this.sysid = sysid;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, ndviMean);
        PayloadCodec.putF32(buf, 4, ndviMin);
        PayloadCodec.putF32(buf, 8, ndviMax);
        PayloadCodec.putF32(buf, 12, vegetationCoverage);
        PayloadCodec.putU32(buf, 16, timestamp);
        PayloadCodec.putU8(buf, 20, sysid);
        PayloadCodec.putU8(buf, 21, 0);
        PayloadCodec.putU16(buf, 22, 0);
        return buf;
    }

    public static MultispectralDataMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new MultispectralDataMsg(
                PayloadCodec.f32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.f32(b, 12),
                PayloadCodec.u32(b, 16),
                len > 20 ? PayloadCodec.u8(b, 20) : 0);
    }
}