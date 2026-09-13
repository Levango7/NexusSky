package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** GPS_RAW_INT (msgId=24, LEN=52, CRC=24)。GPS 修复状态与精度。 */
public final class GpsRawInt extends MavlinkMessage {

    public static final int ID = 24;
    public static final int LEN = 52;

    public final long timeUsec;
    public final int latE7;
    public final int lonE7;
    public final int altMm;
    public final int eph;          // HDOP*100，UINT16_MAX=未知
    public final int epv;          // VDOP*100
    public final int vel;          // cm/s
    public final int cog;          // cdeg
    public final int fixType;      // 0=无修复 3=3D
    public final int satellitesVisible;
    public final int yaw;           // cdeg，0=未知

    public GpsRawInt(long timeUsec, int latE7, int lonE7, int altMm, int eph, int epv,
                     int vel, int cog, int fixType, int satellitesVisible, int yaw) {
        this.timeUsec = timeUsec;
        this.latE7 = latE7;
        this.lonE7 = lonE7;
        this.altMm = altMm;
        this.eph = eph;
        this.epv = epv;
        this.vel = vel;
        this.cog = cog;
        this.fixType = fixType;
        this.satellitesVisible = satellitesVisible;
        this.yaw = yaw;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU64(buf, 0, timeUsec);
        PayloadCodec.putI32(buf, 8, latE7);
        PayloadCodec.putI32(buf, 12, lonE7);
        PayloadCodec.putI32(buf, 16, altMm);
        PayloadCodec.putU16(buf, 20, eph);
        PayloadCodec.putU16(buf, 22, epv);
        PayloadCodec.putU16(buf, 24, vel);
        PayloadCodec.putU16(buf, 26, cog);
        PayloadCodec.putU8(buf, 28, fixType);
        PayloadCodec.putU8(buf, 29, satellitesVisible);
        // 30..49: alt_ellipsoid 与精度字段（骨架置 0）
        PayloadCodec.putU16(buf, 50, yaw);
        return buf;
    }

    public static GpsRawInt decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new GpsRawInt(
                PayloadCodec.u64(b, 0),
                PayloadCodec.i32(b, 8),
                PayloadCodec.i32(b, 12),
                PayloadCodec.i32(b, 16),
                PayloadCodec.u16(b, 20),
                PayloadCodec.u16(b, 22),
                len > 24 ? PayloadCodec.u16(b, 24) : 65535,
                len > 26 ? PayloadCodec.u16(b, 26) : 65535,
                len > 28 ? PayloadCodec.u8(b, 28) : 0,
                len > 29 ? PayloadCodec.u8(b, 29) : 0,
                len > 50 ? PayloadCodec.u16(b, 50) : 0);
    }
}
