package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * CLUSTER_FORMATION (msgId=481, LEN=18) —— NexusSky P2 灾害应急通讯组网扩展消息。
 * <p>
 * 分簇结构通知：告知 mesh 节点当前簇 ID、簇头、成员列表与簇半径。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型      单位/精度
 * 0    timestamp         u32      时间戳（ms）
 * 4    clusterRadius     u16      簇半径（m）
 * 6    clusterId         u8       簇 ID
 * 7    clusterHead       u8       簇头 sysid
 * 8    members           u8[8]    成员 sysid 列表（最多8个，0=空位）
 * 16   memberCount       u8       成员数量
 * 17   reserved          u8       保留（0填充）
 * </pre>
 * CRC_EXTRA = 265（P2 自定义扩展）。
 */
public final class ClusterFormationMsg extends MavlinkMessage {

    public static final int ID = 481;
    public static final int LEN = 18;
    public static final int CRC_EXTRA = 265;
    private static final int MEMBERS_LEN = 8;

    public final long timestamp;      // ms
    public final int clusterRadius;   // m
    public final int clusterId;       // 簇 ID
    public final int clusterHead;     // 簇头 sysid
    public final int[] members;       // 成员 sysid 列表（最多8个）
    public final int memberCount;     // 成员数量

    public ClusterFormationMsg(long timestamp, int clusterRadius, int clusterId,
                               int clusterHead, int[] members, int memberCount) {
        if (members == null || members.length != MEMBERS_LEN) {
            throw new IllegalArgumentException("members must have length " + MEMBERS_LEN);
        }
        if (memberCount < 0 || memberCount > MEMBERS_LEN) {
            throw new IllegalArgumentException("memberCount must be in [0, " + MEMBERS_LEN + "]: " + memberCount);
        }
        this.timestamp = timestamp;
        this.clusterRadius = clusterRadius;
        this.clusterId = clusterId;
        this.clusterHead = clusterHead;
        this.members = members;
        this.memberCount = memberCount;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU32(buf, 0, timestamp);
        PayloadCodec.putU16(buf, 4, clusterRadius);
        PayloadCodec.putU8(buf, 6, clusterId);
        PayloadCodec.putU8(buf, 7, clusterHead);
        for (int i = 0; i < MEMBERS_LEN; i++) {
            PayloadCodec.putU8(buf, 8 + i, members[i]);
        }
        PayloadCodec.putU8(buf, 16, memberCount);
        PayloadCodec.putU8(buf, 17, 0); // reserved
        return buf;
    }

    public static ClusterFormationMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        int[] members = new int[MEMBERS_LEN];
        for (int i = 0; i < MEMBERS_LEN; i++) {
            members[i] = (len > 8 + i) ? PayloadCodec.u8(b, 8 + i) : 0;
        }
        return new ClusterFormationMsg(
                PayloadCodec.u32(b, 0),
                len > 5 ? PayloadCodec.u16(b, 4) : 0,
                len > 6 ? PayloadCodec.u8(b, 6) : 0,
                len > 7 ? PayloadCodec.u8(b, 7) : 0,
                members,
                len > 16 ? PayloadCodec.u8(b, 16) : 0);
    }

    @Override
    public String toString() {
        return "ClusterFormationMsg{clusterId=" + clusterId + ", head=" + clusterHead
                + ", members=" + memberCount + ", radius=" + clusterRadius
                + "m, ts=" + timestamp + "}";
    }
}