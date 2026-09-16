package io.aerofleet.sim.ai;

/** M11 自适应航线策略 */
public class AdaptivePathStrategy {
    private static final double WIND_THRESHOLD = 8.0; // m/s

    public DecisionResult evaluate(double windSpeed, double battery) {
        if (windSpeed > WIND_THRESHOLD) {
            return new DecisionResult("ADAPT_PATH", "strong wind", windSpeed, 0.6);
        }
        if (battery < 40.0) {
            return new DecisionResult("ADAPT_PATH", "battery optimization", battery, 0.5);
        }
        return null;
    }
}
