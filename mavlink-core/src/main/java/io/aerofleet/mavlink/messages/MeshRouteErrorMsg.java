package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * MESH_ROUTE_ERROR (msgId=453, LEN=4) —— NexusSky M5 AODV-lite 路由错误消息。
 * <p>
 * 链路断或邻居超时触发 RERR 通知，收到 RERR 的节点删除受影响路由并继续传播（FR-03/13/14）。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段               类型   单位/精度
 * 0    unreachableSysid   u8    不可达目标 sysid
 * 1    hopCount           u8    已经历跳数
 * 2    timestamp          u16   时间戳（ms 截断为 u16，65536ms 回绕）
 * </pre>
 * CRC_EXTRA = 236。timestamp 截断为 u16（65536ms 回绕）。
 */
public final class MeshRouteErrorMsg extends MavlinkMessage {

    public static final int ID = 453;
    public static final int LEN = 4;
    public static final int CRC_EXTRA = 236;

    public final int unreachableSysid;
    public final int hopCount;
    public final int timestamp;        // u16 ms 截断

    /** 构造器：接收 long timestamp，内部截断为 u16。 */
    public MeshRouteErrorMsg(int unreachableSysid, int hopCount, long timestamp) {
        this(unreachableSysid, hopCount, (int) (timestamp & 0xFFFF));
    }

    /** 全字段构造器。 */
    public MeshRouteErrorMsg(int unreachableSysid, int hopCount, int timestamp) {
        this.unreachableSysid = unreachableSysid;
        this.hopCount = hopCount;
        this.timestamp = timestamp & 0xFFFF;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, unreachableSysid);
        PayloadCodec.putU8(buf, 1, hopCount);
        PayloadCodec.putU16(buf, 2, timestamp);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static MeshRouteErrorMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        return new MeshRouteErrorMsg(
                PayloadCodec.u8(b, 0),
                len > 1 ? PayloadCodec.u8(b, 1) : 0,
                len > 3 ? PayloadCodec.u16(b, 2) : 0);
    }

    @Override
    public String toString() {
        return "MeshRouteErrorMsg{unreachable=" + unreachableSysid
                + ", hops=" + hopCount + ", ts=" + timestamp + "}";
    }
}