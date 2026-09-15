package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * IMU_DATA (msgId=441, LEN=41) —— NexusSky M4 硬件抽象自定义扩展消息。
 * 承载 IMU 原始数据（加速度三轴/角速度三轴/磁场三轴/温度/sysid），由 drone-sim
 * {@code SimulatedImuSource} 产出并上报至 cloud-backend（FR-22）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段    类型   单位/精度
 * 0    accelX  f32   加速度 X（m/s²）
 * 4    accelY  f32   加速度 Y（m/s²）
 * 8    accelZ  f32   加速度 Z（m/s²）
 * 12   gyroX   f32   角速度 X（rad/s）
 * 16   gyroY   f32   角速度 Y（rad/s）
 * 20   gyroZ   f32   角速度 Z（rad/s）
 * 24   magX    f32   磁场 X（μT）
 * 28   magY    f32   磁场 Y（μT）
 * 32   magZ    f32   磁场 Z（μT）
 * 36   tempC   f32   温度（℃）
 * 40   sysid   u8    源飞机 sysid
 * </pre>
 * CRC_EXTRA = 215（M4 自定义扩展）。
 */
public final class ImuDataMsg extends MavlinkMessage {

    public static final int ID = 441;
    public static final int LEN = 41;
    public static final int CRC_EXTRA = 215;

    public final float accelX;   // m/s²
    public final float accelY;
    public final float accelZ;
    public final float gyroX;    // rad/s
    public final float gyroY;
    public final float gyroZ;
    public final float magX;     // μT
    public final float magY;
    public final float magZ;
    public final float tempC;    // ℃
    public final int sysid;

    public ImuDataMsg(float accelX, float accelY, float accelZ,
                      float gyroX, float gyroY, float gyroZ,
                      float magX, float magY, float magZ,
                      float tempC, int sysid) {
        this.accelX = accelX;
        this.accelY = accelY;
        this.accelZ = accelZ;
        this.gyroX = gyroX;
        this.gyroY = gyroY;
        this.gyroZ = gyroZ;
        this.magX = magX;
        this.magY = magY;
        this.magZ = magZ;
        this.tempC = tempC;
        this.sysid = sysid;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, accelX);
        PayloadCodec.putF32(buf, 4, accelY);
        PayloadCodec.putF32(buf, 8, accelZ);
        PayloadCodec.putF32(buf, 12, gyroX);
        PayloadCodec.putF32(buf, 16, gyroY);
        PayloadCodec.putF32(buf, 20, gyroZ);
        PayloadCodec.putF32(buf, 24, magX);
        PayloadCodec.putF32(buf, 28, magY);
        PayloadCodec.putF32(buf, 32, magZ);
        PayloadCodec.putF32(buf, 36, tempC);
        PayloadCodec.putU8(buf, 40, sysid);
        return buf;
    }

    public static ImuDataMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new ImuDataMsg(
                PayloadCodec.f32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.f32(b, 12),
                PayloadCodec.f32(b, 16),
                PayloadCodec.f32(b, 20),
                PayloadCodec.f32(b, 24),
                PayloadCodec.f32(b, 28),
                PayloadCodec.f32(b, 32),
                PayloadCodec.f32(b, 36),
                len > 40 ? PayloadCodec.u8(b, 40) : 0);
    }
}