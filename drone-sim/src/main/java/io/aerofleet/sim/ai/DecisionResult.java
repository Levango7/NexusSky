package io.aerofleet.sim.ai;

import java.util.List;

/**
 * AI 决策结果。
 * <p>
 * 基础字段：{@code decisionType} / {@code reason} / {@code triggerValue} / {@code confidence}
 * 由 M11 决策引擎及其策略（RTL / AVOID / ADAPT_PATH / EMERGENCY_LAND）填充。
 * <p>
 * 扩展字段：{@code path}（避障路径点列表，每点为 {@code [lat, lon, alt]}），
 * 仅在 {@link ObstacleAvoidanceStrategy#avoidWithPath} 等路径规划接口中填充，
 * 旧调用方不受影响（{@code path} 默认为 {@code null}）。
 */
public class DecisionResult {
    public final String decisionType; // RTL / AVOID / ADAPT_PATH / EMERGENCY_LAND
    public final String reason;
    public final double triggerValue;
    public final double confidence;
    /** 避障路径点列表，每点为 [lat, lon, alt]；非路径规划决策时为 null */
    public final List<double[]> path;

    public DecisionResult(String decisionType, String reason, double triggerValue, double confidence) {
        this(decisionType, reason, triggerValue, confidence, null);
    }

    public DecisionResult(String decisionType, String reason, double triggerValue, double confidence,
                          List<double[]> path) {
        this.decisionType = decisionType;
        this.reason = reason;
        this.triggerValue = triggerValue;
        this.confidence = confidence;
        this.path = path;
    }
}
