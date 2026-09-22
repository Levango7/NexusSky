package io.aerofleet.linksim;

import io.aerofleet.mavlink.MavlinkFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 链路桥接节点（FR-01 异构链路桥接）。
 * <p>
 * 同时监听多个通信介质（WiFi/LTE/LoRa/Satellite），在不同介质间转发 MAVLink 帧。
 * 桥接节点是异构链路互通的关键枢纽——使用不同通信介质的节点通过桥接节点实现跨介质可达。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>维护支持的通信介质列表（至少 2 种才能桥接）</li>
 *   <li>{@link #bridgeFrame} — 跨介质帧转发，自动计算额外延迟</li>
 *   <li>记录转发统计（各介质间转发计数与延迟累计）</li>
 * </ul>
 *
 * <h3>跨介质转发延迟（DFX 6.1.4）</h3>
 * <ul>
 *   <li>WiFi → LoRa：100ms 额外延迟</li>
 *   <li>WiFi → Satellite：200ms 额外延迟</li>
 *   <li>其他组合：按 {@link LinkType#crossMediumDelay} 计算</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 转发队列与统计使用并发数据结构，支持多线程并发调用。
 */
public final class LinkBridgeNode {

    /** 桥接节点系统 ID（对应 MAVLink sysid） */
    public final int sysid;
    /** 该桥接节点支持的通信介质集合 */
    public final Set<LinkType> supportedLinks;
    /** 桥接节点监听的网段列表（FR-19 跨网段转发） */
    public final Set<String> listenedSegments;

    /** 转发队列：待投递的桥接帧 */
    private final ConcurrentLinkedQueue<BridgedFrame> forwardQueue = new ConcurrentLinkedQueue<>();
    /** 转发统计：按 "fromType->toType" 键计数 */
    private final ConcurrentHashMap<String, AtomicLong> forwardStats = new ConcurrentHashMap<>();
    /** 延迟统计：按 "fromType->toType" 键累计延迟 */
    private final ConcurrentHashMap<String, AtomicLong> delayStats = new ConcurrentHashMap<>();

    /**
     * 创建链路桥接节点。
     *
     * @param sysid          桥接节点系统 ID
     * @param supportedLinks 支持的通信介质数组（至少 2 种）
     * @param segments       监听的网段列表（可为空，FR-19 跨网段转发用）
     */
    public LinkBridgeNode(int sysid, LinkType[] supportedLinks, String[] segments) {
        if (supportedLinks == null || supportedLinks.length < 2) {
            throw new IllegalArgumentException(
                    "桥接节点至少需要支持 2 种通信介质，当前: "
                            + (supportedLinks == null ? 0 : supportedLinks.length));
        }
        this.sysid = sysid;
        Set<LinkType> linkSet = new HashSet<>();
        Collections.addAll(linkSet, supportedLinks);
        this.supportedLinks = Collections.unmodifiableSet(linkSet);
        Set<String> segSet = new HashSet<>();
        if (segments != null) {
            Collections.addAll(segSet, segments);
        }
        this.listenedSegments = Collections.unmodifiableSet(segSet);
    }

    /**
     * 创建链路桥接节点（不带网段，仅异构链路桥接）。
     *
     * @param sysid          桥接节点系统 ID
     * @param supportedLinks 支持的通信介质数组（至少 2 种）
     */
    public LinkBridgeNode(int sysid, LinkType[] supportedLinks) {
        this(sysid, supportedLinks, null);
    }

    /**
     * 跨介质帧转发（FR-01）。
     * <p>
     * 将帧从源介质转发到目标介质，自动计算跨介质额外延迟。
     * 帧内容保持不变（透明转发），仅追加桥接延迟。
     *
     * @param frame    待转发的 MAVLink 帧
     * @param fromType 源通信介质
     * @param toType   目标通信介质
     * @return 桥接后的帧（含额外延迟信息），若介质不支持则返回 null
     */
    public BridgedFrame bridgeFrame(MavlinkFrame frame, LinkType fromType, LinkType toType) {
        // 校验桥接节点支持源介质和目标介质
        if (!supportedLinks.contains(fromType)) {
            return null;
        }
        if (!supportedLinks.contains(toType)) {
            return null;
        }
        if (fromType == toType) {
            // 同介质不需要桥接
            return null;
        }

        long extraDelay = LinkType.crossMediumDelay(fromType, toType);
        BridgedFrame bridged = new BridgedFrame(frame, fromType, toType, extraDelay, sysid);

        // 入队等待投递
        forwardQueue.add(bridged);

        // 更新统计
        String key = fromType.name() + "->" + toType.name();
        forwardStats.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        delayStats.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(extraDelay);

        return bridged;
    }

    /**
     * 从转发队列中取出一个待投递的桥接帧。
     *
     * @return 下一个待投递的桥接帧，队列空则返回 null
     */
    public BridgedFrame pollBridgedFrame() {
        return forwardQueue.poll();
    }

    /**
     * 查询该桥接节点是否支持指定的介质转换路径。
     *
     * @param from 源介质
     * @param to   目标介质
     * @return true 表示支持该转换路径
     */
    public boolean supportsBridge(LinkType from, LinkType to) {
        return supportedLinks.contains(from) && supportedLinks.contains(to) && from != to;
    }

    /**
     * 获取该桥接节点支持的所有介质转换路径。
     *
     * @return 转换路径列表，每项为 [fromType, toType]
     */
    public List<LinkType[]> getBridgePaths() {
        List<LinkType[]> paths = new ArrayList<>();
        for (LinkType from : supportedLinks) {
            for (LinkType to : supportedLinks) {
                if (from != to) {
                    paths.add(new LinkType[]{from, to});
                }
            }
        }
        return paths;
    }

    /**
     * 获取指定介质转换路径的转发次数。
     *
     * @param from 源介质
     * @param to   目标介质
     * @return 转发次数，无记录则 0
     */
    public long getForwardCount(LinkType from, LinkType to) {
        String key = from.name() + "->" + to.name();
        AtomicLong count = forwardStats.get(key);
        return count == null ? 0 : count.get();
    }

    /**
     * 获取指定介质转换路径的累计延迟。
     *
     * @param from 源介质
     * @param to   目标介质
     * @return 累计延迟（毫秒），无记录则 0
     */
    public long getAccumulatedDelay(LinkType from, LinkType to) {
        String key = from.name() + "->" + to.name();
        AtomicLong delay = delayStats.get(key);
        return delay == null ? 0 : delay.get();
    }

    /**
     * 判断该桥接节点是否监听指定网段（FR-19 跨网段转发）。
     *
     * @param segment 网段标识（如 "192.168.1.x"）
     * @return true 表示该节点监听此网段
     */
    public boolean listensToSegment(String segment) {
        return listenedSegments.contains(segment);
    }

    /**
     * 桥接后的帧记录：包含原始帧、源/目标介质、额外延迟和桥接节点 ID。
     * <p>
     * 不可变值对象，用于跨介质转发的中间表示。
     */
    public static final class BridgedFrame {
        /** 原始 MAVLink 帧（内容不变，透明转发） */
        public final MavlinkFrame frame;
        /** 源通信介质 */
        public final LinkType fromType;
        /** 目标通信介质 */
        public final LinkType toType;
        /** 跨介质额外延迟（毫秒） */
        public final long extraDelayMs;
        /** 桥接节点系统 ID */
        public final int bridgeSysid;

        public BridgedFrame(MavlinkFrame frame, LinkType fromType, LinkType toType,
                            long extraDelayMs, int bridgeSysid) {
            this.frame = frame;
            this.fromType = fromType;
            this.toType = toType;
            this.extraDelayMs = extraDelayMs;
            this.bridgeSysid = bridgeSysid;
        }

        @Override
        public String toString() {
            return "BridgedFrame{" + fromType + "->" + toType
                    + ", extraDelay=" + extraDelayMs + "ms"
                    + ", bridge=" + bridgeSysid
                    + ", msgId=" + frame.getMessageId() + "}";
        }
    }
}