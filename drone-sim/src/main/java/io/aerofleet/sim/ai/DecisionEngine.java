package io.aerofleet.sim.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * M11 AI 自主决策引擎：周期评估无人机状态，触发决策。
 * 决策类型：RTL(返航) / AVOID(避障) / ADAPT_PATH(自适应航线) / EMERGENCY_LAND(紧急降落)
 * <p>
 * 本引擎在初版顺序评估基础上增强为：
 * <ol>
 *   <li><b>多策略融合</b>：同时评估返航/避障/自适应航线策略，按融合权重排序输出。</li>
 *   <li><b>权重动态调整</b>：根据态势严重度自动放大对应策略权重。
 *       <ul>
 *         <li>电量 &lt;20%：返航权重 ×3</li>
 *         <li>障碍物 &lt;10m：避障权重 ×3</li>
 *         <li>任务紧急度高(&gt;0.7)：自适应航线权重 ×2</li>
 *         <li>多紧急情况同时出现：按优先级 返航 &gt; 避障 &gt; 自适应</li>
 *       </ul>
 *   </li>
 *   <li><b>决策树</b>：对复杂场景走决策树路径，电量危急时强制返航置顶。</li>
 *   <li><b>决策日志</b>：记录每次评估的输入、权重、决策树路径与选择结果，便于回溯分析。</li>
 * </ol>
 * <p>
 * 注意：drone-sim 模块未引入 slf4j，统一使用 {@code System.out.println} 输出日志。
 */
public class DecisionEngine {

    private final ReturnToHomeStrategy rtlStrategy = new ReturnToHomeStrategy();
    private final ObstacleAvoidanceStrategy avoidStrategy = new ObstacleAvoidanceStrategy();
    private final AdaptivePathStrategy adaptStrategy = new AdaptivePathStrategy();
    private final DecisionTree decisionTree = new DecisionTree();

    // ---- 权重动态调整阈值 ----
    private static final double BATTERY_BOOST_THRESHOLD = 20.0;   // 电量<20% → 返航×3
    private static final double OBSTACLE_BOOST_THRESHOLD = 10.0;  // 障碍物<10m → 避障×3
    private static final double URGENCY_BOOST_THRESHOLD = 0.7;    // 紧急度>0.7 → 自适应×2
    private static final double BATTERY_BOOST_FACTOR = 3.0;
    private static final double OBSTACLE_BOOST_FACTOR = 3.0;
    private static final double URGENCY_BOOST_FACTOR = 2.0;

    // ---- 决策日志 ----
    private final List<DecisionLogEntry> decisionLog = new ArrayList<>();
    private long logSeq = 0;
    private static final int LOG_CAPACITY = 512;

    // ----------------------------------------------------------------------
    // 向后兼容接口：保留旧签名，内部委托给融合评估
    // ----------------------------------------------------------------------

    /**
     * 评估无人机状态，返回需要执行的决策列表（按融合权重降序排列）。
     * <p>此为向后兼容接口，等价于以 {@link DecisionContext#legacy} 构造上下文后调用
     * {@link #evaluate(DecisionContext)}。
     */
    public List<DecisionResult> evaluate(double battery, boolean linkHealthy, boolean gpsHealthy,
                                         double alt, double distanceToHome, boolean obstacleDetected,
                                         double windSpeed) {
        return evaluate(DecisionContext.legacy(battery, linkHealthy, gpsHealthy,
                alt, distanceToHome, obstacleDetected, windSpeed));
    }

    // ----------------------------------------------------------------------
    // 核心评估：多策略融合 + 权重动态调整 + 决策树
    // ----------------------------------------------------------------------

    /**
     * 基于完整决策上下文评估，返回按融合权重降序排列的决策列表。
     */
    public List<DecisionResult> evaluate(DecisionContext ctx) {
        return evaluateFused(ctx).ranked;
    }

    /**
     * 完整融合评估：多策略独立评估 → 权重动态调整 → 融合排序 → 决策树路径修正 → 日志记录。
     *
     * @return {@link FusedDecision} 含主决策、全部候选、动态权重、决策树路径与上下文快照
     */
    public FusedDecision evaluateFused(DecisionContext ctx) {
        // 1. 各策略独立评估，收集候选
        DecisionResult rtl = rtlStrategy.evaluate(ctx.battery, ctx.linkHealthy, ctx.gpsHealthy, ctx.distanceToHome);
        DecisionResult avoid = avoidStrategy.evaluate(ctx.obstacleDetected, ctx.alt);
        DecisionResult adapt = adaptStrategy.evaluate(ctx.windSpeed, ctx.battery);

        // 2. 权重动态调整（基础系数 1.0，按态势严重度放大）
        double rtlWeight = 1.0;
        double avoidWeight = 1.0;
        double adaptWeight = 1.0;

        if (ctx.battery < BATTERY_BOOST_THRESHOLD) {
            rtlWeight *= BATTERY_BOOST_FACTOR;
        }
        if (ctx.obstacleCloseRange()) {
            avoidWeight *= OBSTACLE_BOOST_FACTOR;
        }
        if (ctx.missionUrgency > URGENCY_BOOST_THRESHOLD) {
            adaptWeight *= URGENCY_BOOST_FACTOR;
        }

        // 3. 计算融合权重 = 策略 confidence × 动态调整系数，构造带权候选
        List<WeightedDecision> weighted = new ArrayList<>(3);
        if (rtl != null) {
            weighted.add(new WeightedDecision(rtl, rtl.confidence * rtlWeight, rtlWeight));
        }
        if (avoid != null) {
            weighted.add(new WeightedDecision(avoid, avoid.confidence * avoidWeight, avoidWeight));
        }
        if (adapt != null) {
            weighted.add(new WeightedDecision(adapt, adapt.confidence * adaptWeight, adaptWeight));
        }

        // 4. 融合排序：融合权重降序，相同权重按类型优先级 RTL>EMERGENCY_LAND>AVOID>ADAPT_PATH
        weighted.sort(Comparator.comparingDouble((WeightedDecision wd) -> wd.fusedWeight).reversed()
                .thenComparingInt(wd -> typePriority(wd.result.decisionType)));

        // 5. 决策树评估
        DecisionTree.TreeResult treeResult = decisionTree.evaluate(ctx);

        // 6. 决策树路径修正：电量危急时强制返航/紧急降落置顶
        if (treeResult.verdict == DecisionTree.Verdict.FORCE_RTL && weighted.size() > 1) {
            promoteRtlToFront(weighted);
        }

        // 7. 构造结果列表
        List<DecisionResult> ranked = new ArrayList<>(weighted.size());
        for (WeightedDecision wd : weighted) {
            ranked.add(wd.result);
        }

        DecisionResult primary = ranked.isEmpty() ? null : ranked.get(0);

        // 8. 记录决策日志
        recordLog(ctx, rtlWeight, avoidWeight, adaptWeight, treeResult, primary, ranked.size());

        // 9. 控制台输出（替代 slf4j）
        if (!ranked.isEmpty()) {
            System.out.println("[ai] Decisions triggered: count=" + ranked.size()
                    + " top=" + primary.decisionType
                    + " tree=" + treeResult.path
                    + " w=[rtl=" + rtlWeight + ",avoid=" + avoidWeight + ",adapt=" + adaptWeight + "]");
        }

        return new FusedDecision(primary, ranked, rtlWeight, avoidWeight, adaptWeight,
                treeResult.path, ctx);
    }

    // ----------------------------------------------------------------------
    // 决策日志访问
    // ----------------------------------------------------------------------

    /** 返回决策日志的不可变副本 */
    public List<DecisionLogEntry> getDecisionLog() {
        synchronized (decisionLog) {
            return new ArrayList<>(decisionLog);
        }
    }

    /** 清空决策日志 */
    public void clearLog() {
        synchronized (decisionLog) {
            decisionLog.clear();
            logSeq = 0;
        }
    }

    // ----------------------------------------------------------------------
    // 内部实现
    // ----------------------------------------------------------------------

    /** 带权候选决策（内部载体） */
    private static final class WeightedDecision {
        final DecisionResult result;
        final double fusedWeight;
        final double dynamicWeight;

        WeightedDecision(DecisionResult result, double fusedWeight, double dynamicWeight) {
            this.result = result;
            this.fusedWeight = fusedWeight;
            this.dynamicWeight = dynamicWeight;
        }
    }

    /** 决策类型优先级（值越小优先级越高），用于同权重时定序 */
    private static int typePriority(String type) {
        switch (type) {
            case "RTL":            return 0;
            case "EMERGENCY_LAND": return 1;
            case "AVOID":          return 2;
            case "ADAPT_PATH":     return 3;
            default:               return 9;
        }
    }

    /** 将返航/紧急降落决策提升到列表首位（决策树 FORCE_RTL 修正） */
    private void promoteRtlToFront(List<WeightedDecision> weighted) {
        int rtlIdx = -1;
        for (int i = 0; i < weighted.size(); i++) {
            String t = weighted.get(i).result.decisionType;
            if ("RTL".equals(t) || "EMERGENCY_LAND".equals(t)) {
                rtlIdx = i;
                break;
            }
        }
        if (rtlIdx > 0) {
            WeightedDecision rtl = weighted.remove(rtlIdx);
            weighted.add(0, rtl);
        }
    }

    /** 记录一条决策日志（环形缓冲，超过容量丢弃最旧） */
    private void recordLog(DecisionContext ctx, double rtlWeight, double avoidWeight,
                           double adaptWeight, DecisionTree.TreeResult treeResult,
                           DecisionResult primary, int candidateCount) {
        synchronized (decisionLog) {
            long seq = ++logSeq;
            DecisionLogEntry entry = new DecisionLogEntry(
                    seq, System.currentTimeMillis(), ctx,
                    rtlWeight, avoidWeight, adaptWeight, treeResult.path,
                    primary == null ? "NONE" : primary.decisionType,
                    primary == null ? "" : primary.reason,
                    candidateCount);
            decisionLog.add(entry);
            // 环形缓冲：超容量丢弃最旧
            while (decisionLog.size() > LOG_CAPACITY) {
                decisionLog.remove(0);
            }
        }
    }
}
