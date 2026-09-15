package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * RADAR_SCAN (msgId=437, LEN=20) —— NexusSky M4 硬件抽象自定义扩展消息。
 * 承载雷达扫描状态（扫描模式/波束方位/俯仰/扫描周期/已探测目标数/sysid），由 drone-sim
 * {@code SimulatedRadar} 产出并上报至 cloud-backend（FR-18）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段           类型   单位/精度
 * 0    mode           u8    ScanMode.ordinal()（0-2）
 * 1    beamAzim       f32   波束方位角（度）
 * 5    beamElev       f32   波束俯仰角（度）
 * 9    scanPeriodMs   u16   扫描周期（ms）
 * 11   targetCount    u8    已探测目标数
 * 12   sysid          u8    源飞机 sysid
 * 13   timestamp      u32   时间戳（ms）
 * 17   reserved       u8    保留（0）
 * 18   reserved2      u16   保留（0）
 * </pre>
 * CRC_EXTRA = 211（M4 自定义扩展）。
 */
public final class RadarScanMsg extends MavlinkMessage {

    public static final int ID = 437;
    public static final int LEN = 20;
    public static final int CRC_EXTRA = 211;

    public final int mode;            // ScanMode.ordinal()
    public final float beamAzim;      // 度
    public final float beamElev;      // 度
    public final int scanPeriodMs;    // ms
    public final int targetCount;
    public final int sysid;
    public final long timestamp;      // ms

    public RadarScanMsg(int mode, float beamAzim, float beamElev, int scanPeriodMs,
                        int targetCount, int sysid, long timestamp) {
        this.mode = mode;
        this.beamAzim = beamAzim;
        this.beamElev = beamElev;
        this.scanPeriodMs = scanPeriodMs;
        this.targetCount = targetCount;
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
        PayloadCodec.putU8(buf, 0, mode);
        PayloadCodec.putF32(buf, 1, beamAzim);
        PayloadCodec.putF32(buf, 5, beamElev);
        PayloadCodec.putU16(buf, 9, scanPeriodMs);
        PayloadCodec.putU8(buf, 11, targetCount);
        PayloadCodec.putU8(buf, 12, sysid);
        PayloadCodec.putU32(buf, 13, timestamp);
        PayloadCodec.putU8(buf, 17, 0);
        PayloadCodec.putU16(buf, 18, 0);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时A容忍（缺失字段填 0）。 */
    public static RadarScanMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new RadarScanMsg(
                PayloadCodec.u8(b, 0),
                PayloadCodec.f32(b, 1),
                PayloadCodec.f32(b, 5),
                PayloadCodec.u16(b, 9),
                PayloadCodec.u8(b, 11),
                len > 12 ? PayloadCodec.u8(b, 12) : 0,
                len > 17 ? PayloadCodec.u32(b, 13) : 0L);
    }
}