package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * SPRAY_STATUS (msgId=423, LEN=12) —— NexusSky 自定义扩展消息（M2 喷洒物流，FR-26）。
 * <p>
 * 承载当前喷洒状态，由 drone-sim 2Hz 周期下传。
 * <p>
 * 字段布局（小端，按 spec.md §6.3）：
 * <pre>
 * 偏移  字段                    类型     单位/精度
 * 0    enabled                 uint8   0/1
 * 1    rate                    uint16  mL/s
 * 3    remainingChemical       uint16  mL
 * 5    coveragePercent         uint8   %
 * 6    lowChemical             uint8   0/1
 * 7    driftOffsetAngle        int16   cdeg（×100）
 * 9    flowCorrectionPercent   uint8   %
 * 10   reserved1               uint8
 * 11   reserved2               uint8
 * </pre>
 * CRC_EXTRA = 58864（按 MavlinkCrc 对消息名+字段名+类型计算）。
 */
public final class SprayStatus extends MavlinkMessage {

    public static final int ID = 423;
    public static final int LEN = 12;

    public final boolean enabled;
    public final int rate;                  // mL/s
    public final int remainingChemical;     // mL
    public final int coveragePercent;       // %
    public final boolean lowChemical;
    public final int driftOffsetAngle;      // cdeg（百分之一度）
    public final int flowCorrectionPercent; // %

    public SprayStatus(boolean enabled, int rate, int remainingChemical,
                       int coveragePercent, boolean lowChemical,
                       int driftOffsetAngle, int flowCorrectionPercent) {
        this.enabled = enabled;
        this.rate = rate;
        this.remainingChemical = remainingChemical;
        this.coveragePercent = coveragePercent;
        this.lowChemical = lowChemical;
        this.driftOffsetAngle = driftOffsetAngle;
        this.flowCorrectionPercent = flowCorrectionPercent;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, enabled ? 1 : 0);
        PayloadCodec.putU16(buf, 1, rate);
        PayloadCodec.putU16(buf, 3, remainingChemical);
        PayloadCodec.putU8(buf, 5, coveragePercent);
        PayloadCodec.putU8(buf, 6, lowChemical ? 1 : 0);
        PayloadCodec.putI16(buf, 7, driftOffsetAngle);
        PayloadCodec.putU8(buf, 9, flowCorrectionPercent);
        PayloadCodec.putU8(buf, 10, 0);  // reserved1
        PayloadCodec.putU8(buf, 11, 0);  // reserved2
        return buf;
    }

    /**
     * 从帧解码；payload 短于所需字段时容忍解码（缺失字段填默认值 0，与 {@link RadioStatus} 一致）。
     */
    public static SprayStatus decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new SprayStatus(
                PayloadCodec.u8(b, 0) > 0,
                len > 3 ? PayloadCodec.u16(b, 1) : 0,
                len > 5 ? PayloadCodec.u16(b, 3) : 0,
                len > 6 ? PayloadCodec.u8(b, 5) : 0,
                len > 7 && PayloadCodec.u8(b, 6) > 0,
                len > 9 ? PayloadCodec.i16(b, 7) : 0,
                len > 10 ? PayloadCodec.u8(b, 9) : 0);
    }
}