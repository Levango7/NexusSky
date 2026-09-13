package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * MANUAL_CONTROL (msgId=69, LEN=26, CRC=243). GCS→飞控：虚拟摇杆轴值。
 * x/y: 侧倾/俯仰 [-1000,1000]；z: 油门 [0,1000]；r: 偏航 [-1000,1000]；
 * buttons: 16 位按钮掩码；targetSystem/targetComponent: 接收方。
 */
public final class ManualControl extends MavlinkMessage {

    public static final int ID = 69;
    public static final int LEN = 26;

    public final int x;
    public final int y;
    public final int z;
    public final int r;
    public final int buttons;
    public final int targetSystem;
    public final int targetComponent;

    public ManualControl(int x, int y, int z, int r, int buttons,
                         int targetSystem, int targetComponent) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.r = r;
        this.buttons = buttons;
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        putS16(buf, 0, x);
        putS16(buf, 2, y);
        PayloadCodec.putU16(buf, 4, z & 0xFFFF);
        putS16(buf, 6, r);
        PayloadCodec.putU16(buf, 8, buttons & 0xFFFF);
        PayloadCodec.putU8(buf, 10, targetSystem);
        PayloadCodec.putU8(buf, 11, targetComponent);
        return buf;
    }

    private static void putS16(byte[] buf, int off, int v) {
        PayloadCodec.putU16(buf, off, v & 0xFFFF);
    }

    public static ManualControl decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        // x/y/r are signed int16 (two's complement) on the wire; z (throttle)
        // is unsigned 0..1000.
        int x = PayloadCodec.u16(b, 0);
        if (x > 32767) {
            x -= 65536;
        }
        int y = PayloadCodec.u16(b, 2);
        if (y > 32767) {
            y -= 65536;
        }
        int z = PayloadCodec.u16(b, 4);
        int r = PayloadCodec.u16(b, 6);
        if (r > 32767) {
            r -= 65536;
        }
        return new ManualControl(
                x, y, z, r,
                PayloadCodec.u16(b, 8),
                f.getPayloadLength() > 10 ? PayloadCodec.u8(b, 10) : 0,
                f.getPayloadLength() > 11 ? PayloadCodec.u8(b, 11) : 0);
    }
}
