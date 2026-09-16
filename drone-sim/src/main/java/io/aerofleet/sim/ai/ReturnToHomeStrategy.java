package io.aerofleet.sim.ai;

/** M11 应急返航策略 */
public class ReturnToHomeStrategy {
    private static final double BATTERY_THRESHOLD = 25.0;
    private static final double LINK_TIMEOUT_SEC = 10.0;

    public DecisionResult evaluate(double battery, boolean linkHealthy, boolean gpsHealthy, double distanceToHome) {
        if (battery < BATTERY_THRESHOLD) {
            return new DecisionResult("RTL", "low battery", battery, 0.9);
        }
        if (!linkHealthy) {
            return new DecisionResult("RTL", "link lost", 0, 0.85);
        }
        if (!gpsHealthy) {
            return new DecisionResult("EMERGENCY_LAND", "GPS degraded", 0, 0.8);
        }
        return null;
    }
}
