package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HIERARCHICAL_ROUTE_DECISION (msgId=461, LEN=32) —— NexusSky M7 层级路由决策上报消息。
 * <p>
 * 承载层级行由决策结果：源层级、目标层级、选定路径、决策原因、估计延迟（FR-5.3）。
 * 由 drone-sim {@code HierarchicalRouter} 产出并 UDP 上报到 cloud-backend。
 * <p>
 * 字段布局（小端）：
 * <pre>
 * 偏移  字段              类型   说明
 * 0    sourceLayer     u8     源层级（0=L0, 1=L1, 2=L2, 3=L3, 4=L4）
 * 1    targetLayer    u8     目标层级（0-4）
 * 2    chosenLayer    u8     选定路径层级（0-4，全不可达时 255）
 * 3    estimatedDelayMs u16  估计端到端延迟（ms，全不可达时 65535）
 * 5    pathHopCount   u8     路径跳数
 * 6    pathNodes      u8[8]  路径节点 ID 列表（最多 8 跳，不足补 0）
 * 14   strategy        u8     切换策略（0=NEAR_FIRST,1=DELAY,2=BANDWIDTH,3=RELIABILITY）
 * 15   reasonLen       u8     决策原因长度
 * 16   reasonChars     char[14] 决策原因（ASCII，不足补 0）
 * 30   timestamp       u32    时间戳（ms）
 * </pre>
 * CRC_EXTRA = 240。
 */
public final class HierarchicalRouteDecisionMsg extends MavlinkMessage {

    public static final int ID = 461;
    public static final int LEN = 34;
    public static final int CRC_EXTRA = 240;

    /** 路径节点最大数量。 */
    public static final int MAX_PATH_NODES = 8;
    /** 决策原因最大长度。 */
    public static final int MAX_REASON_LEN = 14;
    /** 全不可达时的 chosenLayer 哨兵值。 */
    public static final int NO_PATH_LAYER = 255;
    /** 全不可达时的 estimatedDelayMs 哨兵值。 */
    public static final int NO_PATH_DELAY = 65535;

    /** 切换策略枚举序数。 */
    public static final int STRATEGY_NEAR_FIRST = 0;
    public static final int STRATEGY_DELAY_OPTIMAL = 1;
    public static final int STRATEGY_BANDWIDTH_OPTIMAL = 2;
    public static final int STRATEGY_RELIABILITY_OPTIMAL = 3;

    public final int sourceLayer;        // 0-4
    public final int targetLayer;        // 0-4
    public final int chosenLayer;        // 0-4 或 255
    public final int estimatedDelayMs;   // 0-500 或 65535
    public final int pathHopCount;       // 跳数
    public final List<Integer> pathNodes; // 节点 ID 列表
    public final int strategy;           // 0-3
    public final String decisionReason;  // 可读字符串
    public final long timestamp;         // ms

    public HierarchicalRouteDecisionMsg(int sourceLayer, int targetLayer, int chosenLayer,
                                        int estimatedDelayMs, List<Integer> pathNodes,
                                        int strategy, String decisionReason, long timestamp) {
        this.sourceLayer = sourceLayer & 0xFF;
        this.targetLayer = targetLayer & 0xFF;
        this.chosenLayer = chosenLayer & 0xFF;
        this.estimatedDelayMs = estimatedDelayMs & 0xFFFF;
        List<Integer> nodes = pathNodes == null
                ? Collections.emptyList()
                : new ArrayList<>(pathNodes);
        if (nodes.size() > MAX_PATH_NODES) {
            nodes = nodes.subList(0, MAX_PATH_NODES);
        }
        this.pathNodes = Collections.unmodifiableList(nodes);
        this.pathHopCount = this.pathNodes.size();
        this.strategy = strategy & 0xFF;
        this.decisionReason = decisionReason == null ? "" : decisionReason;
        this.timestamp = timestamp;
    }

    /** 是否全层级不可达。 */
    public boolean isNoPath() {
        return chosenLayer == NO_PATH_LAYER;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putU8(buf, 0, sourceLayer);
        PayloadCodec.putU8(buf, 1, targetLayer);
        PayloadCodec.putU8(buf, 2, chosenLayer);
        PayloadCodec.putU16(buf, 3, estimatedDelayMs);
        PayloadCodec.putU8(buf, 5, pathHopCount);
        for (int i = 0; i < MAX_PATH_NODES; i++) {
            int nodeId = i < pathNodes.size() ? pathNodes.get(i) : 0;
            PayloadCodec.putU8(buf, 6 + i, nodeId);
        }
        PayloadCodec.putU8(buf, 14, strategy);
        // reason 按 UTF-8 字节编码，截断到 MAX_REASON_LEN 字节且在字符边界安全
        byte[] reasonBytes = decisionReason.getBytes(StandardCharsets.UTF_8);
        if (reasonBytes.length > MAX_REASON_LEN) {
            int cut = MAX_REASON_LEN;
            while (cut > 0 && (reasonBytes[cut] & 0xC0) == 0x80) {
                cut--;
            }
            reasonBytes = java.util.Arrays.copyOf(reasonBytes, cut);
        }
        PayloadCodec.putU8(buf, 15, reasonBytes.length);
        System.arraycopy(reasonBytes, 0, buf, 16, reasonBytes.length);
        for (int i = reasonBytes.length; i < MAX_REASON_LEN; i++) {
            buf[16 + i] = 0;
        }
        PayloadCodec.putU32(buf, 30, timestamp);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static HierarchicalRouteDecisionMsg decode(MavlinkFrame f) {
        ByteBuffer b = le(f.getPayload());
        int len = f.getPayloadLength();
        int sourceLayer = PayloadCodec.u8(b, 0);
        int targetLayer = len > 1 ? PayloadCodec.u8(b, 1) : 0;
        int chosenLayer = len > 2 ? PayloadCodec.u8(b, 2) : 0;
        int estimatedDelayMs = len > 4 ? PayloadCodec.u16(b, 3) : 0;
        int pathHopCount = len > 5 ? PayloadCodec.u8(b, 5) : 0;
        List<Integer> nodes = new ArrayList<>(MAX_PATH_NODES);
        int availableNodes = Math.min(pathHopCount, MAX_PATH_NODES);
        for (int i = 0; i < availableNodes; i++) {
            int offset = 6 + i;
            if (len > offset) {
                nodes.add(PayloadCodec.u8(b, offset));
            }
        }
        int strategy = len > 14 ? PayloadCodec.u8(b, 14) : 0;
        int reasonLen = len > 15 ? PayloadCodec.u8(b, 15) : 0;
        int actualReasonLen = Math.min(reasonLen, MAX_REASON_LEN);
        String reason = len > 16 ? PayloadCodec.chars(b, 16, actualReasonLen) : "";
        long timestamp = len > 33 ? PayloadCodec.u32(b, 30) : 0L;
        return new HierarchicalRouteDecisionMsg(sourceLayer, targetLayer, chosenLayer,
                estimatedDelayMs, nodes, strategy, reason, timestamp);
    }

    @Override
    public String toString() {
        return "HierarchicalRouteDecisionMsg{src=L" + sourceLayer + " -> tgt=L" + targetLayer
                + ", chosen=L" + (chosenLayer == NO_PATH_LAYER ? "NONE" : chosenLayer)
                + ", delay=" + (estimatedDelayMs == NO_PATH_DELAY ? -1 : estimatedDelayMs) + "ms"
                + ", path=" + pathNodes + ", strategy=" + strategy
                + ", reason=\"" + decisionReason + "\", ts=" + timestamp + "}";
    }
}