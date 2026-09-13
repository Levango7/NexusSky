package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** VFR_HUD (msgId=74, LEN=20, CRC=20)。飞行仪表：空速/地速/高度/爬升率/航向/油门。 */
public final class VfrHud extends MavlinkMessage {

    public static final int ID = 74;
    public static final int LEN = 20;

    public final float airspeed;
    public final float groundspeed;
    public final float alt;
    public final float climb;
    public final int heading;     // deg 0-359
    public final int throttle;    // %

    public VfrHud(float airspeed, float groundspeed, float alt, float climb,
                 int heading, int throttle) {
        this.airspeed = airspeed;
        this.groundspeed = groundspeed;
        this.alt = alt;
        this.climb = climb;
        this.heading = heading;
        this.throttle = throttle;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, airspeed);
        PayloadCodec.putF32(buf, 4, groundspeed);
        PayloadCodec.putF32(buf, 8, alt);
        PayloadCodec.putF32(buf, 12, climb);
        PayloadCodec.putI16(buf, 16, heading);
        PayloadCodec.putU16(buf, 18, throttle);
        return buf;
    }

    public static VfrHud decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new VfrHud(
                PayloadCodec.f32(b, 0),
                PayloadCodec.f32(b, 4),
                PayloadCodec.f32(b, 8),
                PayloadCodec.f32(b, 12),
                PayloadCodec.i16(b, 16),
                PayloadCodec.u16(b, 18));
    }
}
