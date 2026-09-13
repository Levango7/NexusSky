package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** MISSION_REQUEST_INT (msgId=51, LEN=5, CRC=196)。飞控→GCS：请求第 seq 个航点。 */
public final class MissionRequestInt extends MavlinkMessage {

    public static final int ID = 51;
    public static final int LEN = 5;

    public final int seq;
    public final int targetSystem;
    public final int targetComponent;
    public final int missionType;

    public MissionRequestInt(int seq, int targetSystem, int targetComponent, int missionType) {
        this.seq = seq;
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
        this.missionType = missionType;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU16(buf, 0, seq);
        PayloadCodec.putU8(buf, 2, targetSystem);
        PayloadCodec.putU8(buf, 3, targetComponent);
        PayloadCodec.putU8(buf, 4, missionType);
        return buf;
    }

    public static MissionRequestInt decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new MissionRequestInt(
                PayloadCodec.u16(b, 0),
                PayloadCodec.u8(b, 2),
                PayloadCodec.u8(b, 3),
                f.getPayloadLength() > 4 ? PayloadCodec.u8(b, 4) : 0);
    }
}
