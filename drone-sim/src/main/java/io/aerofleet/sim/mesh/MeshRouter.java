package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.MeshHeartbeatMsg;
import io.aerofleet.mavlink.messages.MeshNeighborTableMsg;
import io.aerofleet.mavlink.messages.MeshRouteErrorMsg;
import io.aerofleet.mavlink.messages.MeshRouteReplyMsg;
import io.aerofleet.mavlink.messages.MeshRouteRequestMsg;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import io.aerofleet.sim.SimLog;
import io.aerofleet.sim.comm.LoRaMavlinkTransport;
import io.aerofleet.sim.comm.LoRaTransportAdapter;
import io.aerofleet.sim.comm.MavlinkTransport;
import io.aerofleet.sim.comm.UdpTransportAdapter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * AODV-lite mesh 路由引擎核心（M5 应急 mesh，FR-01~22a）。
 * <p>
 * 实现按需建路（RREQ/RREP）、路由错误（RERR）、自愈重构（备份切换 + 重建路）、
 * HELLO 周期广播（邻居发现）、拓扑快照周期上报。
 * <p>
 * 线程模型：三张表（neighbors/routes/rreqCache）内部 ConcurrentHashMap，
 * 时间戳标记 volatile，自愈事务在 {@code synchronized(this)} 块内执行。
 * <p>
 * 生命周期：{@link #start()} → {@link #tick(long)} 周期驱动 → {@link #close()} 主动退出。
 */
public final class MeshRouter implements AutoCloseable {

    /** 发送结果枚举。 */
    public enum SendResult {
        SENT,              // 已按路由转发
        QUEUED_RREQ,       // 无路由，已发起 RREQ，数据缓存待路由建立
        DROPPED_NO_ROUTE,  // 无路由且无法发起 RREQ（如目标暂时不可达）
        DROPPED_HOP_LIMIT  // hopCount 耗尽或越界
    }

    /**
     * P1-fix(Major 8): 帧转发结果，携带递减后的 newHopCount 供调用方写回帧尾部。
     * DROPPED 时 newHopCount 无意义（取原值）。
     */
    public record ForwardOutcome(SendResult result, int newHopCount) {
        /** 便捷工厂：丢弃（newHopCount 保持原值）。 */
        public static ForwardOutcome dropped(SendResult r, int hopCount) {
            return new ForwardOutcome(r, hopCount);
        }
        /** 便捷工厂：已转发，携带递减后的 hopCount。 */
        public static ForwardOutcome sent(int newHopCount) {
            return new ForwardOutcome(SendResult.SENT, newHopCount);
        }
    }

    /** 拓扑事件类型（供 WebSocket 推送）。 */
    public enum TopologyEvent {
        NODE_JOINED, NODE_LEFT, LINK_UP, LINK_DOWN, ROUTE_CHANGED
    }

    /** 缓存的待发送数据（按需建路：RREQ 发出后等待 RREP 回传）。 */
    private record PendingSend(int targetSysid, byte[] payload, long createdAtMs) {}

    // ===== 核心字段 =====
    private final int selfSysid;
    private final MeshRouterConfig config;
    /** 通用传输层（UDP 或 LoRa），null 表示无网络 IO（单测用）。 */
    private final MavlinkTransport transport;
    private final NeighborTable neighbors;
    private final RouteTable routes;
    private final RreqCache rreqCache;
    private final AtomicInteger broadcastIdSeq = new AtomicInteger(0);
    private final AtomicInteger frameSeq = new AtomicInteger(0);

    // ===== 周期驱动时间戳 =====
    private volatile long lastHelloSentMs = 0;
    private volatile long lastTimeoutCheckMs = 0;
    private volatile long lastTopologyReportMs = 0;

    // ===== 待发送数据队列（按需建路） =====
    private final Deque<PendingSend> pendingSends = new ArrayDeque<>();

    // ===== 连续自愈失败保护（FR-16a） =====
    // P1-fix(Critical 1): 改为 ConcurrentHashMap，避免 onMeshHeartbeat（无 synchronized）
    // 与 onNeighborTimeout（synchronized）并发访问 HashMap 导致数据损坏或无限循环。
    private final Map<Integer, Integer> consecutiveHealFailures = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> temporarilyUnreachable = new ConcurrentHashMap<>();
    private static final int HEAL_FAILURE_THRESHOLD = 3;

    // P1-fix(Major 6): pendingSends 容量上限与过期清理，避免无界增长
    private static final int PENDING_SENDS_MAX_CAPACITY = 1000;
    private static final long PENDING_SENDS_TTL_MS = 30_000L;

    // ===== 节点状态（供 HELLO 组装） =====
    private volatile int latE7 = 0;
    private volatile int lonE7 = 0;
    private volatile int altMm = 0;
    private volatile int batteryPercent = 100;

    private volatile boolean closed = false;
    /** transport 是否由本 router 内部创建（createWithLoRa），close() 时需关闭。 */
    private final boolean ownsTransport;

    /**
     * 构造 MeshRouter，使用通用 {@link MavlinkTransport}（UDP 或 LoRa）。
     * <p>
     * 3d 集成：根据 {@link MeshRouterConfig#transportType} 选择传输层实现。
     * transport 为 null 时 sendFrame 静默跳过（单测用）。
     *
     * @param selfSysid 本节点 sysid（1-255）
     * @param config    路由配置
     * @param transport 通用传输层，null 表示无网络 IO
     */
    public MeshRouter(int selfSysid, MeshRouterConfig config, MavlinkTransport transport) {
        this(selfSysid, config, transport, false);
    }

    /**
     * 内部构造函数，指定 transport 所有权。
     *
     * @param ownsTransport true 表示 transport 由本 router 内部创建，close() 时需关闭
     */
    MeshRouter(int selfSysid, MeshRouterConfig config, MavlinkTransport transport, boolean ownsTransport) {
        this.selfSysid = selfSysid;
        this.config = config;
        this.transport = transport;
        this.ownsTransport = ownsTransport;
        this.neighbors = new NeighborTable();
        this.routes = new RouteTable(config.routeLifetimeMs);
        this.rreqCache = new RreqCache();
    }

    /**
     * 构造 MeshRouter，使用 {@link UdpMavlinkTransport}（向后兼容）。
     * <p>
     * 内部包装为 {@link UdpTransportAdapter}，mesh 组播地址作为默认发送目标。
     *
     * @param selfSysid 本节点 sysid
     * @param config    路由配置
     * @param transport UDP 传输，null 表示无网络 IO
     */
    public MeshRouter(int selfSysid, MeshRouterConfig config, UdpMavlinkTransport transport) {
        this(selfSysid, config,
                transport != null ? new UdpTransportAdapter(transport, config.meshGroupAddress) : null);
    }

    /**
     * 工厂方法：创建使用 LoRa 传输的 MeshRouter。
     * <p>
     * 3d 集成：多个 MeshRouter 实例共享同一个 {@code channel}（BlockingQueue）即可通过
     * LoRa 信道互通。内部创建 {@link LoRaTransportAdapter}，绑定到共享信道。
     *
     * @param selfSysid     本节点 sysid
     * @param config        路由配置（transportType 应为 LORA）
     * @param loRaTransport LoRa 帧适配器
     * @param channel       共享空中信道标识（BlockingQueue），同一 channel 实例共享同一物理信道
     * @return 新建的 MeshRouter
     */
    public static MeshRouter createWithLoRa(int selfSysid, MeshRouterConfig config,
                                            LoRaMavlinkTransport loRaTransport,
                                            BlockingQueue<byte[]> channel) {
        LoRaTransportAdapter adapter = new LoRaTransportAdapter(
                loRaTransport, channel, selfSysid, config.loRaMaxPayloadBytes);
        return new MeshRouter(selfSysid, config, adapter, true);
    }

    /**
     * 注册帧监听器：收到完整 MAVLink 帧时回调（委托到 transport）。
     * <p>
     * 3d 集成：供上层（如 VirtualDrone）接收并分发 mesh 消息。
     * transport 为 null 时静默忽略。
     *
     * @param listener 帧回调
     */
    public void addFrameListener(Consumer<MavlinkFrame> listener) {
        if (transport != null) {
            transport.addFrameListener(listener);
        }
    }

    /** 当前传输层（供诊断与测试）。 */
    public MavlinkTransport transport() {
        return transport;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 启动：初始化时间戳。 */
    public void start() {
        long now = System.currentTimeMillis();
        lastHelloSentMs = now;
        lastTimeoutCheckMs = now;
        lastTopologyReportMs = now;
        SimLog.info("[mesh] router started: sysid=" + selfSysid + " config=" + config);
    }

    /**
     * 主动退出（FR-22a）：对所有路由表项涉及的受影响目标发 RERR，停 HELLO。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        synchronized (this) {
            // 对所有路由表项的 nextHop 发 RERR（通知邻居本节点退出）
            long now = System.currentTimeMillis();
            List<RouteEntry> allRoutes = routes.snapshot();
            for (RouteEntry r : allRoutes) {
                sendRouteError(r.targetSysId, 0, now);
            }
            routes.clear();
            neighbors.clear();
        }
        // 3d 集成：关闭内部创建的 transport（如 LoRaTransportAdapter），避免接收线程泄漏。
        // 外部传入的 transport（如 VirtualDrone 的 UdpMavlinkTransport）由外部管理生命周期。
        if (ownsTransport && transport != null) {
            transport.close();
        }
        SimLog.info("[mesh] router closed: sysid=" + selfSysid);
    }

    // ------------------------------------------------------------------
    // 周期驱动（tick）
    // ------------------------------------------------------------------

    /**
     * 周期驱动调度逻辑（由 VirtualDrone.tickOnce 调用）。
     * <ol>
     *   <li>HELLO 周期广播（FR-09）</li>
     *   <li>邻居超时检查（FR-11）</li>
     *   <li>拓扑快照上报（FR-27）</li>
     *   <li>路由表过期清理（FR-21）</li>
     * </ol>
     */
    public void tick(long nowMs) {
        if (closed) {
            return;
        }
        // 1) HELLO 周期广播
        if (nowMs - lastHelloSentMs >= config.helloIntervalMs) {
            broadcastHello(nowMs);
            lastHelloSentMs = nowMs;
        }
        // 2) 邻居超时检查
        if (nowMs - lastTimeoutCheckMs >= config.neighborTimeoutMs / 2) {
            checkNeighborTimeout(nowMs);
            lastTimeoutCheckMs = nowMs;
        }
        // 3) 拓扑快照上报
        if (nowMs - lastTopologyReportMs >= config.topologyReportIntervalMs) {
            reportTopology(nowMs);
            lastTopologyReportMs = nowMs;
        }
        // 4) 路由表过期清理
        routes.cleanupExpired(nowMs);
        // P1-fix(Major 6): pendingSends 过期清理（TTL=30s）
        cleanupExpiredPendingSends(nowMs);
    }

    // ------------------------------------------------------------------
    // HELLO 广播与邻居发现（FR-09/10/11）
    // ------------------------------------------------------------------

    /**
     * HELLO 周期广播（FR-09）：组装 MeshHeartbeatMsg 并 UDP 广播到 mesh 组播地址。
     */
    public void broadcastHello(long nowMs) {
        MeshHeartbeatMsg msg = new MeshHeartbeatMsg(
                selfSysid, latE7, lonE7, altMm, batteryPercent,
                neighbors.size(), nowMs);
        sendFrame(msg, config.meshGroupAddress);
    }

    /**
     * 收到 HELLO 处理（FR-10）：新增或刷新邻居表项。
     *
     * @param msg      收到的 MeshHeartbeatMsg
     * @param srcAddr  源地址
     * @param rssiDbm  RSSI（dBm）
     */
    public void onMeshHeartbeat(MeshHeartbeatMsg msg, InetSocketAddress srcAddr, int rssiDbm) {
        if (closed || msg.sysid == selfSysid) {
            return;
        }
        long now = System.currentTimeMillis();
        neighbors.upsert(msg.sysid, srcAddr, now, rssiDbm);
        // 若该节点之前被标记暂时不可达，收到 HELLO 说明已恢复，清除标记并重发缓存数据
        if (temporarilyUnreachable.remove(msg.sysid) != null) {
            consecutiveHealFailures.remove(msg.sysid);
            SimLog.info("[mesh] node " + msg.sysid + " recovered, clearing temporarilyUnreachable");
            // P1-fix(Major 7): 检查 pendingSends 中是否有该目标的数据，发起 RREQ 重建路由
            boolean hasPending = false;
            synchronized (pendingSends) {
                for (PendingSend p : pendingSends) {
                    if (p.targetSysid() == msg.sysid) {
                        hasPending = true;
                        break;
                    }
                }
            }
            if (hasPending) {
                initiateRreq(msg.sysid, now);
                SimLog.info("[mesh] re-initiated RREQ for recovered target " + msg.sysid);
            }
        }
    }

    /**
     * 邻居超时检查（FR-11）：遍历超时邻居，移除并触发 RERR。
     */
    public void checkNeighborTimeout(long nowMs) {
        List<Integer> expired = neighbors.findExpired(nowMs, config.neighborTimeoutMs);
        for (int sysid : expired) {
            onNeighborTimeout(sysid, nowMs);
        }
    }

    /**
     * 邻居超时处理（FR-13/14/16）：在 synchronized 块内完成 RERR 发送 + 路由删除 + 备份切换。
     */
    public void onNeighborTimeout(int neighborSysid, long nowMs) {
        synchronized (this) {
            // 1) 查找受影响的目标
            List<Integer> affectedTargets = routes.findAffectedTargets(neighborSysid);
            if (affectedTargets.isEmpty()) {
                neighbors.remove(neighborSysid);
                return;
            }
            // 2) 对每个 affectedTarget 发 RERR
            for (int target : affectedTargets) {
                sendRouteError(target, 0, nowMs);
            }
            // 3) 删除所有以 neighborSysid 为下一跳的路由项（FR-14）
            routes.removeByNextHop(neighborSysid);
            // 4) 移除邻居
            neighbors.remove(neighborSysid);
            // 5) 备份路径切换（FR-16）或重新建路（FR-15）
            for (int target : affectedTargets) {
                if (routes.promoteBackup(target)) {
                    // 备份提升成功，自愈完成（<0.1s）
                    consecutiveHealFailures.remove(target);
                    SimLog.info("[mesh] backup promoted for target " + target);
                } else {
                    // 无备份路径，需重新建路（FR-15）
                    consecutiveHealFailures.merge(target, 1, Integer::sum);
                    int failures = consecutiveHealFailures.get(target);
                    if (failures > HEAL_FAILURE_THRESHOLD) {
                        // 连续自愈失败超过阈值，标记暂时不可达
                        temporarilyUnreachable.put(target, true);
                        SimLog.warn("[mesh] target " + target
                                + " marked temporarily unreachable after " + failures + " failures");
                    } else {
                        // 发起 RREQ 重建路
                        initiateRreq(target, nowMs);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // RREQ/RREP 路由建立（FR-01/02/02a/04/05/05a/17/18）
    // ------------------------------------------------------------------

    /**
     * 按需建路发送（FR-04）：查路由表，有则转发；无则发起 RREQ 并缓存数据。
     *
     * @param targetSysid 目标 sysid
     * @param payload     待发送数据
     * @return 发送结果
     */
    public SendResult sendTo(int targetSysid, byte[] payload) {
        if (closed) {
            return SendResult.DROPPED_NO_ROUTE;
        }
        long now = System.currentTimeMillis();
        // 查路由表
        RouteEntry route = routes.lookup(targetSysid);
        if (route != null) {
            // 有有效路由：按 nextHop 转发
            NeighborEntry nextHop = neighbors.get(route.nextHop);
            if (nextHop != null) {
                // 实际数据转发由上层（VirtualDrone）处理，这里只返回 SENT
                return SendResult.SENT;
            }
        }
        // 暂时不可达：缓存数据，不发起 RREQ
        if (temporarilyUnreachable.getOrDefault(targetSysid, false)) {
            enqueuePendingSend(targetSysid, payload, now);
            return SendResult.DROPPED_NO_ROUTE;
        }
        // 无路由：发起 RREQ
        initiateRreq(targetSysid, now);
        // 缓存数据发送需求
        enqueuePendingSend(targetSysid, payload, now);
        return SendResult.QUEUED_RREQ;
    }

    /**
     * P1-fix(Major 6): 入队待发送数据，带容量上限保护。
     * 超过 PENDING_SENDS_MAX_CAPACITY 时丢弃最旧条目并 log warning。
     */
    private void enqueuePendingSend(int targetSysid, byte[] payload, long nowMs) {
        synchronized (pendingSends) {
            if (pendingSends.size() >= PENDING_SENDS_MAX_CAPACITY) {
                PendingSend dropped = pendingSends.pollFirst();
                SimLog.warn("[mesh] pendingSends full (capacity=" + PENDING_SENDS_MAX_CAPACITY
                        + "), dropped oldest: target=" + (dropped != null ? dropped.targetSysid() : -1));
            }
            pendingSends.add(new PendingSend(targetSysid, payload, nowMs));
        }
    }

    /**
     * P1-fix(Major 6): 清理 pendingSends 中过期条目（TTL=30s）。
     */
    private void cleanupExpiredPendingSends(long nowMs) {
        synchronized (pendingSends) {
            pendingSends.removeIf(p -> (nowMs - p.createdAtMs()) > PENDING_SENDS_TTL_MS);
        }
    }

    /**
     * 发起 RREQ（FR-01）：组装 MeshRouteRequestMsg 广播到 mesh 组播地址。
     */
    public void initiateRreq(int targetSysid, long nowMs) {
        int broadcastId = broadcastIdSeq.incrementAndGet() & 0xFFFF;
        MeshRouteRequestMsg rreq = new MeshRouteRequestMsg(
                selfSysid, targetSysid, broadcastId, 0, 0.0, nowMs);
        // 记入去重缓存（自己发起的 RREQ 不再接收处理）
        rreqCache.seenAndRecord(selfSysid, broadcastId);
        sendFrame(rreq, config.meshGroupAddress);
        SimLog.info("[mesh] RREQ initiated: src=" + selfSysid + " tgt=" + targetSysid
                + " bcastId=" + broadcastId);
    }

    /**
     * 收到 RREQ 处理（FR-01/02/02a/05/05a/17）。
     *
     * @param msg       收到的 MeshRouteRequestMsg
     * @param srcAddr   源地址
     * @param rssiDbm   RSSI（dBm）
     * @param frameSysid 发送该 RREQ 帧的 MAVLink systemId（真实上一跳 sysid，作为 nextHop）
     */
    public void onRouteRequest(MeshRouteRequestMsg msg, InetSocketAddress srcAddr, int rssiDbm,
                               int frameSysid) {
        if (closed) {
            return;
        }
        // 1) hopCount 检查（FR-08）
        if (msg.hopCount >= config.maxHops) {
            return;
        }
        // 2) RREQ 去重（FR-05）
        if (rreqCache.seenAndRecord(msg.sourceSysid, msg.broadcastId)) {
            return;
        }
        long now = System.currentTimeMillis();
        // 3) 建立到 sourceSysid 的反向路由（FR-17）
        double reverseMetric = computeMetric(msg.hopCount + 1, rssiDbm, 0);
        // P1-fix(Major 9): 使用帧真实 systemId 作为 nextHop，而非端口低 8 位伪 sysid
        routes.upsert(msg.sourceSysid, frameSysid, msg.hopCount + 1,
                reverseMetric, now, true);
        // 4) 若 self == targetSysid → 回传 RREP（FR-02）
        if (msg.targetSysid == selfSysid) {
            sendRouteReply(msg.sourceSysid, selfSysid, msg.hopCount + 1,
                    reverseMetric, now, srcAddr);
            SimLog.info("[mesh] RREP sent back to source " + msg.sourceSysid);
            return;
        }
        // 5) 否则递增 hopCount、累加 metric、继续广播 RREQ（FR-05a）
        MeshRouteRequestMsg forwarded = new MeshRouteRequestMsg(
                msg.sourceSysid, msg.targetSysid, msg.broadcastId,
                msg.hopCount + 1, msg.originMetric() + reverseMetric, now);
        sendFrame(forwarded, config.meshGroupAddress);
    }

    /**
     * 收到 RREP 处理（FR-02a/17/18）。
     *
     * @param msg       收到的 MeshRouteReplyMsg
     * @param srcAddr   源地址
     * @param rssiDbm   RSSI（dBm）
     * @param frameSysid 发送该 RREP 帧的 MAVLink systemId（真实上一跳 sysid，作为 nextHop）
     */
    public void onRouteReply(MeshRouteReplyMsg msg, InetSocketAddress srcAddr, int rssiDbm,
                             int frameSysid) {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        // P1-fix(Major 9): 使用帧真实 systemId 作为 nextHop，而非端口低 8 位伪 sysid
        // 1) 建立到 targetSysid 的正向路由项
        double forwardMetric = computeMetric(msg.hopCount + 1, rssiDbm, 0);
        routes.upsert(msg.targetSysid, frameSysid, msg.hopCount + 1,
                forwardMetric, now, true);
        // 2) 若 self == msg.sourceSysid → 路由建立完成，从 pendingSends 取出缓存数据转发
        if (msg.sourceSysid == selfSysid) {
            synchronized (pendingSends) {
                pendingSends.removeIf(p -> p.targetSysid == msg.targetSysid);
            }
            consecutiveHealFailures.remove(msg.targetSysid);
            temporarilyUnreachable.remove(msg.targetSysid);
            SimLog.info("[mesh] route established to " + msg.targetSysid
                    + " via " + frameSysid + " metric=" + forwardMetric);
            return;
        }
        // 3) 中间节点：查反向路由到 sourceSysid 的 nextHop，沿反向路径继续单播转发 RREP
        RouteEntry reverseRoute = routes.lookup(msg.sourceSysid);
        if (reverseRoute == null) {
            return;
        }
        NeighborEntry nextHop = neighbors.get(reverseRoute.nextHop);
        if (nextHop == null) {
            return;
        }
        MeshRouteReplyMsg forwarded = new MeshRouteReplyMsg(
                msg.sourceSysid, msg.targetSysid, msg.hopCount + 1,
                msg.metric() + forwardMetric, now);
        sendFrame(forwarded, nextHop.addr);
    }

    // ------------------------------------------------------------------
    // RERR 与自愈重构（FR-03/13/14/15/16/19）
    // ------------------------------------------------------------------

    /**
     * 收到 RERR 处理（FR-14）：删除受影响路由，若无其他路由则继续传播。
     *
     * @param msg       收到的 MeshRouteErrorMsg
     * @param srcAddr   源地址
     * @param frameSysid 发送该 RERR 帧的 MAVLink systemId（真实下一跳 sysid）
     */
    public void onRouteError(MeshRouteErrorMsg msg, InetSocketAddress srcAddr, int frameSysid) {
        if (closed) {
            return;
        }
        synchronized (this) {
            // P1-fix(Major 5): 正确顺序——
            // 1) 查找以 unreachableSysid 为 nextHop 的受影响 target
            List<Integer> affectedTargets = routes.findAffectedTargets(msg.unreachableSysid);
            if (affectedTargets.isEmpty()) {
                return;
            }
            // 2) 删除所有以 unreachableSysid 为 nextHop 的路由项（FR-14）
            routes.removeByNextHop(msg.unreachableSysid);
            // 3) 对每个受影响 target：尝试 promoteBackup，无备份则继续传播 RERR
            long now = System.currentTimeMillis();
            for (int target : affectedTargets) {
                if (routes.promoteBackup(target)) {
                    // 备份提升成功，无需传播 RERR
                    consecutiveHealFailures.remove(target);
                    SimLog.info("[mesh] RERR: backup promoted for target " + target);
                } else {
                    // 无备份路径：继续传播 RERR（hopCount 递增）
                    sendRouteError(target, msg.hopCount + 1, now);
                }
            }
        }
    }

    /**
     * 转发帧（FR-06/07/08）：hopCount 递减，耗尽或越界丢弃。
     * <p>
     * P1-fix(Major 8): 返回 {@link ForwardOutcome} 携带递减后的 newHopCount，
     * 调用方据此写回帧尾部，避免递减结果丢失。
     *
     * @param data      待转发数据
     * @param hopCount  当前 hopCount
     * @return 转发结果（含递减后的 newHopCount）
     */
    public ForwardOutcome forwardFrame(byte[] data, int hopCount) {
        if (closed) {
            return ForwardOutcome.dropped(SendResult.DROPPED_NO_ROUTE, hopCount);
        }
        // FR-08：hopCount > MAX_HOPS 丢弃
        if (hopCount > config.maxHops) {
            return ForwardOutcome.dropped(SendResult.DROPPED_HOP_LIMIT, hopCount);
        }
        // FR-07：hopCount <= 0 丢弃
        if (hopCount <= 0) {
            return ForwardOutcome.dropped(SendResult.DROPPED_HOP_LIMIT, hopCount);
        }
        // FR-06：hopCount 递减
        int newHopCount = hopCount - 1;
        // 实际转发由上层处理，返回 SENT + newHopCount 供调用方写回帧尾部
        return ForwardOutcome.sent(newHopCount);
    }

    /**
     * 触发路由错误（FR-13）：对外通知某目标不可达。
     */
    public void triggerRerr(int unreachableSysid) {
        sendRouteError(unreachableSysid, 0, System.currentTimeMillis());
    }

    /**
     * 发送 RERR 消息。
     *
     * @param unreachableSysid 不可达目标
     * @param hopCount         已经历跳数
     * @param nowMs            当前时间戳
     */
    private void sendRouteError(int unreachableSysid, int hopCount, long nowMs) {
        MeshRouteErrorMsg rerr = new MeshRouteErrorMsg(unreachableSysid, hopCount, nowMs);
        // 广播到 mesh 组播地址（简化：实际应单播给受影响的上一跳）
        sendFrame(rerr, config.meshGroupAddress);
    }

    /**
     * 发送 RREP 消息（FR-02）：沿反向路径单播回传。
     */
    private void sendRouteReply(int sourceSysid, int targetSysid, int hopCount,
                                double metric, long nowMs, InetSocketAddress destAddr) {
        MeshRouteReplyMsg rrep = new MeshRouteReplyMsg(
                sourceSysid, targetSysid, hopCount, metric, nowMs);
        sendFrame(rrep, destAddr);
    }

    // ------------------------------------------------------------------
    // 拓扑快照上报（FR-27）
    // ------------------------------------------------------------------

    /**
     * 拓扑快照上报：组装 MeshNeighborTableMsg 并单播发送到 cloud-backend。
     */
    public void reportTopology(long nowMs) {
        if (config.cloudBackendAddress == null) {
            return;
        }
        List<NeighborEntry> snapshot = neighbors.snapshot();
        List<MeshNeighborTableMsg.NeighborInfo> infos = new ArrayList<>(snapshot.size());
        for (NeighborEntry e : snapshot) {
            infos.add(new MeshNeighborTableMsg.NeighborInfo(
                    e.sysid, e.rssiDbm, e.linkQuality.code()));
        }
        MeshNeighborTableMsg msg = new MeshNeighborTableMsg(selfSysid, nowMs, infos);
        sendFrame(msg, config.cloudBackendAddress);
    }

    // ------------------------------------------------------------------
    // 度量计算（FR-17）
    // ------------------------------------------------------------------

    /**
     * 综合度量计算（FR-17）：
     * {@code metric = hopCount × W1 + (100 + RSSI) × W2 + delayMs × W3}
     * <p>
     * 默认权重 W1=1.0/W2=0.5/W3=0.1。RSSI 取端到端路径上最差链路值，
     * 字段缺失时以保守默认值（RSSI=-100, 延迟=500ms）代入。
     *
     * @param hopCount 跳数
     * @param rssiDbm  RSSI（dBm）
     * @param delayMs  延迟（ms）
     * @return 综合度量
     */
    public double computeMetric(int hopCount, int rssiDbm, double delayMs) {
        // 保守默认值：RSSI 缺失用 -100，延迟缺失用 500ms
        int rssi = (rssiDbm == 0) ? -100 : rssiDbm;
        double delay = (delayMs <= 0) ? 500.0 : delayMs;
        return hopCount * config.metricW1
                + (100 + rssi) * config.metricW2
                + delay * config.metricW3;
    }

    // ------------------------------------------------------------------
    // 状态更新（供 VirtualDrone 调用）
    // ------------------------------------------------------------------

    /** 更新本节点位置/电量（供 HELLO 组装用）。 */
    public void updateState(int latE7, int lonE7, int altMm, int batteryPercent) {
        this.latE7 = latE7;
        this.lonE7 = lonE7;
        this.altMm = altMm;
        this.batteryPercent = batteryPercent;
    }

    // ------------------------------------------------------------------
    // 查询方法（供 REST/快照用）
    // ------------------------------------------------------------------

    public NeighborTable snapshotNeighbors() {
        return neighbors;
    }

    public RouteTable snapshotRoutes() {
        return routes;
    }

    public int neighborCount() {
        return neighbors.size();
    }

    public int selfSysid() {
        return selfSysid;
    }

    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /**
     * 发送帧到指定地址。
     * <p>
     * 3d 集成：通过通用 {@link MavlinkTransport#send(MavlinkFrame)} 发送。
     * UDP 模式下 dest 用于选择对端（由 UdpTransportAdapter 内部处理）；
     * LoRa 模式下 dest 被忽略（广播到共享空中信道）。
     */
    private void sendFrame(io.aerofleet.mavlink.messages.MavlinkMessage msg,
                           InetSocketAddress dest) {
        if (transport == null || dest == null) {
            return;
        }
        try {
            MavlinkFrame frame = msg.toFrame(selfSysid, 1, frameSeq.incrementAndGet() & 0xFF);
            transport.send(frame);
        } catch (IOException e) {
            SimLog.warn("[mesh] send failed: " + e.getMessage());
        }
    }

    // P1-fix(Major 9): extractSysidFromAddr 已移除。
    // nextHop 现从 MavlinkFrame.getSystemId() 真实 sysid 获取，由 onRouteRequest/
    // onRouteReply/onRouteError 的 frameSysid 参数传入。

}