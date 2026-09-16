package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * CONFLICT_ALERT (msgId=469, LEN=12) —— NexusSky M10 多机协同冲突告警自定义扩展消息。
 * <p>
 * 承载多机冲突告警：冲突类型 + 冲突方系统 ID + 严重程度 + 最小距离 + 冲突倒计时。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段              类型   单位/精度
 * 0    minDistance       f32   最小距离（m）
 * 4    timeToConflict    f32   冲突倒计时（s）
 * 8    sysId             u8    发送方系统 ID
 * 9    conflictType      u8    0=空域, 1=航径, 2=碰撞（{@link io.aerofleet.mavlink.enums.ConflictType}）
 * 10   conflictingSysId  u8    冲突方系统 ID
 * 11   severity          u8    严重程度（1-4）
 * </pre>
 * CRC_EXTRA = 253（M10 自定义扩展）。
 */
public final class ConflictAlertMsg extends MavlinkMessage {

    public static final int ID = 469;
    public static final int LEN = 12;
    public static final int CRC_EXTRA = 253;

    public final float minDistance;        // m
    public final float timeToConflict;     // s
    public final int sysId;                // 发送方系统 ID
    public final int conflictType;         // 0=空域, 1=航径, 2=碰撞
    public final int conflictingSysId;     // 冲突方系统 ID
    public final int severity;             // 1-4

    public ConflictAlertMsg(float minDistance, float timeToConflict,
                            int sysId, int conflictType, int conflictingSysId, int severity) {
        this.minDistance = minDistance;
        this.timeToConflict = timeToConflict;
        this.sysId = sysId;
        this.conflictType = conflictType;
        this.conflictingSysId = conflictingSysId;
        this.severity = severity;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putF32(buf, 0, minDistance);
        PayloadCodec.putF32(buf, 4, timeToConflict);
        PayloadCodec.putU8(buf, 8, sysId);
        PayloadCodec.putU8(buf, 9, conflictType);
        PayloadCodec.putU8(buf, 10, conflictingSysId);
        PayloadCodec.putU8(buf, 11, severity);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static ConflictAlertMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new ConflictAlertMsg(
                len > 3 ? PayloadCodec.f32(b, 0) : 0f,
                len > 7 ? PayloadCodec.f32(b, 4) : 0f,
                len > 8 ? PayloadCodec.u8(b, 8) : 0,
                len > 9 ? PayloadCodec.u8(b, 9) : 0,
                len > 10 ? PayloadCodec.u8(b, 10) : 0,
                len > 11 ? PayloadCodec.u8(b, 11) : 0);
    }

    @Override
    public String toString() {
        return "ConflictAlertMsg{sysId=" + sysId
                + ", type=" + conflictType + ", conflicting=" + conflictingSysId
                + ", sev=" + severity
                + ", minDist=" + minDistance + "m"
                + ", ttc=" + timeToConflict + "s}";
    }
}