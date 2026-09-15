package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * ROTOR_TELEMETRY (msgId=439, LEN=24) —— NexusSky M4 硬件抽象自定义扩展消息。
 * 承载旋翼气动遥测（旋翼序号/转速/推力/功耗/总推力/总功耗/sysid），由 drone-sim
 * {@code SimulatedRotorAerodynamics} 产出并上报至 cloud-backend（FR-20）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段         类型   单位/精度
 * 0    rotorIndex   u8    旋翼序号
 * 1    rpm          f32   转速（RPM）
 * 5    thrust       f32   推力（N）
 * 9    power        f32   功耗（W）
 * 13   totalThrust  f32   总推力（N）
 * 17   totalPower   f32   总功耗（W）
 * 21   sysid        u8    源飞机 sysid
 * 22   reserved     u16   保留（0）
 * </pre>
 * CRC_EXTRA = 213（M4 自定义扩展）。
 */
public final class RotorTelemetryMsg extends MavlinkMessage {

    public static final int ID = 439;
    public static final int LEN = 24;
    public static final int CRC_EXTRA = 213;

    public final int rotorIndex;
    public final float rpm;        // RPM
    public final float thrust;     // N
    public final float power;      // W
    public final float totalThrust; // N
    public final float totalPower;  // W
    public final int sysid;

    public RotorTelemetryMsg(int rotorIndex, float rpm, float thrust, float power,
                             float totalThrust, float totalPower, int sysid) {
        this.rotorIndex = rotorIndex;
        this.rpm = rpm;
        this.thrust = thrust;
        this.power = power;
        this.totalThrust = totalThrust;
        this.totalPower = totalPower;
        this.sysid = sysid;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, rotorIndex);
        PayloadCodec.putF32(buf, 1, rpm);
        PayloadCodec.putF32(buf, 5, thrust);
        PayloadCodec.putF32(buf, 9, power);
        PayloadCodec.putF32(buf, 13, totalThrust);
        PayloadCodec.putF32(buf, 17, totalPower);
        PayloadCodec.putU8(buf, 21, sysid);
        PayloadCodec.putU16(buf, 22, 0);
        return buf;
    }

    public static RotorTelemetryMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new RotorTelemetryMsg(
                PayloadCodec.u8(b, 0),
                PayloadCodec.f32(b, 1),
                PayloadCodec.f32(b, 5),
                PayloadCodec.f32(b, 9),
                PayloadCodec.f32(b, 13),
                PayloadCodec.f32(b, 17),
                len > 21 ? PayloadCodec.u8(b, 21) : 0);
    }
}