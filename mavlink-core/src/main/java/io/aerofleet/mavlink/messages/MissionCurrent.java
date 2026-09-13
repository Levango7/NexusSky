package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** MISSION_CURRENT (msgId=42, LEN=18, CRC=28)。当前执行航点 seq 与任务状态。 */
public final class MissionCurrent extends MavlinkMessage {

    public static final int ID = 42;
    public static final int LEN = 18;

    public final int seq;
    public final int total;
    public final int missionState;   // MISSION_STATE_*
    public final int missionMode;
    public final long missionId;
    public final long fenceId;
    public final long rallyPointsId;

    public MissionCurrent(int seq, int total, int missionState, int missionMode,
                         long missionId, long fenceId, long rallyPointsId) {
        this.seq = seq;
        this.total = total;
        this.missionState = missionState;
        this.missionMode = missionMode;
        this.missionId = missionId;
        this.fenceId = fenceId;
        this.rallyPointsId = rallyPointsId;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU16(buf, 0, seq);
        PayloadCodec.putU16(buf, 2, total);
        PayloadCodec.putU8(buf, 4, missionState);
        PayloadCodec.putU8(buf, 5, missionMode);
        PayloadCodec.putU32(buf, 6, missionId);
        PayloadCodec.putU32(buf, 10, fenceId);
        PayloadCodec.putU32(buf, 14, rallyPointsId);
        return buf;
    }

    public static MissionCurrent decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new MissionCurrent(
                PayloadCodec.u16(b, 0),
                len > 2 ? PayloadCodec.u16(b, 2) : 0,
                len > 4 ? PayloadCodec.u8(b, 4) : 0,
                len > 5 ? PayloadCodec.u8(b, 5) : 0,
                len > 6 ? PayloadCodec.u32(b, 6) : 0,
                len > 10 ? PayloadCodec.u32(b, 10) : 0,
                len > 14 ? PayloadCodec.u32(b, 14) : 0);
    }
}
