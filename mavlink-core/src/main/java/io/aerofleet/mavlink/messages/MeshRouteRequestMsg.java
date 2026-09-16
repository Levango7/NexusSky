package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * MESH_ROUTE_REQUEST (msgId=451, LEN=12) —— NexusSky M5 AODV-lite 路由请求消息。
 * <p>
 * 源节点广播寻找到目标节点的路径，中间节点转发并建立反向路由，目标节点回传 RREP（FR-01）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段           类型   单位/精度
 * 0    sourceSysid   u8    发起 RREQ 的源节点 sysid
 * 1    targetSysid   u8    目标节点 sysid
 * 2    broadcastId   u16   广播 ID（同源递增，去重用）
 * 4    hopCount      u8    已经历跳数
 * 5    originMetric  u16   累计度量（×100 整数化）
 * 7    timestamp     u32   时间戳（ms）
 * 11   reserved      u8    保留（0）
 * </pre>
 * 构造器接收 {@code double originMetric}，内部转 u16×100（精度 0.01）。
 * CRC_EXTRA = 234。
 */
public final class MeshRouteRequestMsg extends MavlinkMessage {

    public static final int ID = 451;
    public static final int LEN = 12;
    public static final int CRC_EXTRA = 234;

    public final int sourceSysid;
    public final int targetSysid;
    public final int broadcastId;      // u16
    public final int hopCount;
    public final int originMetricRaw;  // u16 × 100
    public final long timestamp;       // ms

    /** 构造器：接收 double metric，内部转 u16×100（精度 0.01，上限 655.35）。 */
    public MeshRouteRequestMsg(int sourceSysid, int targetSysid, int broadcastId,
                               int hopCount, double originMetric, long timestamp) {
        this(sourceSysid, targetSysid, broadcastId, hopCount,
                (int) Math.round(Math.max(0, Math.min(655.35, originMetric)) * 100),
                timestamp);
    }

    /** 全字段构造器（originMetricRaw 为已 ×100 的整数）。 */
    public MeshRouteRequestMsg(int sourceSysid, int targetSysid, int broadcastId,
                               int hopCount, int originMetricRaw, long timestamp) {
        this.sourceSysid = sourceSysid;
        this.targetSysid = targetSysid;
        this.broadcastId = broadcastId & 0xFFFF;
        this.hopCount = hopCount;
        this.originMetricRaw = originMetricRaw & 0xFFFF;
        this.timestamp = timestamp;
    }

    /** 还原后的度量值（double）。 */
    public double originMetric() {
        return originMetricRaw / 100.0;
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
        PayloadCodec.putU16(buf, 2, broadcastId);
        PayloadCodec.putU8(buf, 4, hopCount);
        PayloadCodec.putU16(buf, 5, originMetricRaw);
        PayloadCodec.putU32(buf, 7, timestamp);
        PayloadCodec.putU8(buf, 11, 0);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static MeshRouteRequestMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new MeshRouteRequestMsg(
                PayloadCodec.u8(b, 0),
                len > 1 ? PayloadCodec.u8(b, 1) : 0,
                len > 3 ? PayloadCodec.u16(b, 2) : 0,
                len > 4 ? PayloadCodec.u8(b, 4) : 0,
                len > 6 ? PayloadCodec.u16(b, 5) : 0,
                len > 10 ? PayloadCodec.u32(b, 7) : 0L);
    }

    @Override
    public String toString() {
        return "MeshRouteRequestMsg{src=" + sourceSysid + " -> tgt=" + targetSysid
                + ", bcastId=" + broadcastId + ", hops=" + hopCount
                + ", metric=" + originMetric() + ", ts=" + timestamp + "}";
    }
}