package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * SAT_PASS_SCHEDULE (msgId=460, LEN=16) —— NexusSky M7 卫星过境计划预告消息。
 * <p>
 * 预告卫星可见窗口的起止时间与最大仰角，供 GCS 可视化与任务规划参考（FR-5.1）。
 * 由 drone-sim {@code LinkWindowCalculator} 计算产出并 UDP 上报到 cloud-backend。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型   单位/精度
 * 0    satId            u16    卫星 ID
 * 2    passStartMs     u32    过境开始时间（仿真时钟 ms）
 * 6    passEndMs       u32    过境结束时间（仿真时钟 ms）
 * 10   maxElevationDeg u8     过境期间最大仰角（度，0-90）
 * 11   groundPointId   u8     地面点标识
 * 12   timestamp       u32    时间戳（ms）
 * </pre>
 * CRC_EXTRA = 239。
 */
public final class SatPassScheduleMsg extends MavlinkMessage {

    public static final int ID = 460;
    public static final int LEN = 16;
    public static final int CRC_EXTRA = 239;

    public final int satId;              // u16
    public final long passStartMs;       // ms
    public final long passEndMs;         // ms
    public final int maxElevationDeg;    // 0-90
    public final int groundPointId;      // 地面点标识
    public final long timestamp;         // ms

    public SatPassScheduleMsg(int satId, long passStartMs, long passEndMs,
                              int maxElevationDeg, int groundPointId, long timestamp) {
        this.satId = satId & 0xFFFF;
        this.passStartMs = passStartMs;
        this.passEndMs = passEndMs;
        this.maxElevationDeg = maxElevationDeg & 0xFF;
        this.groundPointId = groundPointId & 0xFF;
        this.timestamp = timestamp;
    }

    /** 窗口持续时长（ms）。 */
    public long durationMs() {
        return passEndMs - passStartMs;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU16(buf, 0, satId);
        PayloadCodec.putU32(buf, 2, passStartMs);
        PayloadCodec.putU32(buf, 6, passEndMs);
        PayloadCodec.putU8(buf, 10, maxElevationDeg);
        PayloadCodec.putU8(buf, 11, groundPointId);
        PayloadCodec.putU32(buf, 12, timestamp);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static SatPassScheduleMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new SatPassScheduleMsg(
                PayloadCodec.u16(b, 0),
                len > 5 ? PayloadCodec.u32(b, 2) : 0L,
                len > 9 ? PayloadCodec.u32(b, 6) : 0L,
                len > 10 ? PayloadCodec.u8(b, 10) : 0,
                len > 11 ? PayloadCodec.u8(b, 11) : 0,
                len > 15 ? PayloadCodec.u32(b, 12) : 0L);
    }

    @Override
    public String toString() {
        return "SatPassScheduleMsg{sat=" + satId + ", pass=[" + passStartMs + "-" + passEndMs
                + "], maxEl=" + maxElevationDeg + "°, ground=" + groundPointId
                + ", ts=" + timestamp + "}";
    }
}