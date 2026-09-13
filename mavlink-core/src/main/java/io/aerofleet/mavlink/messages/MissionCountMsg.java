package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** MISSION_COUNT (msgId=44, LEN=9, CRC=221)。GCS→飞控：宣布任务航点总数。 */
public final class MissionCountMsg extends MavlinkMessage {

    public static final int ID = 44;
    public static final int LEN = 9;

    public final int count;
    public final int targetSystem;
    public final int targetComponent;
    public final int missionType;
    public final long opaqueId;

    public MissionCountMsg(int count, int targetSystem, int targetComponent,
                           int missionType, long opaqueId) {
        this.count = count;
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
        this.missionType = missionType;
        this.opaqueId = opaqueId;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU16(buf, 0, count);
        PayloadCodec.putU8(buf, 2, targetSystem);
        PayloadCodec.putU8(buf, 3, targetComponent);
        PayloadCodec.putU8(buf, 4, missionType);
        PayloadCodec.putU32(buf, 5, opaqueId);
        return buf;
    }

    public static MissionCountMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new MissionCountMsg(
                PayloadCodec.u16(b, 0),
                PayloadCodec.u8(b, 2),
                PayloadCodec.u8(b, 3),
                len > 4 ? PayloadCodec.u8(b, 4) : 0,
                len > 5 ? PayloadCodec.u32(b, 5) : 0);
    }
}
