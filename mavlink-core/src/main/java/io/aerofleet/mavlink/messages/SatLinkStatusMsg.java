package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * SAT_LINK_STATUS (msgId=459, LEN=24) —— NexusSky M7 星-空-地多层级中继自定义扩展消息。
 * <p>
 * 周期上报每颗可见卫星的链路状态：仰角、方位角、延迟、带宽、窗口结束时间（FR-5.4）。
 * 由 drone-sim {@code SatRelayEngine} 周期产出并 UDP 上报到 cloud-backend。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型   单位/精度
 * 0    satId            u16    卫星 ID
 * 2    visible          u8     可见性（0=不可见, 1=可见）
 * 3    elevationDeg    u8     仰角（度，0-90）
 * 4    azimuthDeg      u16    方位角（度，0-359）
 * 6    delayMs         u16    链路延迟（ms，50-500）
 * 8    bandwidthMbps   u8     可用带宽（Mbps，0-50，带宽耗尽时 0）
 * 9    windowEndMs     u32    当前可见窗口结束时间（ms，不可见时 0）
 * 13   sharedUsers     u8     当前共享用户数
 * 14   timestamp       u32    时间戳（ms）
 * 18   simFlag         u8     仿真标识（1=仿真，DFX 4.3.3 链路状态真实性）
 * 19   reserved        u8     保留（0）
 * 20   reserved2       u32    保留（0）
 * </pre>
 * CRC_EXTRA = 238（M7 自定义扩展，接续 M5 的 237）。
 */
public final class SatLinkStatusMsg extends MavlinkMessage {

    public static final int ID = 459;
    public static final int LEN = 24;
    public static final int CRC_EXTRA = 238;

    public final int satId;            // u16
    public final int visible;          // 0/1
    public final int elevationDeg;     // 0-90
    public final int azimuthDeg;       // 0-359
    public final int delayMs;          // 50-500
    public final int bandwidthMbps;    // 0-50
    public final long windowEndMs;     // ms
    public final int sharedUsers;      // 共享用户数
    public final long timestamp;       // ms
    public final int simFlag;          // 1=仿真

    public SatLinkStatusMsg(int satId, int visible, int elevationDeg, int azimuthDeg,
                            int delayMs, int bandwidthMbps, long windowEndMs,
                            int sharedUsers, long timestamp, int simFlag) {
        this.satId = satId & 0xFFFF;
        this.visible = visible & 0xFF;
        this.elevationDeg = elevationDeg & 0xFF;
        this.azimuthDeg = azimuthDeg & 0xFFFF;
        this.delayMs = delayMs & 0xFFFF;
        this.bandwidthMbps = bandwidthMbps & 0xFF;
        this.windowEndMs = windowEndMs;
        this.sharedUsers = sharedUsers & 0xFF;
        this.timestamp = timestamp;
        this.simFlag = simFlag & 0xFF;
    }

    /** 便捷工厂：仿真链路状态（simFlag=1）。 */
    public static SatLinkStatusMsg simulated(int satId, int visible, int elevationDeg, int azimuthDeg,
                                             int delayMs, int bandwidthMbps, long windowEndMs,
                                             int sharedUsers, long timestamp) {
        return new SatLinkStatusMsg(satId, visible, elevationDeg, azimuthDeg,
                delayMs, bandwidthMbps, windowEndMs, sharedUsers, timestamp, 1);
    }

    public boolean isVisible() {
        return visible != 0;
    }

    public boolean isSimulated() {
        return simFlag != 0;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU16(buf, 0, satId);
        PayloadCodec.putU8(buf, 2, visible);
        PayloadCodec.putU8(buf, 3, elevationDeg);
        PayloadCodec.putU16(buf, 4, azimuthDeg);
        PayloadCodec.putU16(buf, 6, delayMs);
        PayloadCodec.putU8(buf, 8, bandwidthMbps);
        PayloadCodec.putU32(buf, 9, windowEndMs);
        PayloadCodec.putU8(buf, 13, sharedUsers);
        PayloadCodec.putU32(buf, 14, timestamp);
        PayloadCodec.putU8(buf, 18, simFlag);
        PayloadCodec.putU8(buf, 19, 0);
        PayloadCodec.putU32(buf, 20, 0L);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static SatLinkStatusMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new SatLinkStatusMsg(
                PayloadCodec.u16(b, 0),
                len > 2 ? PayloadCodec.u8(b, 2) : 0,
                len > 3 ? PayloadCodec.u8(b, 3) : 0,
                len > 5 ? PayloadCodec.u16(b, 4) : 0,
                len > 7 ? PayloadCodec.u16(b, 6) : 0,
                len > 8 ? PayloadCodec.u8(b, 8) : 0,
                len > 12 ? PayloadCodec.u32(b, 9) : 0L,
                len > 13 ? PayloadCodec.u8(b, 13) : 0,
                len > 17 ? PayloadCodec.u32(b, 14) : 0L,
                len > 18 ? PayloadCodec.u8(b, 18) : 0);
    }

    @Override
    public String toString() {
        return "SatLinkStatusMsg{sat=" + satId + ", visible=" + visible
                + ", el=" + elevationDeg + "°, az=" + azimuthDeg + "°"
                + ", delay=" + delayMs + "ms, bw=" + bandwidthMbps + "Mbps"
                + ", windowEnd=" + windowEndMs + ", users=" + sharedUsers
                + ", ts=" + timestamp + ", sim=" + simFlag + "}";
    }
}