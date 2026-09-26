package io.aerofleet.linksim;

import io.aerofleet.mavlink.MavlinkFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨网段转发器（FR-19 跨网段 NAT 穿透）。
 * <p>
 * 当 mesh 节点分布在不同网段时，通过桥接节点实现跨网段通信——
 * 桥接节点同时监听多个网段，在网段间转发帧，实现 NAT 穿透。
 *
 * <h3>典型场景</h3>
 * <pre>
 *   节点A (192.168.1.x) ←→ 桥接节点 (双网段: 192.168.1.x + 10.0.0.x) ←→ 节点B (10.0.0.x)
 * </pre>
 * 桥接节点同时监听两个网段，A 发出的帧经桥接节点转发到 B 所在的网段，反之亦然。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>{@link #forwardToSegment} — 将帧转发到指定目标网段</li>
 *   <li>{@link #findBridgeForSegments} — 查找连接两个网段的桥接节点</li>
 *   <li>{@link #registerSegmentNode} — 注册节点所在的网段</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 使用 ConcurrentHashMap 存储网段映射，支持并发注册与查询。
 */
public final class CrossSegmentForwarder {

    /** 网段节点映射：segment → 该网段下的节点 sysid 列表 */
    private final ConcurrentHashMap<String, Set<Integer>> segmentNodes = new ConcurrentHashMap<>();
    /** 节点网段映射：sysid → 该节点所在网段 */
    private final ConcurrentHashMap<Integer, String> nodeSegments = new ConcurrentHashMap<>();
    /** 桥接节点列表（支持跨网段转发的节点） */
    private final List<LinkBridgeNode> bridgeNodes = new ArrayList<>();
    /** 转发统计 */
    private final ConcurrentHashMap<String, Long> forwardStats = new ConcurrentHashMap<>();

    /**
     * 注册桥接节点为跨网段转发器。
     * <p>
     * 桥接节点必须监听至少 2 个网段才能用于跨网段转发。
     *
     * @param bridge 桥接节点（需已配置 listenedSegments）
     */
    public void registerBridgeNode(LinkBridgeNode bridge) {
        if (bridge.listenedSegments.size() >= 2) {
            bridgeNodes.add(bridge);
        }
    }

    /**
     * 注册节点所在的网段（FR-19）。
     *
     * @param sysid   节点系统 ID
     * @param segment 网段标识（如 "192.168.1.x"）
     */
    public void registerSegmentNode(int sysid, String segment) {
        nodeSegments.put(sysid, segment);
        segmentNodes.computeIfAbsent(segment, k -> ConcurrentHashMap.newKeySet()).add(sysid);
    }

    /**
     * 查找连接两个网段的桥接节点（FR-19）。
     * <p>
     * 返回所有同时监听 sourceSegment 和 targetSegment 的桥接节点。
     *
     * @param sourceSegment 源网段
     * @param targetSegment 目标网段
     * @return 支持该跨网段转发的桥接节点列表
     */
    public List<LinkBridgeNode> findBridgeForSegments(String sourceSegment, String targetSegment) {
        List<LinkBridgeNode> result = new ArrayList<>();
        for (LinkBridgeNode bridge : bridgeNodes) {
            if (bridge.listensToSegment(sourceSegment) && bridge.listensToSegment(targetSegment)
                    && !sourceSegment.equals(targetSegment)) {
                result.add(bridge);
            }
        }
        return result;
    }

    /**
     * 跨网段转发帧（FR-19）。
     * <p>
     * 将帧从源网段转发到目标网段，通过同时监听两个网段的桥接节点实现 NAT 穿透。
     *
     * @param frame          待转发的 MAVLink 帧
     * @param targetSegment  目标网段标识
     * @return 转发结果，包含桥接节点与目标网段信息；无可达桥接则返回 null
     */
    public ForwardResult forwardToSegment(MavlinkFrame frame, String targetSegment) {
        // 查找源节点所在网段
        int sourceSysid = frame.getSystemId();
        String sourceSegment = nodeSegments.get(sourceSysid);
        if (sourceSegment == null) {
            return null;
        }

        // 同网段无需转发
        if (sourceSegment.equals(targetSegment)) {
            return new ForwardResult(frame, sourceSegment, targetSegment,
                    null, 0, true, "同网段直达");
        }

        // 查找连接两个网段的桥接节点
        List<LinkBridgeNode> bridges = findBridgeForSegments(sourceSegment, targetSegment);
        if (bridges.isEmpty()) {
            return null;
        }

        // 选择第一个可用桥接节点转发
        LinkBridgeNode bridge = bridges.get(0);

        // 更新统计
        String statKey = sourceSegment + "->" + targetSegment;
        forwardStats.merge(statKey, 1L, Long::sum);

        return new ForwardResult(frame, sourceSegment, targetSegment,
                bridge, 0, true,
                "经桥接节点 " + bridge.sysid + " 跨网段转发");
    }

    /**
     * 获取指定网段下的所有节点。
     *
     * @param segment 网段标识
     * @return 该网段下的节点 sysid 集合
     */
    public Set<Integer> getNodesInSegment(String segment) {
        Set<Integer> nodes = segmentNodes.get(segment);
        return nodes == null ? Collections.emptySet() : Collections.unmodifiableSet(nodes);
    }

    /**
     * 获取节点所在网段。
     *
     * @param sysid 节点系统 ID
     * @return 网段标识，未注册则 null
     */
    public String getNodeSegment(int sysid) {
        return nodeSegments.get(sysid);
    }

    /**
     * 获取所有已注册网段。
     */
    public Set<String> getAllSegments() {
        return Collections.unmodifiableSet(segmentNodes.keySet());
    }

    /**
     * 获取跨网段转发统计。
     *
     * @param sourceSegment 源网段
     * @param targetSegment 目标网段
     * @return 转发次数，无记录则 0
     */
    public long getForwardCount(String sourceSegment, String targetSegment) {
        String key = sourceSegment + "->" + targetSegment;
        return forwardStats.getOrDefault(key, 0L);
    }

    /**
     * 获取已注册桥接节点数量。
     */
    public int getBridgeNodeCount() {
        return bridgeNodes.size();
    }

    /**
     * 跨网段转发结果。
     */
    public static final class ForwardResult {
        /** 原始 MAVLink 帧 */
        public final MavlinkFrame frame;
        /** 源网段 */
        public final String sourceSegment;
        /** 目标网段 */
        public final String targetSegment;
        /** 使用的桥接节点（同网段直达时为 null） */
        public final LinkBridgeNode bridge;
        /** 额外延迟（毫秒） */
        public final long extraDelayMs;
        /** 是否可达 */
        public final boolean reachable;
        /** 路径描述 */
        public final String pathDescription;

        public ForwardResult(MavlinkFrame frame, String sourceSegment, String targetSegment,
                             LinkBridgeNode bridge, long extraDelayMs, boolean reachable,
                             String pathDescription) {
            this.frame = frame;
            this.sourceSegment = sourceSegment;
            this.targetSegment = targetSegment;
            this.bridge = bridge;
            this.extraDelayMs = extraDelayMs;
            this.reachable = reachable;
            this.pathDescription = pathDescription;
        }

        @Override
        public String toString() {
            return "ForwardResult{" + sourceSegment + " -> " + targetSegment
                    + ", bridge=" + (bridge == null ? "none" : bridge.sysid)
                    + ", reachable=" + reachable
                    + ", path=" + pathDescription + "}";
        }
    }
}