package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * THERMAL_DATA (msgId=432, LEN=24) —— NexusSky M3 自定义扩展消息。
 * 承载热成像温度场分析摘要（均值/最小/最高/标准差/热点数 + sysid），由 drone-sim
 * {@code SimulatedThermalSource} 产出（FR-21）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段          类型   单位/精度
 * 0    tempMean     f32    温度均值（℃）
 * 4    tempMin      f32    温度最小（℃）
 * 8    tempMax      f32    温度最高（℃）
 * 12   tempStdDev   f32    温度标准差（℃）
 * 16   timestamp    u32    采集时间戳（ms）
 * 20   hotspotCount u8     热点数量
 * 21   sysid        u8     源飞机 sysid
 * 22   reserved     u16    保留（0）
 * </pre>
 * CRC_EXTRA = 203。
 */
public final class ThermalDataMsg extends MavlinkMessage {

    public static final int ID = 432;
    public static final int LEN = 24;
    public static final int CRC_EXTRA = 203;

    public final float tempMean;
    public final float tempMin;
    public final float tempMax;
    public final float tempStdDev;
    public final long timestamp;
    public final int hotspotCount;
    public final int sysid;

    public ThermalDataMsg(float tempMean, float tempMin, float tempMax,
                          float tempStdDev, long timestamp,
                          int hotspotCount, int sysid) {
        this.tempMean = tempMean;
        this.tempMin = tempMin;
        this.tempMax = tempMax;
        this.tempStdDev = tempStdDev;
        this.timestamp = timestamp;
        this.hotspotCount = hotspotCount;
        this.sysid = sysid;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, tempMean);
        PayloadCodec.putF32(buf, 4, tempMin);
        PayloadCodec.putF32(buf, 8, tempMax);
        PayloadCodec.putF32(buf, 12, tempStdDev);
        PayloadCodec.putU32(buf, 16, timestamp);
        PayloadCodec.putU8(buf, 20, hotspotCount);
        PayloadCodec.putU8(buf, 21, sysid);
        PayloadCodec.putU16(buf, 22, 0);
        return buf;
    }

    public static ThermalDataMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new ThermalDataMsg(
                PayloadCodec.f32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.f32(b, 12),
                PayloadCodec.u32(b, 16),
                len > 20 ? PayloadCodec.u8(b, 20) : 0,
                len > 21 ? PayloadCodec.u8(b, 21) : 0);
    }
}