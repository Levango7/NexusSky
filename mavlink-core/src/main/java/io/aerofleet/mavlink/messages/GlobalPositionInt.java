package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** GLOBAL_POSITION_INT (msgId=33, LEN=28, CRC=104)。WGS84 位置与速度（缩放整数）。 */
public final class GlobalPositionInt extends MavlinkMessage {

    public static final int ID = 33;
    public static final int LEN = 28;

    public final int timeBootMs;
    public final int latE7;        // deg * 1e7
    public final int lonE7;        // deg * 1e7
    public final int altMm;        // AMSL，mm
    public final int relativeAltMm;
    public final int vx;           // cm/s
    public final int vy;           // cm/s
    public final int vz;           // cm/s
    public final int hdg;          // deg*100，65535=未知

    public GlobalPositionInt(int timeBootMs, int latE7, int lonE7, int altMm,
                            int relativeAltMm, int vx, int vy, int vz, int hdg) {
        this.timeBootMs = timeBootMs;
        this.latE7 = latE7;
        this.lonE7 = lonE7;
        this.altMm = altMm;
        this.relativeAltMm = relativeAltMm;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.hdg = hdg;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, timeBootMs);
        PayloadCodec.putI32(buf, 4, latE7);
        PayloadCodec.putI32(buf, 8, lonE7);
        PayloadCodec.putI32(buf, 12, altMm);
        PayloadCodec.putI32(buf, 16, relativeAltMm);
        PayloadCodec.putI16(buf, 20, vx);
        PayloadCodec.putI16(buf, 22, vy);
        PayloadCodec.putI16(buf, 24, vz);
        PayloadCodec.putU16(buf, 26, hdg);
        return buf;
    }

    public static GlobalPositionInt decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new GlobalPositionInt(
                (int) PayloadCodec.u32(b, 0),
                PayloadCodec.i32(b, 4),
                PayloadCodec.i32(b, 8),
                PayloadCodec.i32(b, 12),
                PayloadCodec.i32(b, 16),
                PayloadCodec.i16(b, 20),
                PayloadCodec.i16(b, 22),
                PayloadCodec.i16(b, 24),
                PayloadCodec.u16(b, 26));
    }

    public double lat() {
        return latE7 / 1e7;
    }

    public double lon() {
        return lonE7 / 1e7;
    }

    public double relativeAltM() {
        return relativeAltMm / 1000.0;
    }

    @Override
    public String toString() {
        return "GlobalPositionInt{lat=" + lat() + ", lon=" + lon()
                + ", relAlt=" + relativeAltM() + "m}";
    }
}
