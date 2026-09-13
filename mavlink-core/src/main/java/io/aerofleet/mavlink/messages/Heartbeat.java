package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** HEARTBEAT (msgId=0, LEN=9, CRC=50)。周期心跳：类型/自驾仪/基础模式/系统状态。 */
public final class Heartbeat extends MavlinkMessage {

    public static final int ID = 0;
    public static final int LEN = 9;
    public static final int CRC_EXTRA = 50;

    public final int customMode;
    public final int type;
    public final int autopilot;
    public final int baseMode;
    public final int systemStatus;
    public final int mavlinkVersion = 3;

    public Heartbeat(int customMode, int type, int autopilot, int baseMode, int systemStatus) {
        this.customMode = customMode;
        this.type = type;
        this.autopilot = autopilot;
        this.baseMode = baseMode;
        this.systemStatus = systemStatus;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, customMode);
        PayloadCodec.putU8(buf, 4, type);
        PayloadCodec.putU8(buf, 5, autopilot);
        PayloadCodec.putU8(buf, 6, baseMode);
        PayloadCodec.putU8(buf, 7, systemStatus);
        PayloadCodec.putU8(buf, 8, mavlinkVersion);
        return buf;
    }

    public static Heartbeat decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        return new Heartbeat(
                (int) PayloadCodec.u32(b, 0),
                PayloadCodec.u8(b, 4),
                PayloadCodec.u8(b, 5),
                PayloadCodec.u8(b, 6),
                PayloadCodec.u8(b, 7));
    }

    @Override
    public String toString() {
        return "Heartbeat{customMode=" + customMode + ", type=" + type
                + ", autopilot=" + autopilot + ", baseMode=0x" + Integer.toHexString(baseMode)
                + ", systemStatus=" + systemStatus + "}";
    }
}
