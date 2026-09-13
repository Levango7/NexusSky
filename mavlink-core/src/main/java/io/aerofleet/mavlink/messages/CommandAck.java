package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/** COMMAND_ACK (msgId=77, LEN=10, CRC=143)。飞控→GCS：命令执行结果。 */
public final class CommandAck extends MavlinkMessage {

    public static final int ID = 77;
    public static final int LEN = 10;

    public final int command;
    public final int result;        // MAV_RESULT
    public final int progress;
    public final int resultParam2;
    public final int targetSystem;
    public final int targetComponent;

    public CommandAck(int command, int result, int progress, int resultParam2,
                      int targetSystem, int targetComponent) {
        this.command = command;
        this.result = result;
        this.progress = progress;
        this.resultParam2 = resultParam2;
        this.targetSystem = targetSystem;
        this.targetComponent = targetComponent;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU16(buf, 0, command);
        PayloadCodec.putU8(buf, 2, result);
        PayloadCodec.putU8(buf, 3, progress);
        PayloadCodec.putI32(buf, 4, resultParam2);
        PayloadCodec.putU8(buf, 8, targetSystem);
        PayloadCodec.putU8(buf, 9, targetComponent);
        return buf;
    }

    public static CommandAck decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new CommandAck(
                PayloadCodec.u16(b, 0),
                PayloadCodec.u8(b, 2),
                len > 3 ? PayloadCodec.u8(b, 3) : -1,
                len > 4 ? PayloadCodec.i32(b, 4) : 0,
                len > 8 ? PayloadCodec.u8(b, 8) : 0,
                len > 9 ? PayloadCodec.u8(b, 9) : 0);
    }

    @Override
    public String toString() {
        return "CommandAck{cmd=" + command + ", result=" + result + "}";
    }
}
