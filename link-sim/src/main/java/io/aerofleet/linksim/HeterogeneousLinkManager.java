package io.aerofleet.linksim;

import io.aerofleet.mavlink.MavlinkFrame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异构链路管理器（FR-01 异构链路桥接）。
 * <p>
 * 管理网络中所有桥接节点的注册与跨链路路由决策。当源节点与目标节点使用不同通信介质时，
 * 通过桥接节点实现跨介质帧转发，保证异构链路节点间可达。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>{@link #registerBridge} — 注册桥接节点及其支持的通信介质</li>
 *   <li>{@link #routeAcrossLinks} — 跨链路路由：找到从源到目标的桥接路径</li>
 *   <li>{@link #getBridgeNodes} — 查询支持指定介质转换的桥接节点</li>
 * </ul>
 *
 * <h3>路由策略</h3>
 * <ol>
 *   <li>若源与目标在同一介质上，直接返回（无需桥接）</li>
 *   <li>若存在单跳桥接节点（同时支持源介质和目标介质），优先选择</li>
 *   <li>若无单跳桥接，尝试两跳桥接（经中间介质中转）</li>
 *   <li>选择桥接路径时，优先选择总延迟最小的路径</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * 使用 ConcurrentHashMap 存储桥接节点注册信息，支持并发注册与查询。
 */
public final class HeterogeneousLinkManager {

    /** 桥接节点注册表：sysid → 桥接节点 */
    private final ConcurrentHashMap<Integer, LinkBridgeNode> bridgeNodes = new ConcurrentHashMap<>();
    /** 节点介质映射：sysid → 该节点使用的通信介质 */
    private final ConcurrentHashMap<Integer, LinkType> nodeLinkTypes = new ConcurrentHashMap<>();

    /**
     * 注册桥接节点（FR-01）。
     * <p>
     * 将桥接节点加入注册表，记录其支持的通信介质。
     *
     * @param sysid          桥接节点系统 ID
     * @param supportedLinks 该桥接节点支持的通信介质数组
     * @return 注册成功的桥接节点实例
     */
    public LinkBridgeNode registerBridge(int sysid, LinkType[] supportedLinks) {
        LinkBridgeNode node = new LinkBridgeNode(sysid, supportedLinks);
        bridgeNodes.put(sysid, node);
        return node;
    }

    /**
     * 注册桥接节点（含网段信息，FR-19 跨网段转发）。
     *
     * @param sysid          桥接节点系统 ID
     * @param supportedLinks 支持的通信介质数组
     * @param segments       监听的网段列表
     * @return 注册成功的桥接节点实例
     */
    public LinkBridgeNode registerBridge(int sysid, LinkType[] supportedLinks, String[] segments) {
        LinkBridgeNode node = new LinkBridgeNode(sysid, supportedLinks, segments);
        bridgeNodes.put(sysid, node);
        return node;
    }

    /**
     * 注销桥接节点。
     *
     * @param sysid 桥接节点系统 ID
     * @return 被移除的桥接节点，不存在则 null
     */
    public LinkBridgeNode unregisterBridge(int sysid) {
        return bridgeNodes.remove(sysid);
    }

    /**
     * 注册普通节点的通信介质类型。
     * <p>
     * 用于路由决策时判断源/目标节点使用的介质。
     *
     * @param sysid    节点系统 ID
     * @param linkType 该节点使用的通信介质
     */
    public void registerNodeLink(int sysid, LinkType linkType) {
        nodeLinkTypes.put(sysid, linkType);
    }

    /**
     * 查询节点的通信介质类型。
     *
     * @param sysid 节点系统 ID
     * @return 该节点使用的通信介质，未注册则 null
     */
    public LinkType getNodeLinkType(int sysid) {
        return nodeLinkTypes.get(sysid);
    }

    /**
     * 查询支持指定介质转换的桥接节点（FR-01）。
     * <p>
     * 返回所有同时支持 from 和 to 两种介质的桥接节点。
     *
     * @param from 源通信介质
     * @param to   目标通信介质
     * @return 支持该转换的桥接节点列表（可能为空）
     */
    public List<LinkBridgeNode> getBridgeNodes(LinkType from, LinkType to) {
        List<LinkBridgeNode> result = new ArrayList<>();
        for (LinkBridgeNode node : bridgeNodes.values()) {
            if (node.supportsBridge(from, to)) {
                result.add(node);
            }
        }
        return result;
    }

    /**
     * 跨链路路由（FR-01）。
     * <p>
     * 从源节点到目标节点，找到最优的跨介质桥接路径并执行帧转发。
     *
     * <h3>路由逻辑</h3>
     * <ol>
     *   <li>查询源/目标节点的介质类型（通过 {@link #nodeLinkTypes}）</li>
     *   <li>若同介质，返回直接路由结果（无需桥接）</li>
     *   <li>查找单跳桥接节点（同时支持源/目标介质）</li>
     *   <li>若无单跳，查找两跳桥接路径（源介质 → 中间介质 → 目标介质）</li>
     *   <li>选择延迟最小的路径执行转发</li>
     * </ol>
     *
     * @param sourceSysid 源节点系统 ID
     * @param targetSysid 目标节点系统 ID
     * @param frame       待路由的 MAVLink 帧
     * @return 路由结果，包含桥接路径与转发记录；不可达则返回 null
     */
    public RouteResult routeAcrossLinks(int sourceSysid, int targetSysid, MavlinkFrame frame) {
        LinkType sourceLink = nodeLinkTypes.get(sourceSysid);
        LinkType targetLink = nodeLinkTypes.get(targetSysid);

        // 源或目标介质未知，无法路由
        if (sourceLink == null || targetLink == null) {
            return null;
        }

        // 同介质：无需桥接
        if (sourceLink == targetLink) {
            return new RouteResult(sourceSysid, targetSysid, frame,
                    sourceLink, targetLink, Collections.emptyList(), 0, true);
        }

        // 尝试单跳桥接
        List<LinkBridgeNode> singleHopBridges = getBridgeNodes(sourceLink, targetLink);
        if (!singleHopBridges.isEmpty()) {
            // 选择延迟最小的桥接节点（跨介质延迟固定，选第一个即可）
            LinkBridgeNode bridge = singleHopBridges.get(0);
            LinkBridgeNode.BridgedFrame bridged = bridge.bridgeFrame(frame, sourceLink, targetLink);
            if (bridged != null) {
                List<LinkBridgeNode.BridgedFrame> hops = new ArrayList<>();
                hops.add(bridged);
                long totalDelay = bridged.extraDelayMs;
                return new RouteResult(sourceSysid, targetSysid, frame,
                        sourceLink, targetLink, hops, totalDelay, true);
            }
        }

        // 尝试两跳桥接：源介质 → 中间介质 → 目标介质
        RouteResult twoHopResult = tryTwoHopRoute(sourceSysid, targetSysid, frame,
                sourceLink, targetLink);
        if (twoHopResult != null) {
            return twoHopResult;
        }

        // 不可达
        return null;
    }

    /**
     * 尝试两跳桥接路由。
     * <p>
     * 遍历所有中间介质，查找源→中间和中间→目标都有桥接节点的路径。
     * 选择总延迟最小的路径。
     */
    private RouteResult tryTwoHopRoute(int sourceSysid, int targetSysid, MavlinkFrame frame,
                                       LinkType sourceLink, LinkType targetLink) {
        RouteResult bestResult = null;
        long minDelay = Long.MAX_VALUE;

        for (LinkType intermediate : LinkType.values()) {
            if (intermediate == sourceLink || intermediate == targetLink) {
                continue;
            }

            // 查找源→中间的桥接节点
            List<LinkBridgeNode> firstHopBridges = getBridgeNodes(sourceLink, intermediate);
            if (firstHopBridges.isEmpty()) {
                continue;
            }

            // 查找中间→目标的桥接节点
            List<LinkBridgeNode> secondHopBridges = getBridgeNodes(intermediate, targetLink);
            if (secondHopBridges.isEmpty()) {
                continue;
            }

            LinkBridgeNode firstBridge = firstHopBridges.get(0);
            LinkBridgeNode secondBridge = secondHopBridges.get(0);

            // 执行两跳转发
            LinkBridgeNode.BridgedFrame firstHop = firstBridge.bridgeFrame(frame, sourceLink, intermediate);
            if (firstHop == null) {
                continue;
            }
            LinkBridgeNode.BridgedFrame secondHop = secondBridge.bridgeFrame(frame, intermediate, targetLink);
            if (secondHop == null) {
                continue;
            }

            long totalDelay = firstHop.extraDelayMs + secondHop.extraDelayMs;
            if (totalDelay < minDelay) {
                minDelay = totalDelay;
                List<LinkBridgeNode.BridgedFrame> hops = new ArrayList<>();
                hops.add(firstHop);
                hops.add(secondHop);
                bestResult = new RouteResult(sourceSysid, targetSysid, frame,
                        sourceLink, targetLink, hops, totalDelay, true);
            }
        }

        return bestResult;
    }

    /**
     * 获取所有已注册的桥接节点。
     */
    public Collection<LinkBridgeNode> getAllBridges() {
        return Collections.unmodifiableCollection(bridgeNodes.values());
    }

    /**
     * 获取已注册桥接节点数量。
     */
    public int getBridgeCount() {
        return bridgeNodes.size();
    }

    /**
     * 路由结果：包含源/目标信息、桥接路径与总延迟。
     * <p>
     * 不可变值对象，描述一次跨链路路由的完整信息。
     */
    public static final class RouteResult {
        /** 源节点系统 ID */
        public final int sourceSysid;
        /** 目标节点系统 ID */
        public final int targetSysid;
        /** 原始 MAVLink 帧 */
        public final MavlinkFrame frame;
        /** 源通信介质 */
        public final LinkType sourceLink;
        /** 目标通信介质 */
        public final LinkType targetLink;
        /** 桥接跳列表（每跳一个 BridgedFrame） */
        public final List<LinkBridgeNode.BridgedFrame> hops;
        /** 总跨介质延迟（毫秒） */
        public final long totalDelayMs;
        /** 是否可达 */
        public final boolean reachable;

        public RouteResult(int sourceSysid, int targetSysid, MavlinkFrame frame,
                           LinkType sourceLink, LinkType targetLink,
                           List<LinkBridgeNode.BridgedFrame> hops, long totalDelayMs,
                           boolean reachable) {
            this.sourceSysid = sourceSysid;
            this.targetSysid = targetSysid;
            this.frame = frame;
            this.sourceLink = sourceLink;
            this.targetLink = targetLink;
            this.hops = Collections.unmodifiableList(hops);
            this.totalDelayMs = totalDelayMs;
            this.reachable = reachable;
        }

        /** 跳数（0=同介质直达，1=单跳桥接，2=两跳桥接） */
        public int hopCount() {
            return hops.size();
        }

        @Override
        public String toString() {
            return "RouteResult{" + sourceSysid + "(" + sourceLink + ") -> "
                    + targetSysid + "(" + targetLink + ")"
                    + ", hops=" + hopCount()
                    + ", delay=" + totalDelayMs + "ms"
                    + ", reachable=" + reachable + "}";
        }
    }
}