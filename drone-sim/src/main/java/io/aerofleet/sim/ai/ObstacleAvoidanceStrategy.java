package io.aerofleet.sim.ai;

/** M11 自动避障策略 */
public class ObstacleAvoidanceStrategy {
    public DecisionResult evaluate(boolean obstacleDetected, double alt) {
        if (obstacleDetected) {
            if (alt < 50) {
                return new DecisionResult("AVOID", "obstacle ahead, climb", alt, 0.75);
            } else {
                return new DecisionResult("AVOID", "obstacle ahead, reroute", 0, 0.7);
            }
        }
        return null;
    }
}
