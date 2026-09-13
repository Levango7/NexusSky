package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** SYSTEM_TIME (msgId=2, LEN=12, CRC=137)。Unix 时间与开机毫秒。 */
public final class SystemTimeMsg extends MavlinkMessage {

    public static final int ID = 2;
    public static final int LEN = 12;

    public final long timeUnixUsec;
    public final int timeBootMs;

    public SystemTimeMsg(long timeUnixUsec, int timeBootMs) {
        this.timeUnixUsec = timeUnixUsec;
        this.timeBootMs = timeBootMs;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU64(buf, 0, timeUnixUsec);
        PayloadCodec.putU32(buf, 8, timeBootMs);
        return buf;
    }

    public static SystemTimeMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new SystemTimeMsg(PayloadCodec.u64(b, 0), (int) PayloadCodec.u32(b, 8));
    }
}
