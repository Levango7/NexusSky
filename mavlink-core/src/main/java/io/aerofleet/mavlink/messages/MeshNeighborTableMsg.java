package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MESH_NEIGHBOR_TABLE (msgId=454, LEN=可变 6+n×4) —— NexusSky M5 拓扑快照上报消息。
 * <p>
 * 各节点周期上报本地邻居表至 cloud-backend，云端聚合为全网拓扑快照供 REST/WebSocket 查询（FR-27）。
 * <p>
 * 字段布局（小端，可变长度 6 + neighborCount × 4）：
 * <pre>
 * 偏移  字段              类型   说明
 * 0    sysid            u8    源飞机 sysid
 * 1    neighborCount    u8    邻居数（上限 255，总长上限 1026 字节）
 * 2    timestamp        u32   时间戳（ms）
 * 6    neighbors[n]     每项 4 字节：
 *                          neighborSysid u8 + rssi i8 + linkQuality u8 + reserved u8
 * </pre>
 * 内嵌 {@link NeighborInfo} record 承载每个邻居项。
 * CRC_EXTRA = 237。
 */
public final class MeshNeighborTableMsg extends MavlinkMessage {

    public static final int ID = 454;
    /** 固定头部长度。 */
    public static final int HEADER_LEN = 6;
    /** 每邻居项长度。 */
    public static final int NEIGHBOR_ENTRY_LEN = 4;
    /** 邻居数上限。 */
    public static final int MAX_NEIGHBORS = 255;
    /** LEN 字段填 -1 表示可变长度。 */
    public static final int LEN = -1;
    public static final int CRC_EXTRA = 237;

    /** 内嵌邻居项：sysid + RSSI(dBm) + LinkQuality.ordinal()。 */
    public record NeighborInfo(int sysid, int rssiDbm, int linkQualityOrdinal) {
        /** 编码到 buf 的指定偏移（4 字节）。 */
        void encode(byte[] buf, int offset) {
            PayloadCodec.putU8(buf, offset, sysid);
            PayloadCodec.putI8(buf, offset + 1, rssiDbm);
            PayloadCodec.putU8(buf, offset + 2, linkQualityOrdinal);
            PayloadCodec.putU8(buf, offset + 3, 0);
        }

        /** 从 buf 的指定偏移解码（4 字节）。 */
        static NeighborInfo decode(ByteBuffer b, int offset) {
            return new NeighborInfo(
                    PayloadCodec.u8(b, offset),
                    PayloadCodec.i8(b, offset + 1),
                    PayloadCodec.u8(b, offset + 2));
        }
    }

    public final int sysid;
    public final long timestamp;       // ms
    public final List<NeighborInfo> neighbors;

    public MeshNeighborTableMsg(int sysid, long timestamp, List<NeighborInfo> neighbors) {
        this.sysid = sysid;
        this.timestamp = timestamp;
        this.neighbors = neighbors == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(neighbors));
    }

    /** 邻居数。 */
    public int neighborCount() {
        return neighbors.size();
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        int n = Math.min(neighbors.size(), MAX_NEIGHBORS);
        byte[] buf = PayloadCodec.alloc(HEADER_LEN + n * NEIGHBOR_ENTRY_LEN);
        PayloadCodec.putU8(buf, 0, sysid);
        PayloadCodec.putU8(buf, 1, n);
        PayloadCodec.putU32(buf, 2, timestamp);
        for (int i = 0; i < n; i++) {
            neighbors.get(i).encode(buf, HEADER_LEN + i * NEIGHBOR_ENTRY_LEN);
        }
        return buf;
    }

    /** 从帧解码；payload 短于所需时容忍（缺失字段填 0 / 截断邻居列表）。 */
    public static MeshNeighborTableMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        int sysid = PayloadCodec.u8(b, 0);
        int declaredCount = len > 1 ? PayloadCodec.u8(b, 1) : 0;
        long timestamp = len > 5 ? PayloadCodec.u32(b, 2) : 0L;
        // 实际可解码的邻居数：取声明值与可用字节计算值的较小者
        int available = Math.max(0, (len - HEADER_LEN) / NEIGHBOR_ENTRY_LEN);
        int n = Math.min(declaredCount, available);
        List<NeighborInfo> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(NeighborInfo.decode(b, HEADER_LEN + i * NEIGHBOR_ENTRY_LEN));
        }
        return new MeshNeighborTableMsg(sysid, timestamp, list);
    }

    @Override
    public String toString() {
        return "MeshNeighborTableMsg{sysid=" + sysid + ", neighbors=" + neighbors.size()
                + ", ts=" + timestamp + "}";
    }
}