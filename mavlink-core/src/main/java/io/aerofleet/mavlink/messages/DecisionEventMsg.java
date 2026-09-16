package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * DECISION_EVENT (msgId=471, LEN=15) —— NexusSky M11 自主决策事件自定义扩展消息。
 * <p>
 * 承载自主决策事件：决策类型 + 触发原因 + 触发值 + 置信度 + 时间戳。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段           类型   单位/精度
 * 0    triggerValue   f32   触发值（语义随决策类型而异，如距离/电量/风速）
 * 4    confidence     f32   置信度（0.0-1.0）
 * 8    timestamp      u32   时间戳（ms）
 * 12   sysId          u8    发送方系统 ID
 * 13   decisionType   u8    0=RTL, 1=避障, 2=自适应航径, 3=紧急降落（{@link io.aerofleet.mavlink.enums.DecisionType}）
 * 14   reason         u8    触发原因码（自定义编码）
 * </pre>
 * CRC_EXTRA = 255（M11 自定义扩展）。
 */
public final class DecisionEventMsg extends MavlinkMessage {

    public static final int ID = 471;
    public static final int LEN = 15;
    public static final int CRC_EXTRA = 255;

    public final float triggerValue;     // 触发值
    public final float confidence;       // 0.0-1.0
    public final long timestamp;         // ms
    public final int sysId;              // 发送方系统 ID
    public final int decisionType;       // 0=RTL, 1=避障, 2=自适应航径, 3=紧急降落
    public final int reason;             // 触发原因码

    public DecisionEventMsg(float triggerValue, float confidence, long timestamp,
                            int sysId, int decisionType, int reason) {
        this.triggerValue = triggerValue;
        this.confidence = confidence;
        this.timestamp = timestamp;
        this.sysId = sysId;
        this.decisionType = decisionType;
        this.reason = reason;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, triggerValue);
        PayloadCodec.putF32(buf, 4, confidence);
        PayloadCodec.putU32(buf, 8, timestamp);
        PayloadCodec.putU8(buf, 12, sysId);
        PayloadCodec.putU8(buf, 13, decisionType);
        PayloadCodec.putU8(buf, 14, reason);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static DecisionEventMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new DecisionEventMsg(
                len > 3 ? PayloadCodec.f32(b, 0) : 0f,
                len > 7 ? PayloadCodec.f32(b, 4) : 0f,
                len > 11 ? PayloadCodec.u32(b, 8) : 0,
                len > 12 ? PayloadCodec.u8(b, 12) : 0,
                len > 13 ? PayloadCodec.u8(b, 13) : 0,
                len > 14 ? PayloadCodec.u8(b, 14) : 0);
    }

    @Override
    public String toString() {
        return "DecisionEventMsg{sysId=" + sysId
                + ", type=" + decisionType + ", reason=" + reason
                + ", trigger=" + triggerValue
                + ", conf=" + confidence + "}";
    }
}