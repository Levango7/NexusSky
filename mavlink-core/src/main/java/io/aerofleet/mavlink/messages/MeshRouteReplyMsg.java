package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * MESH_ROUTE_REPLY (msgId=452, LEN=10) —— NexusSky M5 AODV-lite 路由应答消息。
 * <p>
 * 目标节点收到 RREQ 后沿反向路径单播回传 RREP，中间节点建立正向路由并继续转发至源（FR-02/02a）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段           类型   单位/精度
 * 0    sourceSysid   u8    RREQ 发起源节点 sysid（RREP 最终目的地）
 * 1    targetSysid   u8    RREP 发起者（即 RREQ 目标）sysid
 * 2    hopCount      u8    已经历跳数
 * 3    metric        u16   累计度量（×100 整数化）
 * 5    timestamp     u32   时间戳（ms）
 * 9    reserved      u8    保留（0）
 * </pre>
 * 构造器接收 {@code double metric}，内部转 u16×100。
 * CRC_EXTRA = 235。
 */
public final class MeshRouteReplyMsg extends MavlinkMessage {

    public static final int ID = 452;
    public static final int LEN = 10;
    public static final int CRC_EXTRA = 235;

    public final int sourceSysid;
    public final int targetSysid;
    public final int hopCount;
    public final int metricRaw;       // u16 × 100
    public final long timestamp;      // ms

    /** 构造器：接收 double metric，内部转 u16×100（精度 0.01，上限 655.35）。 */
    public MeshRouteReplyMsg(int sourceSysid, int targetSysid, int hopCount,
                             double metric, long timestamp) {
        this(sourceSysid, targetSysid, hopCount,
                (int) Math.round(Math.max(0, Math.min(655.35, metric)) * 100),
                timestamp);
    }

    /** 全字段构造器（metricRaw 为已 ×100 的整数）。 */
    public MeshRouteReplyMsg(int sourceSysid, int targetSysid, int hopCount,
                             int metricRaw, long timestamp) {
        this.sourceSysid = sourceSysid;
        this.targetSysid = targetSysid;
        this.hopCount = hopCount;
        this.metricRaw = metricRaw & 0xFFFF;
        this.timestamp = timestamp;
    }

    /** 还原后的度量值（double）。 */
    public double metric() {
        return metricRaw / 100.0;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, sourceSysid);
        PayloadCodec.putU8(buf, 1, targetSysid);
        PayloadCodec.putU8(buf, 2, hopCount);
        PayloadCodec.putU16(buf, 3, metricRaw);
        PayloadCodec.putU32(buf, 5, timestamp);
        PayloadCodec.putU8(buf, 9, 0);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static MeshRouteReplyMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new MeshRouteReplyMsg(
                PayloadCodec.u8(b, 0),
                len > 1 ? PayloadCodec.u8(b, 1) : 0,
                len > 2 ? PayloadCodec.u8(b, 2) : 0,
                len > 4 ? PayloadCodec.u16(b, 3) : 0,
                len > 8 ? PayloadCodec.u32(b, 5) : 0L);
    }

    @Override
    public String toString() {
        return "MeshRouteReplyMsg{src=" + sourceSysid + " <- tgt=" + targetSysid
                + ", hops=" + hopCount + ", metric=" + metric() + ", ts=" + timestamp + "}";
    }
}