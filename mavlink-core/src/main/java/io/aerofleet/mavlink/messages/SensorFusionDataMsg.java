package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * SENSOR_FUSION_DATA (msgId=474, LEN=24) —— NexusSky M12 多源传感器融合数据自定义扩展消息。
 * <p>
 * 承载融合后的位姿数据：融合经纬度/高度/航向/速度 + 精度 + 传感器位掩码。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段           类型   单位/精度
 * 0    fusedLat       i32   融合纬度（1E7 度）
 * 4    fusedLon       i32   融合经度（1E7 度）
 * 8    fusedAlt       i32   融合高度（mm，AMSL）
 * 12   fusedVelocity  f32   融合速度（m/s）
 * 16   accuracy       f32   融合精度（m，CEP 半径）
 * 20   fusedHeading   u16   融合航向（cdeg，0-36000 表示 0-360°）
 * 22   sysId          u8    发送方系统 ID
 * 23   sensorMask     u8    传感器位掩码：GPS=1, IMU=2, VISION=4, LIDAR=8
 * </pre>
 * CRC_EXTRA = 258（M12 自定义扩展）。
 */
public final class SensorFusionDataMsg extends MavlinkMessage {

    public static final int ID = 474;
    public static final int LEN = 24;
    public static final int CRC_EXTRA = 258;

    /** 传感器位掩码常量。 */
    public static final int MASK_GPS = 1;
    public static final int MASK_IMU = 2;
    public static final int MASK_VISION = 4;
    public static final int MASK_LIDAR = 8;

    public final int fusedLat;         // 1E7 度
    public final int fusedLon;         // 1E7 度
    public final int fusedAlt;         // mm
    public final float fusedVelocity;  // m/s
    public final float accuracy;       // m
    public final int fusedHeading;     // cdeg
    public final int sysId;            // 发送方系统 ID
    public final int sensorMask;       // GPS=1, IMU=2, VISION=4, LIDAR=8

    public SensorFusionDataMsg(int fusedLat, int fusedLon, int fusedAlt,
                               float fusedVelocity, float accuracy, int fusedHeading,
                               int sysId, int sensorMask) {
        this.fusedLat = fusedLat;
        this.fusedLon = fusedLon;
        this.fusedAlt = fusedAlt;
        this.fusedVelocity = fusedVelocity;
        this.accuracy = accuracy;
        this.fusedHeading = fusedHeading;
        this.sysId = sysId;
        this.sensorMask = sensorMask;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putI32(buf, 0, fusedLat);
        PayloadCodec.putI32(buf, 4, fusedLon);
        PayloadCodec.putI32(buf, 8, fusedAlt);
        PayloadCodec.putF32(buf, 12, fusedVelocity);
        PayloadCodec.putF32(buf, 16, accuracy);
        PayloadCodec.putU16(buf, 20, fusedHeading);
        PayloadCodec.putU8(buf, 22, sysId);
        PayloadCodec.putU8(buf, 23, sensorMask);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static SensorFusionDataMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new SensorFusionDataMsg(
                len > 3 ? PayloadCodec.i32(b, 0) : 0,
                len > 7 ? PayloadCodec.i32(b, 4) : 0,
                len > 11 ? PayloadCodec.i32(b, 8) : 0,
                len > 15 ? PayloadCodec.f32(b, 12) : 0f,
                len > 19 ? PayloadCodec.f32(b, 16) : 0f,
                len > 21 ? PayloadCodec.u16(b, 20) : 0,
                len > 22 ? PayloadCodec.u8(b, 22) : 0,
                len > 23 ? PayloadCodec.u8(b, 23) : 0);
    }

    @Override
    public String toString() {
        return "SensorFusionDataMsg{sysId=" + sysId
                + ", pos=(" + fusedLat + "," + fusedLon + "," + fusedAlt + "mm)"
                + ", vel=" + fusedVelocity + "m/s"
                + ", acc=" + accuracy + "m"
                + ", hdg=" + (fusedHeading / 100.0) + "deg"
                + ", mask=0x" + Integer.toHexString(sensorMask) + "}";
    }
}