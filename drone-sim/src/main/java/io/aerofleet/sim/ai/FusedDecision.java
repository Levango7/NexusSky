package io.aerofleet.sim.ai;

import java.util.Collections;
import java.util.List;

/**
 * M11 多策略融合决策结果。
 * <p>
 * 持有主决策（权重最高）、全部候选决策（按融合权重降序）、各策略动态权重、
 * 决策树评估路径以及输入上下文快照，便于日志回溯与测试断言。
 */
public final class FusedDecision {

    /** 主决策（融合权重最高），无任何候选时为 null */
    public final DecisionResult primary;
    /** 全部候选决策，按融合权重降序排列（已应用动态权重与优先级） */
    public final List<DecisionResult> ranked;
    /** 返航策略动态权重（基础×调整系数） */
    public final double rtlWeight;
    /** 避障策略动态权重 */
    public final double avoidWeight;
    /** 自适应航线策略动态权重 */
    public final double adaptWeight;
    /** 决策树评估得到的路径描述 */
    public final String decisionTreePath;
    /** 输入上下文快照 */
    public final DecisionContext context;

    public FusedDecision(DecisionResult primary, List<DecisionResult> ranked,
                         double rtlWeight, double avoidWeight, double adaptWeight,
                         String decisionTreePath, DecisionContext context) {
        this.primary = primary;
        this.ranked = Collections.unmodifiableList(ranked);
        this.rtlWeight = rtlWeight;
        this.avoidWeight = avoidWeight;
        this.adaptWeight = adaptWeight;
        this.decisionTreePath = decisionTreePath;
        this.context = context;
    }

    /** 是否产生了至少一个决策 */
    public boolean hasDecision() {
        return primary != null;
    }

    @Override
    public String toString() {
        return "FusedDecision{primary=" + (primary == null ? "NONE" : primary.decisionType)
                + ", ranked=" + ranked.size()
                + ", w=[rtl=" + rtlWeight + ", avoid=" + avoidWeight + ", adapt=" + adaptWeight + "]"
                + ", tree=" + decisionTreePath + "}";
    }
}