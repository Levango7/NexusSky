package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** STATUSTEXT (msgId=253, LEN=54, CRC=83)。飞控→GCS：状态/告警文本。 */
public final class Statustext extends MavlinkMessage {

    public static final int ID = 253;
    public static final int LEN = 54;

    public final int severity;   // MAV_SEVERITY
    public final String text;    // 最多 50 字节
    public final int id;
    public final int chunkSeq;

    public Statustext(int severity, String text, int id, int chunkSeq) {
        this.severity = severity;
        this.text = text;
        this.id = id;
        this.chunkSeq = chunkSeq;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, severity);
        PayloadCodec.putU16(buf, 51, id);
        PayloadCodec.putU8(buf, 53, chunkSeq);
        PayloadCodec.putChars(buf, 1, text, 50);
        return buf;
    }

    public static Statustext decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new Statustext(
                PayloadCodec.u8(b, 0),
                PayloadCodec.chars(b, 1, Math.min(50, len - 1)),
                len > 51 ? PayloadCodec.u16(b, 51) : 0,
                len > 53 ? PayloadCodec.u8(b, 53) : 0);
    }

    @Override
    public String toString() {
        return "Statustext{sev=" + severity + ", text=" + text + "}";
    }
}
