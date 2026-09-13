package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** MISSION_REQUEST_LIST (msgId=143, LEN=4, CRC=132)。GCS→飞控：请求任务项数（拉取任务第一步）。 */
public final class MissionRequestList extends MavlinkMessage {

    public static final int ID = 143;
    public static final int LEN = 4;

    public final int targetSystem;
    public final int targetComponent;
    public final int missionType;

    public MissionRequestList(int targetSystem, int targetComponent, int missionType) {
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
        PayloadCodec.putU8(buf, 0, targetSystem);
        PayloadCodec.putU8(buf, 1, targetComponent);
        PayloadCodec.putU8(buf, 2, missionType);
        return buf;
    }

    public static MissionRequestList decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new MissionRequestList(
                PayloadCodec.u8(b, 0),
                PayloadCodec.u8(b, 1),
                f.getPayloadLength() > 2 ? PayloadCodec.u8(b, 2) : 0);
    }
}
