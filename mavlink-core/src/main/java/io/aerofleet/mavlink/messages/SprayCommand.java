package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * SPRAY_COMMAND (msgId=424, LEN=6) —— NexusSky 自定义扩展消息（M2 喷洒物流，FR-27）。
 * <p>
 * 承载喷洒控制命令（开/关/设流量/紧急停喷），由云端/GCS 事件驱动下发到 drone-sim。
 * <p>
 * 字段布局（小端，按 spec.md §6.3）：
 * <pre>
 * 偏移  字段        类型     单位/精度
 * 0    command     uint8   枚举 ENABLE=0/DISABLE=1/SET_RATE=2/EMERGENCY_STOP=3
 * 1    targetRate  uint16  mL/s
 * 3    sprayWidth  uint16  cm（×100）
 * 5    reserved    uint8
 * </pre>
 * CRC_EXTRA = 52077（按 MavlinkCrc 对消息名+字段名+类型计算）。
 */
public final class SprayCommand extends MavlinkMessage {

    public static final int ID = 424;
    public static final int LEN = 6;

    /** command 枚举值。 */
    public static final int COMMAND_ENABLE = 0;
    public static final int COMMAND_DISABLE = 1;
    public static final int COMMAND_SET_RATE = 2;
    public static final int COMMAND_EMERGENCY_STOP = 3;

    public final int command;        // 枚举 {0,1,2,3}
    public final int targetRate;     // mL/s
    public final int sprayWidth;     // cm

    public SprayCommand(int command, int targetRate, int sprayWidth) {
        this.command = command;
        this.targetRate = targetRate;
        this.sprayWidth = sprayWidth;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, command);
        PayloadCodec.putU16(buf, 1, targetRate);
        PayloadCodec.putU16(buf, 3, sprayWidth);
        PayloadCodec.putU8(buf, 5, 0);  // reserved
        return buf;
    }

    /**
     * 从帧解码；payload 短于所需字段时容忍解码（缺失字段填默认值 0）。
     */
    public static SprayCommand decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new SprayCommand(
                PayloadCodec.u8(b, 0),
                len > 3 ? PayloadCodec.u16(b, 1) : 0,
                len > 5 ? PayloadCodec.u16(b, 3) : 0);
    }
}