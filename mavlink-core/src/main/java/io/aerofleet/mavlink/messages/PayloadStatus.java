package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * PAYLOAD_STATUS (msgId=426, LEN=10) —— NexusSky 自定义扩展消息（M2 喷洒物流，FR-29）。
 * <p>
 * 承载当前负载/抛投状态，由 drone-sim 1Hz 周期下传。
 * <p>
 * 字段布局（小端，按 spec.md §6.3）：
 * <pre>
 * 偏移  字段                     类型     单位/精度
 * 0    gripperState             uint8   枚举 IDLE=0/GRABBING=1/HOLDING=2/RELEASING=3/RELEASED=4/FAULT=5
 * 1    currentPayloadWeight     uint16  cg（×10，克）
 * 3    currentPayloadVolume     uint16  cL（×10，厘升）
 * 5    remainingSites           uint8   剩余站点数
 * 6    currentSiteIndex         uint8   当前站点索引
 * 7    dropAccuracyCm           uint16  cm
 * 9    reserved                 uint8
 * </pre>
 * CRC_EXTRA = 9268（按 MavlinkCrc 对消息名+字段名+类型计算）。
 */
public final class PayloadStatus extends MavlinkMessage {

    public static final int ID = 426;
    public static final int LEN = 10;

    public final int gripperState;            // 枚举 {0,1,2,3,4,5}
    public final int currentPayloadWeight;    // cg（×10，克）
    public final int currentPayloadVolume;    // cL（×10，厘升）
    public final int remainingSites;          // 剩余站点数
    public final int currentSiteIndex;        // 当前站点索引
    public final int dropAccuracyCm;          // cm

    public PayloadStatus(int gripperState, int currentPayloadWeight,
                         int currentPayloadVolume, int remainingSites,
                         int currentSiteIndex, int dropAccuracyCm) {
        this.gripperState = gripperState;
        this.currentPayloadWeight = currentPayloadWeight;
        this.currentPayloadVolume = currentPayloadVolume;
        this.remainingSites = remainingSites;
        this.currentSiteIndex = currentSiteIndex;
        this.dropAccuracyCm = dropAccuracyCm;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, gripperState);
        PayloadCodec.putU16(buf, 1, currentPayloadWeight);
        PayloadCodec.putU16(buf, 3, currentPayloadVolume);
        PayloadCodec.putU8(buf, 5, remainingSites);
        PayloadCodec.putU8(buf, 6, currentSiteIndex);
        PayloadCodec.putU16(buf, 7, dropAccuracyCm);
        PayloadCodec.putU8(buf, 9, 0);  // reserved
        return buf;
    }

    /**
     * 从帧解码；payload 短于所需字段时容忍解码（缺失字段填默认值 0）。
     */
    public static PayloadStatus decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new PayloadStatus(
                PayloadCodec.u8(b, 0),
                len > 3 ? PayloadCodec.u16(b, 1) : 0,
                len > 5 ? PayloadCodec.u16(b, 3) : 0,
                len > 6 ? PayloadCodec.u8(b, 5) : 0,
                len > 7 ? PayloadCodec.u8(b, 6) : 0,
                len > 9 ? PayloadCodec.u16(b, 7) : 0);
    }
}