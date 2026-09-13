package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** COMMAND_LONG (msgId=76, LEN=33, CRC=152)。GCS→飞控：长命令（ARM/起飞/模式等）。 */
public final class CommandLong extends MavlinkMessage {

    public static final int ID = 76;
    public static final int LEN = 33;

    public final int targetSystem;
    public final int targetComponent;
    public final int command;      // MAV_CMD
    public final int confirmation;
    public final float param1;
    public final float param2;
    public final float param3;
    public final float param4;
    public final float param5;
    public final float param6;
    public final float param7;

    public CommandLong(int targetSystem, int targetComponent, int command, int confirmation,
                       float param1, float param2, float param3, float param4,
                       float param5, float param6, float param7) {
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
        this.command = command;
        this.confirmation = confirmation;
        this.param1 = param1;
        this.param2 = param2;
        this.param3 = param3;
        this.param4 = param4;
        this.param5 = param5;
        this.param6 = param6;
        this.param7 = param7;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, param1);
        PayloadCodec.putF32(buf, 4, param2);
        PayloadCodec.putF32(buf, 8, param3);
        PayloadCodec.putF32(buf, 12, param4);
        PayloadCodec.putF32(buf, 16, param5);
        PayloadCodec.putF32(buf, 20, param6);
        PayloadCodec.putF32(buf, 24, param7);
        PayloadCodec.putU16(buf, 28, command);
        PayloadCodec.putU8(buf, 30, targetSystem);
        PayloadCodec.putU8(buf, 31, targetComponent);
        PayloadCodec.putU8(buf, 32, confirmation);
        return buf;
    }

    public static CommandLong decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new CommandLong(
                PayloadCodec.u8(b, 30),
                PayloadCodec.u8(b, 31),
                PayloadCodec.u16(b, 28),
                PayloadCodec.u8(b, 32),
                PayloadCodec.f32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.f32(b, 12),
                PayloadCodec.f32(b, 16),
                PayloadCodec.f32(b, 20),
                PayloadCodec.f32(b, 24));
    }

    @Override
    public String toString() {
        return "CommandLong{cmd=" + command + ", p1=" + param1 + ", p2=" + param2
                + ", p7=" + param7 + "}";
    }
}
