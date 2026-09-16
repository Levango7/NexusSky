package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * MESH_HEARTBEAT (msgId=450, LEN=24) —— NexusSky M5 应急 mesh 自愈组网自定义扩展消息。
 * <p>
 * 承载节点周期性 HELLO 广播：sysid + 经纬高 + 电量 + 邻居数 + 时间戳，由 drone-sim
 * {@code MeshRouter} 周期产出并 UDP 广播到 mesh 组播地址（FR-09）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型   单位/精度
 * 0    sysid            u8    源飞机 sysid（1-255）
 * 1    lat              i32   纬度（1E7 度）
 * 5    lon              i32   经度（1E7 度）
 * 9    alt              i32   高度（mm AMSL）
 * 13   batteryPercent   u8    电量百分比（0-100）
 * 14   neighborCount    u8    当前邻居数
 * 15   timestamp        u32   时间戳（ms）
 * 19   reserved         u8    保留（0）
 * 20   reserved2        u32   保留（0）
 * </pre>
 * CRC_EXTRA = 233（M5 自定义扩展）。
 */
public final class MeshHeartbeatMsg extends MavlinkMessage {

    public static final int ID = 450;
    public static final int LEN = 24;
    public static final int CRC_EXTRA = 233;

    public final int sysid;            // 1-255
    public final int lat;              // 1E7 度
    public final int lon;              // 1E7 度
    public final int alt;              // mm AMSL
    public final int batteryPercent;   // 0-100
    public final int neighborCount;
    public final long timestamp;       // ms

    public MeshHeartbeatMsg(int sysid, int lat, int lon, int alt,
                            int batteryPercent, int neighborCount, long timestamp) {
        this.sysid = sysid;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.batteryPercent = batteryPercent;
        this.neighborCount = neighborCount;
        this.timestamp = timestamp;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, sysid);
        PayloadCodec.putI32(buf, 1, lat);
        PayloadCodec.putI32(buf, 5, lon);
        PayloadCodec.putI32(buf, 9, alt);
        PayloadCodec.putU8(buf, 13, batteryPercent);
        PayloadCodec.putU8(buf, 14, neighborCount);
        PayloadCodec.putU32(buf, 15, timestamp);
        PayloadCodec.putU8(buf, 19, 0);
        PayloadCodec.putU32(buf, 20, 0L);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static MeshHeartbeatMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new MeshHeartbeatMsg(
                PayloadCodec.u8(b, 0),
                len > 4 ? PayloadCodec.i32(b, 1) : 0,
                len > 8 ? PayloadCodec.i32(b, 5) : 0,
                len > 12 ? PayloadCodec.i32(b, 9) : 0,
                len > 13 ? PayloadCodec.u8(b, 13) : 0,
                len > 14 ? PayloadCodec.u8(b, 14) : 0,
                len > 18 ? PayloadCodec.u32(b, 15) : 0L);
    }

    @Override
    public String toString() {
        return "MeshHeartbeatMsg{sysid=" + sysid
                + ", lat=" + lat + ", lon=" + lon + ", alt=" + alt
                + ", battery=" + batteryPercent + "%, neighbors=" + neighborCount
                + ", ts=" + timestamp + "}";
    }
}