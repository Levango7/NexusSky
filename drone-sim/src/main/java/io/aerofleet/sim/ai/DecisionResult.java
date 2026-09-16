package io.aerofleet.sim.ai;

public class DecisionResult {
    public final String decisionType; // RTL / AVOID / ADAPT_PATH / EMERGENCY_LAND
    public final String reason;
    public final double triggerValue;
    public final double confidence;

    public DecisionResult(String decisionType, String reason, double triggerValue, double confidence) {
        this.decisionType = decisionType;
        this.reason = reason;
        this.triggerValue = triggerValue;
        this.confidence = confidence;
    }
}
