package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * TWIN_STATE_SYNC (msgId=475, LEN=28) —— NexusSky M13 数字孪生状态同步自定义扩展消息。
 * <p>
 * 承载数字孪生实体与物理实体的状态同步：孪生经纬度/高度/航向/速度/电量 + 同步时间戳 + 漂移量。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段            类型   单位/精度
 * 0    twinLat         i32   孪生纬度（1E7 度）
 * 4    twinLon         i32   孪生经度（1E7 度）
 * 8    twinAlt         i32   孪生高度（mm，AMSL）
 * 12   syncTimestamp   u32   同步时间戳（ms）
 * 16   twinVelocity    f32   孪生速度（m/s）
 * 20   driftMeters     f32   漂移量（m，孪生与物理实体位置差）
 * 24   twinHeading     u16   孪生航向（cdeg，0-36000 表示 0-360°）
 * 26   sysId           u8    发送方系统 ID
 * 27   twinBattery     u8    孪生电量（%，0-100）
 * </pre>
 * CRC_EXTRA = 259（M13 自定义扩展）。
 */
public final class TwinStateSyncMsg extends MavlinkMessage {

    public static final int ID = 475;
    public static final int LEN = 28;
    public static final int CRC_EXTRA = 259;

    public final int twinLat;           // 1E7 度
    public final int twinLon;           // 1E7 度
    public final int twinAlt;           // mm
    public final long syncTimestamp;    // ms
    public final float twinVelocity;    // m/s
    public final float driftMeters;     // m
    public final int twinHeading;       // cdeg
    public final int sysId;             // 发送方系统 ID
    public final int twinBattery;       // %

    public TwinStateSyncMsg(int twinLat, int twinLon, int twinAlt, long syncTimestamp,
                            float twinVelocity, float driftMeters, int twinHeading,
                            int sysId, int twinBattery) {
        this.twinLat = twinLat;
        this.twinLon = twinLon;
        this.twinAlt = twinAlt;
        this.syncTimestamp = syncTimestamp;
        this.twinVelocity = twinVelocity;
        this.driftMeters = driftMeters;
        this.twinHeading = twinHeading;
        this.sysId = sysId;
        this.twinBattery = twinBattery;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putI32(buf, 0, twinLat);
        PayloadCodec.putI32(buf, 4, twinLon);
        PayloadCodec.putI32(buf, 8, twinAlt);
        PayloadCodec.putU32(buf, 12, syncTimestamp);
        PayloadCodec.putF32(buf, 16, twinVelocity);
        PayloadCodec.putF32(buf, 20, driftMeters);
        PayloadCodec.putU16(buf, 24, twinHeading);
        PayloadCodec.putU8(buf, 26, sysId);
        PayloadCodec.putU8(buf, 27, twinBattery);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static TwinStateSyncMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new TwinStateSyncMsg(
                len > 3 ? PayloadCodec.i32(b, 0) : 0,
                len > 7 ? PayloadCodec.i32(b, 4) : 0,
                len > 11 ? PayloadCodec.i32(b, 8) : 0,
                len > 15 ? PayloadCodec.u32(b, 12) : 0,
                len > 19 ? PayloadCodec.f32(b, 16) : 0f,
                len > 23 ? PayloadCodec.f32(b, 20) : 0f,
                len > 25 ? PayloadCodec.u16(b, 24) : 0,
                len > 26 ? PayloadCodec.u8(b, 26) : 0,
                len > 27 ? PayloadCodec.u8(b, 27) : 0);
    }

    @Override
    public String toString() {
        return "TwinStateSyncMsg{sysId=" + sysId
                + ", pos=(" + twinLat + "," + twinLon + "," + twinAlt + "mm)"
                + ", vel=" + twinVelocity + "m/s"
                + ", hdg=" + (twinHeading / 100.0) + "deg"
                + ", batt=" + twinBattery + "%"
                + ", drift=" + driftMeters + "m}";
    }
}