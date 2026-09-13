package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** MISSION_ACK (msgId=47, LEN=8, CRC=153)。飞控→GCS：任务传输结果。 */
public final class MissionAckMsg extends MavlinkMessage {

    public static final int ID = 47;
    public static final int LEN = 8;

    public final int targetSystem;
    public final int targetComponent;
    public final int type;        // MAV_MISSION_RESULT
    public final int missionType;
    public final long opaqueId;

    public MissionAckMsg(int targetSystem, int targetComponent, int type,
                         int missionType, long opaqueId) {
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
        this.type = type;
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
        PayloadCodec.putU8(buf, 0, targetSystem);
        PayloadCodec.putU8(buf, 1, targetComponent);
        PayloadCodec.putU8(buf, 2, type);
        PayloadCodec.putU8(buf, 3, missionType);
        PayloadCodec.putU32(buf, 4, opaqueId);
        return buf;
    }

    public static MissionAckMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new MissionAckMsg(
                PayloadCodec.u8(b, 0),
                PayloadCodec.u8(b, 1),
                PayloadCodec.u8(b, 2),
                len > 3 ? PayloadCodec.u8(b, 3) : 0,
                len > 4 ? PayloadCodec.u32(b, 4) : 0);
    }
}
