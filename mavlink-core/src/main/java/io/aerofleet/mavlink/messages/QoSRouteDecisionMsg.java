package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * QOS_ROUTE_DECISION (msgId=480, LEN=12) —— NexusSky P2 灾害应急通讯组网扩展消息。
 * <p>
 * QoS 路由决策通知：告知 mesh 节点当前路由的优先级类别与带宽分配。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型      单位/精度
 * 0    timestamp         u32      时间戳（ms）
 * 4    bandwidthAlloc    u16      带宽分配（kbps）
 * 6    routeId           u8       路由 ID
 * 7    priorityClass     u8       0=EMERGENCY, 1=COMMAND, 2=MAPPING, 3=ROUTINE
 * 8    sourceSysid       u8       决策发起方 sysid
 * 9    targetSysid       u8       目标 sysid（0=广播）
 * 10   reserved          u8[2]    保留（0填充）
 * </pre>
 * CRC_EXTRA = 264（P2 自定义扩展）。
 */
public final class QoSRouteDecisionMsg extends MavlinkMessage {

    public static final int ID = 480;
    public static final int LEN = 12;
    public static final int CRC_EXTRA = 264;

    public final long timestamp;       // ms
    public final int bandwidthAlloc;   // kbps
    public final int routeId;          // 路由 ID
    public final int priorityClass;    // 0=EMERGENCY, 1=COMMAND, 2=MAPPING, 3=ROUTINE
    public final int sourceSysid;      // 决策发起方 sysid
    public final int targetSysid;      // 目标 sysid（0=广播）

    public QoSRouteDecisionMsg(long timestamp, int bandwidthAlloc, int routeId,
                               int priorityClass, int sourceSysid, int targetSysid) {
        if (priorityClass < 0 || priorityClass > 3) {
            throw new IllegalArgumentException("priorityClass must be in [0, 3]: " + priorityClass);
        }
        this.timestamp = timestamp;
        this.bandwidthAlloc = bandwidthAlloc;
        this.routeId = routeId;
        this.priorityClass = priorityClass;
        this.sourceSysid = sourceSysid;
        this.targetSysid = targetSysid;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, timestamp);
        PayloadCodec.putU16(buf, 4, bandwidthAlloc);
        PayloadCodec.putU8(buf, 6, routeId);
        PayloadCodec.putU8(buf, 7, priorityClass);
        PayloadCodec.putU8(buf, 8, sourceSysid);
        PayloadCodec.putU8(buf, 9, targetSysid);
        PayloadCodec.putU8(buf, 10, 0); // reserved
        PayloadCodec.putU8(buf, 11, 0); // reserved
        return buf;
    }

    public static QoSRouteDecisionMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new QoSRouteDecisionMsg(
                PayloadCodec.u32(b, 0),
                len > 5 ? PayloadCodec.u16(b, 4) : 0,
                len > 6 ? PayloadCodec.u8(b, 6) : 0,
                len > 7 ? PayloadCodec.u8(b, 7) : 0,
                len > 8 ? PayloadCodec.u8(b, 8) : 0,
                len > 9 ? PayloadCodec.u8(b, 9) : 0);
    }

    @Override
    public String toString() {
        return "QoSRouteDecisionMsg{priority=" + priorityClass + ", routeId=" + routeId
                + ", bw=" + bandwidthAlloc + "kbps, src=" + sourceSysid
                + ", tgt=" + targetSysid + ", ts=" + timestamp + "}";
    }
}