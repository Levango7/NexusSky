package io.aerofleet.sim.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DecisionEngineTest {
    @Test
    void testLowBatteryTriggersRTL() {
        DecisionEngine engine = new DecisionEngine();
        List<DecisionResult> decisions = engine.evaluate(15.0, true, true, 50.0, 500.0, false, 3.0);
        assertTrue(decisions.stream().anyMatch(d -> "RTL".equals(d.decisionType)));
    }

    @Test
    void testNoDecisionWhenHealthy() {
        DecisionEngine engine = new DecisionEngine();
        List<DecisionResult> decisions = engine.evaluate(80.0, true, true, 50.0, 100.0, false, 2.0);
        assertTrue(decisions.isEmpty());
    }

    @Test
    void testObstacleTriggersAvoid() {
        DecisionEngine engine = new DecisionEngine();
        List<DecisionResult> decisions = engine.evaluate(80.0, true, true, 50.0, 100.0, true, 2.0);
        assertTrue(decisions.stream().anyMatch(d -> "AVOID".equals(d.decisionType)));
    }
}
