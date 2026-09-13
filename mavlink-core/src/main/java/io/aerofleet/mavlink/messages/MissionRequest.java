package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * MISSION_REQUEST (msgId=43, LEN=5, CRC=230)。
 * 旧版（非 INT）任务拉取请求：PX4 上传任务时回复的是它而非 MISSION_REQUEST_INT，
 * 字段布局两者完全一致（PX4 实际也常发 INT 版，但兼容层必须两种都认）。
 */
public final class MissionRequest extends MavlinkMessage {

    public static final int ID = 43;
    public static final int LEN = 5;

    public final int seq;
    public final int targetSystem;
    public final int targetComponent;
    public final int missionType;

    public MissionRequest(int seq, int targetSystem, int targetComponent, int missionType) {
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

    public static MissionRequest decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new MissionRequest(
                PayloadCodec.u16(b, 0),
                PayloadCodec.u8(b, 2),
                PayloadCodec.u8(b, 3),
                f.getPayloadLength() > 4 ? PayloadCodec.u8(b, 4) : 0);
    }
}
