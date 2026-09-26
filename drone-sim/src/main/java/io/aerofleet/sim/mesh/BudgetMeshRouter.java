package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.sim.BudgetMode;
import io.aerofleet.sim.SimLog;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 丐版 Mesh 路由器：根据 {@link BudgetMode} 切换路由策略。
 * <p>
 * <b>EMERGENCY_TOY（百元级 ESP-NOW 广播模式）</b>：
 * <ul>
 *   <li>所有节点广播所有帧，无路由表、无 RREQ/RREP</li>
 *   <li>hopCount 守卫：MAX_HOPS=5，防止无限广播</li>
 *   <li>节点数限制：最多 5-8 个节点</li>
 *   <li>数据帧精简：仅传 HEARTBEAT + ATTITUDE + COMMAND_LONG + 自定义精简帧</li>
 * </ul>
 * <p>
 * <b>EMERGENCY_STANDARD（千元级 LoRa 简化 AODV-lite）</b>：
 * <ul>
 *   <li>LoRa 分片：MAVLink 帧分片为 ~50 bytes LoRa 帧</li>
 *   <li>HELLO 间隔放宽：5s（LoRa 带宽窄）</li>
 *   <li>邻居超时放宽：15s（LoRa 链路延迟高）</li>
 *   <li>MAX_HOPS=10</li>
 * </ul>
 * <p>
 * <b>TOY / STANDARD / ADVANCED / FULL</b>：委托给现有 {@link MeshRouter}。
 * <p>
 * 线程安全：邻居集合 ConcurrentHashMap；所有状态字段 volatile 或 concurrent。
 *
 * @see EspNowTransport ESP-NOW 传输层
 * @see LoRaTransportAdapter LoRa 传输适配器
 * @see MeshRouter 完整版 AODV-lite 路由引擎
 */
public final class BudgetMeshRouter implements AutoCloseable {

    /** 发送结果枚举（与 MeshRouter.SendResult 语义对齐）。 */
    public enum SendResult {
        /** 已广播/转发。 */
        SENT,
        /** 已丢弃：hopCount 耗尽。 */
        DROPPED_HOP_LIMIT,
        /** 已丢弃：消息类型不在白名单。 */
        DROPPED_MSG_FILTER,
        /** 已丢弃：节点数超限。 */
        DROPPED_NODE_LIMIT,
        /** 已丢弃：路由器已关闭。 */
        DROPPED_CLOSED
    }

    /** 当前 BudgetMode。 */
    private final BudgetMode budgetMode;

    /** 本节点 sysid。 */
    private final int selfSysid;

    // ===== EMERGENCY_TOY: ESP-NOW 广播模式 =====
    /** ESP-NOW 传输层（仅 EMERGENCY_TOY 模式使用）。 */
    private final EspNowTransport espNowTransport;
    /** ESP-NOW 模式下的已知邻居集合（通过广播帧发现）。 */
    private final Set<Integer> espNowNeighbors = ConcurrentHashMap.newKeySet();
    /** ESP-NOW 模式下当前 hopCount（初始为 MAX_HOPS）。 */
    private volatile int currentHopCount = EspNowTransport.MAX_HOPS;

    // ===== EMERGENCY_STANDARD: LoRa 简化 AODV-lite =====
    /** LoRa 传输适配器（仅 EMERGENCY_STANDARD 模式使用）。 */
    private final LoRaTransportAdapter loRaTransport;
    /** LoRa 模式下的已知邻居集合。 */
    private final Set<Integer> loRaNeighbors = ConcurrentHashMap.newKeySet();

    // ===== 通用模式：委托给 MeshRouter =====
    /** 完整版 MeshRouter（TOY/STANDARD/ADVANCED/FULL 模式使用）。 */
    private final MeshRouter delegateRouter;

    /** 是否已关闭。 */
    private volatile boolean closed = false;

    /**
     * 构造丐版 Mesh 路由器。
     * <p>
     * 根据 budgetMode 选择不同的路由策略：
     * <ul>
     *   <li>EMERGENCY_TOY: 创建 EspNowTransport，广播模式</li>
     *   <li>EMERGENCY_STANDARD: 创建 LoRaTransportAdapter，简化 AODV-lite</li>
     *   <li>其他: 创建 MeshRouter 委托</li>
     * </ul>
     *
     * @param budgetMode     预算模式
     * @param selfSysid      本节点 sysid（1-255）
     * @param channelKey     共享信道标识（用于 ESP-NOW / LoRa 传输层）
     * @param delegateRouter 委托的完整版 MeshRouter（仅 TOY/STANDARD/ADVANCED/FULL 使用），可为 null
     */
    public BudgetMeshRouter(BudgetMode budgetMode, int selfSysid,
                            Object channelKey, MeshRouter delegateRouter) {
        if (budgetMode == null) {
            throw new IllegalArgumentException("budgetMode must not be null");
        }
        if (selfSysid < 1 || selfSysid > 255) {
            throw new IllegalArgumentException("selfSysid must be 1-255, got " + selfSysid);
        }
        this.budgetMode = budgetMode;
        this.selfSysid = selfSysid;

        switch (budgetMode) {
            case EMERGENCY_TOY -> {
                this.espNowTransport = new EspNowTransport(channelKey, selfSysid);
                this.loRaTransport = null;
                this.delegateRouter = null;
                SimLog.info("BudgetMeshRouter: EMERGENCY_TOY mode (ESP-NOW broadcast), sysid=" + selfSysid);
            }
            case EMERGENCY_STANDARD -> {
                this.espNowTransport = null;
                this.loRaTransport = new LoRaTransportAdapter(channelKey, selfSysid);
                this.delegateRouter = null;
                SimLog.info("BudgetMeshRouter: EMERGENCY_STANDARD mode (LoRa AODV-lite), sysid=" + selfSysid);
            }
            default -> {
                this.espNowTransport = null;
                this.loRaTransport = null;
                this.delegateRouter = delegateRouter;
                if (delegateRouter == null) {
                    SimLog.warn("BudgetMeshRouter: " + budgetMode + " mode with null delegateRouter, "
                            + "sendTo/broadcast will be no-ops");
                }
                SimLog.info("BudgetMeshRouter: " + budgetMode + " mode (delegated to MeshRouter), sysid=" + selfSysid);
            }
        }
    }

    /**
     * 工厂方法：创建 EMERGENCY_TOY 模式的丐版路由器。
     *
     * @param selfSysid  本节点 sysid
     * @param channelKey ESP-NOW 共享信道标识
     * @return 新建的 BudgetMeshRouter
     */
    public static BudgetMeshRouter emergencyToy(int selfSysid, Object channelKey) {
        return new BudgetMeshRouter(BudgetMode.EMERGENCY_TOY, selfSysid, channelKey, null);
    }

    /**
     * 工厂方法：创建 EMERGENCY_STANDARD 模式的丐版路由器。
     *
     * @param selfSysid  本节点 sysid
     * @param channelKey LoRa 共享信道标识
     * @return 新建的 BudgetMeshRouter
     */
    public static BudgetMeshRouter emergencyStandard(int selfSysid, Object channelKey) {
        return new BudgetMeshRouter(BudgetMode.EMERGENCY_STANDARD, selfSysid, channelKey, null);
    }

    /**
     * 工厂方法：创建委托给 MeshRouter 的丐版路由器。
     *
     * @param budgetMode     预算模式（TOY/STANDARD/ADVANCED/FULL）
     * @param selfSysid      本节点 sysid
     * @param delegateRouter 委托的 MeshRouter
     * @return 新建的 BudgetMeshRouter
     */
    public static BudgetMeshRouter delegated(BudgetMode budgetMode, int selfSysid, MeshRouter delegateRouter) {
        return new BudgetMeshRouter(budgetMode, selfSysid, null, delegateRouter);
    }

    /** 当前 BudgetMode。 */
    public BudgetMode budgetMode() {
        return budgetMode;
    }

    /** 本节点 sysid。 */
    public int selfSysid() {
        return selfSysid;
    }

    /**
     * 发送/广播一帧。
     * <p>
     * EMERGENCY_TOY: 广播到所有 ESP-NOW 邻居，检查 hopCount 守卫和消息白名单。
     * EMERGENCY_STANDARD: 通过 LoRa 分片发送。
     * 其他: 委托给 MeshRouter.sendFrame。
     *
     * @param frame 待发送的 MAVLink 帧
     * @return 发送结果
     */
    public SendResult sendFrame(MavlinkFrame frame) {
        if (closed) {
            return SendResult.DROPPED_CLOSED;
        }
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }

        return switch (budgetMode) {
            case EMERGENCY_TOY -> sendFrameEspNow(frame);
            case EMERGENCY_STANDARD -> sendFrameLoRa(frame);
            default -> sendFrameDelegated(frame);
        };
    }

    /**
     * EMERGENCY_TOY: ESP-NOW 广播发送。
     * <p>
     * 检查项：
     * <ol>
     *   <li>消息 ID 白名单过滤（数据帧精简）</li>
     *   <li>hopCount 守卫（MAX_HOPS=5）</li>
     *   <li>节点数限制（最多 8 个）</li>
     * </ol>
     */
    private SendResult sendFrameEspNow(MavlinkFrame frame) {
        // 消息白名单过滤
        if (!EspNowTransport.isAllowedMsgId(frame.getMessageId())) {
            SimLog.info("EspNow: drop frame msgId=" + frame.getMessageId() + " (not in whitelist)");
            return SendResult.DROPPED_MSG_FILTER;
        }
        // 节点数限制
        if (espNowNeighbors.size() >= EspNowTransport.MAX_NODES) {
            SimLog.warn("EspNow: node limit (" + EspNowTransport.MAX_NODES + ") reached, drop frame");
            return SendResult.DROPPED_NODE_LIMIT;
        }
        // hopCount 守卫
        if (currentHopCount <= 0 || currentHopCount > EspNowTransport.MAX_HOPS) {
            SimLog.warn("EspNow: hopCount=" + currentHopCount + " out of range, drop frame");
            return SendResult.DROPPED_HOP_LIMIT;
        }
        espNowTransport.broadcastFrame(frame, currentHopCount);
        return SendResult.SENT;
    }

    /**
     * EMERGENCY_STANDARD: LoRa 分片发送。
     */
    private SendResult sendFrameLoRa(MavlinkFrame frame) {
        loRaTransport.sendFrame(frame);
        return SendResult.SENT;
    }

    /**
     * 委托模式：转发给 MeshRouter 的传输层。
     */
    private SendResult sendFrameDelegated(MavlinkFrame frame) {
        if (delegateRouter == null) {
            SimLog.warn("BudgetMeshRouter: no delegateRouter for " + budgetMode + ", drop frame");
            return SendResult.DROPPED_CLOSED;
        }
        try {
            io.aerofleet.sim.comm.MavlinkTransport transport = delegateRouter.transport();
            if (transport != null) {
                transport.send(frame);
            }
            return SendResult.SENT;
        } catch (Exception e) {
            SimLog.warn("BudgetMeshRouter: delegate sendFrame error: " + e.getMessage());
            return SendResult.DROPPED_CLOSED;
        }
    }

    /**
     * 转发一帧（EMERGENCY_TOY 模式专用，hopCount 递减）。
     * <p>
     * 收到广播帧后决定是否继续转发：
     * <ul>
     *   <li>hopCount 递减后 > 0：继续广播</li>
     *   <li>hopCount 递减后 = 0：停止转发</li>
     * </ul>
     *
     * @param frame    待转发的 MAVLink 帧
     * @param hopCount 当前 hopCount（转发前）
     * @return 转发结果
     */
    public SendResult forwardFrame(MavlinkFrame frame, int hopCount) {
        if (closed) {
            return SendResult.DROPPED_CLOSED;
        }
        if (budgetMode != BudgetMode.EMERGENCY_TOY) {
            // 非 ESP-NOW 模式不使用 forwardFrame，委托给 MeshRouter
            return sendFrame(frame);
        }
        if (hopCount <= 1) {
            SimLog.info("EspNow: hopCount=" + hopCount + " exhausted, stop forwarding");
            return SendResult.DROPPED_HOP_LIMIT;
        }
        int newHopCount = hopCount - 1;
        if (!EspNowTransport.isAllowedMsgId(frame.getMessageId())) {
            return SendResult.DROPPED_MSG_FILTER;
        }
        espNowTransport.broadcastFrame(frame, newHopCount);
        return SendResult.SENT;
    }

    /**
     * 注册帧监听器。
     * <p>
     * EMERGENCY_TOY: 注册到 EspNowTransport。
     * EMERGENCY_STANDARD: 注册到 LoRaTransportAdapter。
     * 其他: 注册到 MeshRouter（通过 transport）。
     *
     * @param listener 帧回调
     */
    public void addFrameListener(Consumer<MavlinkFrame> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        switch (budgetMode) {
            case EMERGENCY_TOY -> espNowTransport.addFrameListener(listener);
            case EMERGENCY_STANDARD -> loRaTransport.addFrameListener(listener);
            default -> {
                if (delegateRouter != null) {
                    delegateRouter.addFrameListener(listener);
                }
            }
        }
    }

    /**
     * 记录发现的新邻居（ESP-NOW / LoRa 模式）。
     *
     * @param neighborSysid 邻居 sysid
     */
    public void onNeighborDiscovered(int neighborSysid) {
        if (neighborSysid == selfSysid) {
            return;
        }
        switch (budgetMode) {
            case EMERGENCY_TOY -> espNowNeighbors.add(neighborSysid);
            case EMERGENCY_STANDARD -> loRaNeighbors.add(neighborSysid);
            default -> { /* 委托模式下由 MeshRouter 自行管理邻居 */ }
        }
    }

    /**
     * 移除已离线的邻居（ESP-NOW / LoRa 模式）。
     *
     * @param neighborSysid 邻居 sysid
     */
    public void onNeighborLost(int neighborSysid) {
        switch (budgetMode) {
            case EMERGENCY_TOY -> espNowNeighbors.remove(neighborSysid);
            case EMERGENCY_STANDARD -> loRaNeighbors.remove(neighborSysid);
            default -> { /* 委托模式下由 MeshRouter 自行管理邻居 */ }
        }
    }

    /** 当前已知邻居数量。 */
    public int neighborCount() {
        return switch (budgetMode) {
            case EMERGENCY_TOY -> espNowNeighbors.size();
            case EMERGENCY_STANDARD -> loRaNeighbors.size();
            default -> delegateRouter != null ? delegateRouter.neighborCount() : 0;
        };
    }

    /**
     * 当前已知邻居 sysid 集合（不可变快照）。
     */
    public Set<Integer> snapshotNeighbors() {
        return switch (budgetMode) {
            case EMERGENCY_TOY -> Collections.unmodifiableSet(new HashSet<>(espNowNeighbors));
            case EMERGENCY_STANDARD -> Collections.unmodifiableSet(new HashSet<>(loRaNeighbors));
            default -> {
                if (delegateRouter != null) {
                    Set<Integer> set = new HashSet<>();
                    for (NeighborEntry e : delegateRouter.snapshotNeighbors().snapshot()) {
                        set.add(e.sysid);
                    }
                    yield Collections.unmodifiableSet(set);
                }
                yield Collections.emptySet();
            }
        };
    }

    /**
     * 设置 ESP-NOW 模式的初始 hopCount（仅 EMERGENCY_TOY 有效）。
     *
     * @param hopCount 初始 hopCount，必须 1 ≤ hopCount ≤ MAX_HOPS
     */
    public void setHopCount(int hopCount) {
        if (budgetMode != BudgetMode.EMERGENCY_TOY) {
            SimLog.warn("setHopCount only valid for EMERGENCY_TOY mode, ignored");
            return;
        }
        if (hopCount < 1 || hopCount > EspNowTransport.MAX_HOPS) {
            throw new IllegalArgumentException(
                    "hopCount must be 1-" + EspNowTransport.MAX_HOPS + ", got " + hopCount);
        }
        this.currentHopCount = hopCount;
    }

    /** 当前 hopCount（仅 EMERGENCY_TOY 有意义）。 */
    public int currentHopCount() {
        return currentHopCount;
    }

    /** ESP-NOW 传输层（仅 EMERGENCY_TOY 模式，其他返回 null）。 */
    public EspNowTransport espNowTransport() {
        return espNowTransport;
    }

    /** LoRa 传输适配器（仅 EMERGENCY_STANDARD 模式，其他返回 null）。 */
    public LoRaTransportAdapter loRaTransport() {
        return loRaTransport;
    }

    /** 委托的 MeshRouter（仅 TOY/STANDARD/ADVANCED/FULL 模式，其他返回 null）。 */
    public MeshRouter delegateRouter() {
        return delegateRouter;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        switch (budgetMode) {
            case EMERGENCY_TOY -> {
                if (espNowTransport != null) {
                    espNowTransport.close();
                }
                espNowNeighbors.clear();
            }
            case EMERGENCY_STANDARD -> {
                if (loRaTransport != null) {
                    loRaTransport.close();
                }
                loRaNeighbors.clear();
            }
            default -> {
                if (delegateRouter != null) {
                    delegateRouter.close();
                }
            }
        }
        SimLog.info("BudgetMeshRouter closed: mode=" + budgetMode + " sysid=" + selfSysid);
    }
}