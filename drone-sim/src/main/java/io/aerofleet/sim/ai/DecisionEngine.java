package io.aerofleet.sim.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * M11 AI 自主决策引擎：周期评估无人机状态，触发决策。
 * 决策类型：RTL(返航) / AVOID(避障) / ADAPT_PATH(自适应航线) / EMERGENCY_LAND(紧急降落)
 */
public class DecisionEngine {

    private final ReturnToHomeStrategy rtlStrategy = new ReturnToHomeStrategy();
    private final ObstacleAvoidanceStrategy avoidStrategy = new ObstacleAvoidanceStrategy();
    private final AdaptivePathStrategy adaptStrategy = new AdaptivePathStrategy();

    /** 评估无人机状态，返回需要执行的决策列表 */
    public List<DecisionResult> evaluate(double battery, boolean linkHealthy, boolean gpsHealthy,
                                         double alt, double distanceToHome, boolean obstacleDetected,
                                         double windSpeed) {
        List<DecisionResult> decisions = new ArrayList<>();

        DecisionResult rtl = rtlStrategy.evaluate(battery, linkHealthy, gpsHealthy, distanceToHome);
        if (rtl != null) decisions.add(rtl);

        DecisionResult avoid = avoidStrategy.evaluate(obstacleDetected, alt);
        if (avoid != null) decisions.add(avoid);

        DecisionResult adapt = adaptStrategy.evaluate(windSpeed, battery);
        if (adapt != null) decisions.add(adapt);

        if (!decisions.isEmpty()) {
            System.out.println("[ai] Decisions triggered: count=" + decisions.size() + " top=" + decisions.get(0).decisionType);
        }
        return decisions;
    }
}
