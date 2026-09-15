package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * GRIPPER_COMMAND (msgId=425, LEN=7) —— NexusSky 自定义扩展消息（M2 喷洒物流，FR-28）。
 * <p>
 * 承载抛投控制命令（抓取/投放/复位），由云端/GCS 事件驱动下发到 drone-sim。
 * <p>
 * 字段布局（小端，按 spec.md §6.3）：
 * <pre>
 * 偏移  字段            类型     单位/精度
 * 0    command         uint8   枚举 GRAB=0/RELEASE=1/RESET=2
 * 1    payloadId       uint8   负载标识
 * 2    payloadWeight   uint16  cg（×10，克）
 * 4    payloadVolume   uint16  cL（×10，厘升）
 * 6    reserved        uint8
 * </pre>
 * CRC_EXTRA = 46389（按 MavlinkCrc 对消息名+字段名+类型计算）。
 */
public final class GripperCommand extends MavlinkMessage {

    public static final int ID = 425;
    public static final int LEN = 7;

    /** command 枚举值。 */
    public static final int COMMAND_GRAB = 0;
    public static final int COMMAND_RELEASE = 1;
    public static final int COMMAND_RESET = 2;

    public final int command;          // 枚举 {0,1,2}
    public final int payloadId;        // 负载标识
    public final int payloadWeight;    // cg（×10，克）
    public final int payloadVolume;    // cL（×10，厘升）

    public GripperCommand(int command, int payloadId, int payloadWeight, int payloadVolume) {
        this.command = command;
        this.payloadId = payloadId;
        this.payloadWeight = payloadWeight;
        this.payloadVolume = payloadVolume;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, command);
        PayloadCodec.putU8(buf, 1, payloadId);
        PayloadCodec.putU16(buf, 2, payloadWeight);
        PayloadCodec.putU16(buf, 4, payloadVolume);
        PayloadCodec.putU8(buf, 6, 0);  // reserved
        return buf;
    }

    /**
     * 从帧解码；payload 短于所需字段时容忍解码（缺失字段填默认值 0）。
     */
    public static GripperCommand decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new GripperCommand(
                PayloadCodec.u8(b, 0),
                len > 2 ? PayloadCodec.u8(b, 1) : 0,
                len > 4 ? PayloadCodec.u16(b, 2) : 0,
                len > 6 ? PayloadCodec.u16(b, 4) : 0);
    }
}