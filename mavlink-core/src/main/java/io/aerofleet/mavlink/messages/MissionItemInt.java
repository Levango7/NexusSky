package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** MISSION_ITEM_INT (msgId=73, LEN=38, CRC=38)。任务航点（int 缩放坐标）。 */
public final class MissionItemInt extends MavlinkMessage {

    public static final int ID = 73;
    public static final int LEN = 38;

    public final int targetSystem;
    public final int targetComponent;
    public final int seq;
    public final int frame;
    public final int command;
    public final int current;
    public final int autocontinue;
    public final float param1;
    public final float param2;
    public final float param3;
    public final float param4;
    public final int x;           // GLOBAL: lat*1e7；LOCAL: x*1e4
    public final int y;           // GLOBAL: lon*1e7；LOCAL: y*1e4
    public final float z;         // GLOBAL: 高度 m
    public final int missionType;

    public MissionItemInt(int targetSystem, int targetComponent, int seq, int frame,
                          int command, int current, int autocontinue,
                          float param1, float param2, float param3, float param4,
                          int x, int y, float z, int missionType) {
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
        this.seq = seq;
        this.frame = frame;
        this.command = command;
        this.current = current;
        this.autocontinue = autocontinue;
        this.param1 = param1;
        this.param2 = param2;
        this.param3 = param3;
        this.param4 = param4;
        this.x = x;
        this.y = y;
        this.z = z;
        this.missionType = missionType;
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
        PayloadCodec.putI32(buf, 16, x);
        PayloadCodec.putI32(buf, 20, y);
        PayloadCodec.putF32(buf, 24, z);
        PayloadCodec.putU16(buf, 28, seq);
        PayloadCodec.putU16(buf, 30, command);
        PayloadCodec.putU8(buf, 32, targetSystem);
        PayloadCodec.putU8(buf, 33, targetComponent);
        PayloadCodec.putU8(buf, 34, frame);
        PayloadCodec.putU8(buf, 35, current);
        PayloadCodec.putU8(buf, 36, autocontinue);
        PayloadCodec.putU8(buf, 37, missionType);
        return buf;
    }

    public static MissionItemInt decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new MissionItemInt(
                PayloadCodec.u8(b, 32),
                PayloadCodec.u8(b, 33),
                PayloadCodec.u16(b, 28),
                PayloadCodec.u8(b, 34),
                PayloadCodec.u16(b, 30),
                PayloadCodec.u8(b, 35),
                len > 36 ? PayloadCodec.u8(b, 36) : 0,
                PayloadCodec.f32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.f32(b, 12),
                PayloadCodec.i32(b, 16),
                PayloadCodec.i32(b, 20),
                PayloadCodec.f32(b, 24),
                len > 37 ? PayloadCodec.u8(b, 37) : 0);
    }

    public double lat() {
        return x / 1e7;
    }

    public double lon() {
        return y / 1e7;
    }

    @Override
    public String toString() {
        return "MissionItemInt{seq=" + seq + ", cmd=" + command + ", lat=" + lat()
                + ", lon=" + lon() + ", alt=" + z + "}";
    }
}
