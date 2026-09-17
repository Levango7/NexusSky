package io.aerofleet.sim.ai;

/**
 * M11 决策日志条目：记录单次评估的输入、权重、决策树路径与选择结果，便于回溯分析。
 * <p>
 * 不可变对象，线程安全。
 */
public final class DecisionLogEntry {

    /** 序号（自增） */
    public final long seq;
    /** 时间戳（System.currentTimeMillis） */
    public final long timestamp;
    /** 输入上下文快照 */
    public final DecisionContext context;
    /** 返航策略动态权重 */
    public final double rtlWeight;
    /** 避障策略动态权重 */
    public final double avoidWeight;
    /** 自适应航线策略动态权重 */
    public final double adaptWeight;
    /** 决策树路径 */
    public final String decisionTreePath;
    /** 主决策类型（无决策时为 "NONE"） */
    public final String primaryDecision;
    /** 主决策原因 */
    public final String primaryReason;
    /** 候选决策数量 */
    public final int candidateCount;

    public DecisionLogEntry(long seq, long timestamp, DecisionContext context,
                            double rtlWeight, double avoidWeight, double adaptWeight,
                            String decisionTreePath, String primaryDecision,
                            String primaryReason, int candidateCount) {
        this.seq = seq;
        this.timestamp = timestamp;
        this.context = context;
        this.rtlWeight = rtlWeight;
        this.avoidWeight = avoidWeight;
        this.adaptWeight = adaptWeight;
        this.decisionTreePath = decisionTreePath;
        this.primaryDecision = primaryDecision;
        this.primaryReason = primaryReason;
        this.candidateCount = candidateCount;
    }

    @Override
    public String toString() {
        return "[log#" + seq + "] " + primaryDecision
                + " | path=" + decisionTreePath
                + " | w=[rtl=" + rtlWeight + ",avoid=" + avoidWeight + ",adapt=" + adaptWeight + "]"
                + " | candidates=" + candidateCount
                + " | ctx=" + context;
    }
}