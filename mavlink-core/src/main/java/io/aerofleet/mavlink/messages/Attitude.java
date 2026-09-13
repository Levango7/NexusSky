package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** ATTITUDE (msgId=30, LEN=28, CRC=39)。欧拉姿态与角速度。 */
public final class Attitude extends MavlinkMessage {

    public static final int ID = 30;
    public static final int LEN = 28;

    public final int timeBootMs;
    public final float roll;
    public final float pitch;
    public final float yaw;
    public final float rollSpeed;
    public final float pitchSpeed;
    public final float yawSpeed;

    public Attitude(int timeBootMs, float roll, float pitch, float yaw,
                    float rollSpeed, float pitchSpeed, float yawSpeed) {
        this.timeBootMs = timeBootMs;
        this.roll = roll;
        this.pitch = pitch;
        this.yaw = yaw;
        this.rollSpeed = rollSpeed;
        this.pitchSpeed = pitchSpeed;
        this.yawSpeed = yawSpeed;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, timeBootMs);
        PayloadCodec.putF32(buf, 4, roll);
        PayloadCodec.putF32(buf, 8, pitch);
        PayloadCodec.putF32(buf, 12, yaw);
        PayloadCodec.putF32(buf, 16, rollSpeed);
        PayloadCodec.putF32(buf, 20, pitchSpeed);
        PayloadCodec.putF32(buf, 24, yawSpeed);
        return buf;
    }

    public static Attitude decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new Attitude(
                (int) PayloadCodec.u32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.f32(b, 12),
                PayloadCodec.f32(b, 16),
                PayloadCodec.f32(b, 20),
                PayloadCodec.f32(b, 24));
    }

    @Override
    public String toString() {
        return "Attitude{roll=" + Math.toDegrees(roll) + ", pitch=" + Math.toDegrees(pitch)
                + ", yaw=" + Math.toDegrees(yaw) + "}";
    }
}
