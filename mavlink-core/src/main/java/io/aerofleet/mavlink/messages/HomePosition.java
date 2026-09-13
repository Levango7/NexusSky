package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** HOME_POSITION (msgId=242, LEN=60, CRC=104)。返航点坐标。 */
public final class HomePosition extends MavlinkMessage {

    public static final int ID = 242;
    public static final int LEN = 60;

    public final int latitudeE7;
    public final int longitudeE7;
    public final int altitudeMm;      // AMSL mm

    public HomePosition(int latitudeE7, int longitudeE7, int altitudeMm) {
        this.latitudeE7 = latitudeE7;
        this.longitudeE7 = longitudeE7;
        this.altitudeMm = altitudeMm;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putI32(buf, 0, latitudeE7);
        PayloadCodec.putI32(buf, 4, longitudeE7);
        PayloadCodec.putI32(buf, 8, altitudeMm);
        PayloadCodec.putF32(buf, 12, 0);   // x
        PayloadCodec.putF32(buf, 16, 0);   // y
        PayloadCodec.putF32(buf, 20, 0);   // z
        // 24..39: 姿态四元数（置 0）
        PayloadCodec.putF32(buf, 40, 0);   // approach_x
        PayloadCodec.putF32(buf, 44, 0);   // approach_y
        PayloadCodec.putF32(buf, 48, 0);   // approach_z
        PayloadCodec.putU64(buf, 52, 0);   // time_usec
        return buf;
    }

    public static HomePosition decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new HomePosition(
                PayloadCodec.i32(b, 0),
                PayloadCodec.i32(b, 4),
                PayloadCodec.i32(b, 8));
    }
}
