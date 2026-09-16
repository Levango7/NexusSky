package io.aerofleet.sim.satrelay;

import io.aerofleet.sim.SimLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 层级行由决策核心（M7 星-空-地多层级中继，FR-5.3/5.5）。
 * <p>
 * 近端优先 + 逐级升级 + 降级滞后 + 切换策略引擎。
 * 依赖：M5 MeshRouter（L1 可达性）、HAPS 节点列表（L2）、LEO 星座（L3）。
 * <p>
 * 性能：单次路由决策 <10ms（DFX 4.1.3），3 次可达性查询 + 1 次策略排序。
 */
public final class HierarchicalRouter {

    /** 候选路径。 */
    public record CandidatePath(RelayLayer layer, List<Integer> pathNodes,
                                int estimatedDelayMs, int bandwidthMbps,
                                long windowRemainingMs, String reason) {
        /** 路径跳数。 */
        public int hopCount() {
            return pathNodes.size();
        }
    }

    /** 路由决策结果。 */
    public record RouteDecision(RelayLayer sourceLayer, RelayLayer targetLayer,
                                RelayLayer chosenLayer, CandidatePath chosenPath,
                                SatRelayConfig.Strategy strategy, String decisionReason,
                                int estimatedDelayMs, long timestamp) {
        /** 是否全层级不可达。 */
        public boolean isNoPath() {
            return chosenPath == null;
        }

        /** 选定路径节点列表。 */
        public List<Integer> pathNodes() {
            return chosenPath != null ? chosenPath.pathNodes() : Collections.emptyList();
        }
    }

    /** 策略切换审计记录。 */
    public record StrategyAudit(SatRelayConfig.Strategy from, SatRelayConfig.Strategy to,
                                String triggerBy, long timestamp) {}

    private final SatRelayConfig config;
    private final List<HapsRelayNode> hapsNodes;
    private final LeoConstellation constellation;

    /** 当前切换策略（volatile 保证运行时热更新，FR-5.5.1.5）。 */
    private volatile SatRelayConfig.Strategy currentStrategy;
    /** 降级滞后跟踪：低层链路恢复起始时间（layer name → recovery start ms）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> recoveryStartMs = new java.util.concurrent.ConcurrentHashMap<>();
    /** 策略切换审计日志（有界）。 */
    private final java.util.ArrayDeque<StrategyAudit> strategyAudits = new java.util.ArrayDeque<>();
    private static final int MAX_AUDITS = 100;

    /**
     * 构造器。
     *
     * @param config       中继配置
     * @param hapsNodes    HAPS 中继节点列表（L2 层），可为空
     * @param constellation LEO 星座（L3 层），可为空
     */
    public HierarchicalRouter(SatRelayConfig config, List<HapsRelayNode> hapsNodes,
                              LeoConstellation constellation) {
        this.config = config;
        this.hapsNodes = hapsNodes != null ? Collections.unmodifiableList(new ArrayList<>(hapsNodes))
                : Collections.emptyList();
        this.constellation = constellation;
        this.currentStrategy = config.strategy;
    }

    /**
     * 执行层级路由决策（FR-5.3.1）。
     * <p>
     * 近端优先：先查 L1 → L2 → L3，按切换策略从候选集合选择。
     * 降级滞后：低层链路需持续可用达阈值方可降级。
     * 无路径可达：返回 chosenPath=null, reason="全层级不可达"。
     * 路由环路检测：路径中禁止重复节点。
     *
     * @param sourceNodeId  源节点 ID
     * @param targetNodeId  目标节点 ID
     * @param sourceLatDeg  源纬度
     * @param sourceLonDeg  源经度
     * @param targetLatDeg  目标纬度
     * @param targetLonDeg  目标经度
     * @param l1Reachable   L1 层是否可达（由 M5 MeshRouter 提供）
     * @param nowMs         当前仿真时钟（ms）
     * @return 路由决策结果
     */
    public RouteDecision decide(int sourceNodeId, int targetNodeId,
                                double sourceLatDeg, double sourceLonDeg,
                                double targetLatDeg, double targetLonDeg,
                                boolean l1Reachable, long nowMs) {
        List<CandidatePath> candidates = new ArrayList<>();

        // 1) 查询 L1 可达性（M5 MeshRouter 提供）
        if (l1Reachable) {
            candidates.add(new CandidatePath(
                    RelayLayer.L1, List.of(sourceNodeId, targetNodeId),
                    20, 5, Long.MAX_VALUE, "L1 可达"));
        }

        // 2) 查询 L2 可达性（HAPS 覆盖）
        CandidatePath l2Path = checkL2Reachability(sourceNodeId, targetNodeId,
                sourceLatDeg, sourceLonDeg, targetLatDeg, targetLonDeg, nowMs);
        if (l2Path != null) {
            candidates.add(l2Path);
        }

        // 3) 查询 L3 可达性（卫星可见窗口）
        CandidatePath l3Path = checkL3Reachability(sourceNodeId, targetNodeId,
                sourceLatDeg, sourceLonDeg, targetLatDeg, targetLonDeg, nowMs);
        if (l3Path != null) {
            candidates.add(l3Path);
        }

        // 4) 候选为空 → 全层级不可达
        if (candidates.isEmpty()) {
            return new RouteDecision(RelayLayer.L0, RelayLayer.L4, null, null,
                    currentStrategy, "全层级不可达", -1, nowMs);
        }

        // 5) 路由环路检测：剔除含重复节点的路径
        candidates.removeIf(this::hasLoop);

        // 5.5) 环路检测剔除所有候选 → 全层级不可达
        if (candidates.isEmpty()) {
            return new RouteDecision(RelayLayer.L0, RelayLayer.L4, null, null,
                    currentStrategy, "全层级不可达（路径环路）", -1, nowMs);
        }

        // 6) 按切换策略选择
        CandidatePath chosen = selectByStrategy(candidates, nowMs);

        // 7) 降级滞后判定
        chosen = applyHysteresis(chosen, candidates, nowMs);

        return new RouteDecision(RelayLayer.L0, RelayLayer.L4, chosen.layer(),
                chosen, currentStrategy, chosen.reason(),
                chosen.estimatedDelayMs(), nowMs);
    }

    /** L2 可达性检查：源与目标均在某 HAPS 覆盖内。 */
    private CandidatePath checkL2Reachability(int sourceNodeId, int targetNodeId,
                                              double sourceLat, double sourceLon,
                                              double targetLat, double targetLon, long nowMs) {
        for (HapsRelayNode haps : hapsNodes) {
            if (!haps.isReachable()) continue;
            if (haps.covers(sourceLat, sourceLon) && haps.covers(targetLat, targetLon)) {
                return new CandidatePath(
                        RelayLayer.L2, List.of(sourceNodeId, haps.nodeId(), targetNodeId),
                        40, 20, Long.MAX_VALUE,
                        "L1 不可达，升级 L2（HAPS " + haps.nodeId() + " 覆盖源与目标）");
            }
        }
        return null;
    }

    /** L3 可达性检查：存在同时可见源与目标的卫星。 */
    private CandidatePath checkL3Reachability(int sourceNodeId, int targetNodeId,
                                              double sourceLat, double sourceLon,
                                              double targetLat, double targetLon, long nowMs) {
        if (constellation == null) return null;
        List<SatelliteNode> visibleAtSource = constellation.findVisible(
                sourceLat, sourceLon, config.elevationThresholdDeg);
        for (SatelliteNode sat : visibleAtSource) {
            double[] elAz = OrbitModel.elevationAzimuth(sat.eciPositionKm(), targetLat, targetLon);
            if (elAz[0] > config.elevationThresholdDeg) {
                int delayMs = computeDelayMs(sat.orbitAltitudeKm());
                int bandwidthMbps = computeBandwidthMbps(1);
                long remaining = LinkWindowCalculator.windowRemainingMs(
                        sat, targetLat, targetLon, config.elevationThresholdDeg,
                        nowMs, 1_200_000L, config.linkWindowScanStepMs);
                String reason = visibleAtSource.size() > 0 && !hasL1Candidate()
                        ? "L1/L2 不可达，升级 L3（卫星 " + sat.satId() + " 可见）"
                        : "L3 经卫星 " + sat.satId() + " 可达";
                return new CandidatePath(
                        RelayLayer.L3, List.of(sourceNodeId, sat.satId(), targetNodeId),
                        delayMs, bandwidthMbps, remaining, reason);
            }
        }
        return null;
    }

    private boolean hasL1Candidate() {
        return false; // 简化：由 candidates 列表决定
    }

    /** 路由环路检测：路径中是否有重复节点（FR-5.3.1.8）。 */
    private boolean hasLoop(CandidatePath path) {
        Set<Integer> seen = new HashSet<>();
        for (int node : path.pathNodes()) {
            if (!seen.add(node)) {
                return true;
            }
        }
        return false;
    }

    /** 按切换策略从候选集合选择最优路径（FR-5.5.1）。 */
    private CandidatePath selectByStrategy(List<CandidatePath> candidates, long nowMs) {
        if (candidates.isEmpty()) return null;
        SatRelayConfig.Strategy strategy = currentStrategy;
        return switch (strategy) {
            case NEAR_FIRST -> selectNearFirst(candidates);
            case DELAY_OPTIMAL -> selectDelayOptimal(candidates);
            case BANDWIDTH_OPTIMAL -> selectBandwidthOptimal(candidates);
            case RELIABILITY_OPTIMAL -> selectReliabilityOptimal(candidates);
        };
    }

    /** 近端优先：选层级最低的（FR-5.3.1.1）。 */
    private CandidatePath selectNearFirst(List<CandidatePath> candidates) {
        CandidatePath best = null;
        for (CandidatePath c : candidates) {
            if (best == null || c.layer().layerNumber() < best.layer().layerNumber()) {
                best = c;
            }
        }
        return best;
    }

    /** 延迟最优：选 estimatedDelayMs 最小的（FR-5.5.1.2），并列回退近端优先。 */
    private CandidatePath selectDelayOptimal(List<CandidatePath> candidates) {
        CandidatePath best = null;
        for (CandidatePath c : candidates) {
            if (best == null || c.estimatedDelayMs() < best.estimatedDelayMs()) {
                best = c;
            } else if (c.estimatedDelayMs() == best.estimatedDelayMs()) {
                // 并列回退近端优先（FR-5.5.3.3）
                if (c.layer().layerNumber() < best.layer().layerNumber()) {
                    best = c;
                }
            }
        }
        return best;
    }

    /** 带宽最优：选 bandwidthMbps 最大的（FR-5.5.1.3），并列回退近端优先。 */
    private CandidatePath selectBandwidthOptimal(List<CandidatePath> candidates) {
        CandidatePath best = null;
        for (CandidatePath c : candidates) {
            if (best == null || c.bandwidthMbps() > best.bandwidthMbps()) {
                best = c;
            } else if (c.bandwidthMbps() == best.bandwidthMbps()) {
                if (c.layer().layerNumber() < best.layer().layerNumber()) {
                    best = c;
                }
            }
        }
        return best;
    }

    /** 可靠性最优：选窗口剩余时间最长的（FR-5.5.1.4），并列回退近端优先。 */
    private CandidatePath selectReliabilityOptimal(List<CandidatePath> candidates) {
        CandidatePath best = null;
        for (CandidatePath c : candidates) {
            if (best == null || c.windowRemainingMs() > best.windowRemainingMs()) {
                best = c;
            } else if (c.windowRemainingMs() == best.windowRemainingMs()) {
                if (c.layer().layerNumber() < best.layer().layerNumber()) {
                    best = c;
                }
            }
        }
        return best;
    }

    /**
     * 降级滞后判定（FR-5.3.1.4）。
     * <p>
     * 若当前在用高层路径且低层路径恢复，需低层持续可用达 hysteresisThresholdMs 方可降级。
     */
    private CandidatePath applyHysteresis(CandidatePath chosen, List<CandidatePath> candidates, long nowMs) {
        // 找候选中层级最低的路径
        CandidatePath lowest = selectNearFirst(candidates);
        if (chosen.layer() == lowest.layer()) {
            // 所选已是最低层，无需滞后
            recoveryStartMs.remove(lowest.layer().name());
            return chosen;
        }
        // 所选高于最低层：检查低层是否持续可用达阈值
        String lowKey = lowest.layer().name();
        long recoveryStart = recoveryStartMs.computeIfAbsent(lowKey, k -> nowMs);
        if (nowMs - recoveryStart >= config.hysteresisThresholdMs) {
            // 达到阈值，降级到低层
            recoveryStartMs.remove(lowKey);
            return new CandidatePath(lowest.layer(), lowest.pathNodes(),
                    lowest.estimatedDelayMs(), lowest.bandwidthMbps(),
                    lowest.windowRemainingMs(),
                    lowest.reason() + "（降级至 L" + lowest.layer().layerNumber() + "，滞后阈值已满足）");
        }
        // 未达阈值，维持当前高层路径
        return chosen;
    }

    /**
     * 运行时切换策略（FR-5.5.1.5），记审计日志（FR-5.5.1.7）。
     *
     * @param newStrategy 新策略
     * @param triggerBy   触发者
     * @return 是否切换成功（相同策略返回 false）
     */
    public boolean setStrategy(SatRelayConfig.Strategy newStrategy, String triggerBy) {
        if (newStrategy == null) {
            return false;
        }
        SatRelayConfig.Strategy old = currentStrategy;
        if (old == newStrategy) {
            return false;
        }
        currentStrategy = newStrategy;
        synchronized (strategyAudits) {
            strategyAudits.addLast(new StrategyAudit(old, newStrategy, triggerBy,
                    System.currentTimeMillis()));
            while (strategyAudits.size() > MAX_AUDITS) {
                strategyAudits.pollFirst();
            }
        }
        SimLog.info("[sat-relay] strategy switched: " + old + " -> " + newStrategy
                + " by " + triggerBy);
        return true;
    }

    /** 当前策略。 */
    public SatRelayConfig.Strategy currentStrategy() {
        return currentStrategy;
    }

    /** 获取策略切换审计日志快照。 */
    public List<StrategyAudit> strategyAudits() {
        synchronized (strategyAudits) {
            return new ArrayList<>(strategyAudits);
        }
    }

    // ===== 链路模型 =====

    /**
     * 计算卫星链路端到端延迟（FR-5.4.1.1/2）。
     * <p>
     * 单程传播延迟：300km→7ms, 1200km→12ms 线性插值。
     * 端到端 = 2 × 单程 + 中继转发开销(30ms)，截断到 [50, 500] ms。
     */
    public static int computeDelayMs(double orbitAltitudeKm) {
        double singleTrip = 7.0 + (orbitAltitudeKm - 300.0) / 900.0 * 5.0;
        double e2e = 2 * singleTrip + 30.0;
        return (int) Math.max(50, Math.min(500, Math.round(e2e)));
    }

    /**
     * 计算卫星链路可用带宽（FR-5.4.1.4/5）。
     * <p>
     * 基础带宽 50Mbps，按共享用户数衰减，截断到 [1, 50] Mbps，耗尽时 0。
     */
    public static int computeBandwidthMbps(int sharedUsers) {
        if (sharedUsers <= 0) return 50;
        int bw = 50 / sharedUsers;
        return Math.max(0, Math.min(50, bw));
    }

    /**
     * 链路质量评分（FR-5.4.1.6，仰角正相关）。
     * <p>
     * qualityScore = elevationDeg / 90，取值 [0, 1]。
     */
    public static double linkQualityScore(double elevationDeg) {
        return Math.max(0, Math.min(1, elevationDeg / 90.0));
    }
}