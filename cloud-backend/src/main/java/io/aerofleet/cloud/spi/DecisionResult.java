package io.aerofleet.cloud.spi;

/**
 * AI 决策结果，由 {@link DecisionStrategyPlugin#evaluate(DecisionContext)} 返回。
 * <p>
 * 描述策略给出的决策动作、置信度和附加建议。
 */
public class DecisionResult {

    /** 决策动作类型（如 "rtl"、"avoid"、"adapt-path"、"emergency-land"） */
    private final String action;

    /** 决策置信度（0.0-1.0） */
    private final double confidence;

    /** 决策说明 */
    private final String reason;

    /** 建议参数（可传递给执行层） */
    private final java.util.Map<String, Object> params;

    public DecisionResult(String action, double confidence, String reason,
                          java.util.Map<String, Object> params) {
        this.action = action;
        this.confidence = confidence;
        this.reason = reason;
        this.params = params != null ? params : java.util.Collections.emptyMap();
    }

    public String getAction() {
        return action;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getReason() {
        return reason;
    }

    public java.util.Map<String, Object> getParams() {
        return params;
    }

    @Override
    public String toString() {
        return "DecisionResult{action='" + action + "', confidence=" + confidence
                + ", reason='" + reason + "', params=" + params + '}';
    }
}