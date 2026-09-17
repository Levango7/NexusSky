package io.aerofleet.sim.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ObstacleAvoidanceStrategy 自动避障策略单测（M11）。
 * <p>
 * 纯 JUnit 5，覆盖有/无障碍物 + 高度分层决策。
 */
@DisplayName("ObstacleAvoidanceStrategy 自动避障策略 (M11)")
class ObstacleAvoidanceStrategyTest {

    private ObstacleAvoidanceStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ObstacleAvoidanceStrategy();
    }

    @Test
    @DisplayName("无障碍物时返回 null（无决策）")
    void noObstacleReturnsNull() {
        DecisionResult result = strategy.evaluate(false, 100.0);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("有障碍物且高度 < 50 时触发爬升 AVOID，置信度 0.75")
    void obstacleAtLowAltitudeTriggersClimb() {
        DecisionResult result = strategy.evaluate(true, 30.0);

        assertThat(result).isNotNull();
        assertThat(result.decisionType).isEqualTo("AVOID");
        assertThat(result.reason).isEqualTo("obstacle ahead, climb");
        assertThat(result.triggerValue).isEqualTo(30.0);
        assertThat(result.confidence).isEqualTo(0.75);
    }

    @Test
    @DisplayName("有障碍物且高度 >= 50 时触发重规划 AVOID，置信度 0.7")
    void obstacleAtHighAltitudeTriggersReroute() {
        DecisionResult result = strategy.evaluate(true, 80.0);

        assertThat(result).isNotNull();
        assertThat(result.decisionType).isEqualTo("AVOID");
        assertThat(result.reason).isEqualTo("obstacle ahead, reroute");
        assertThat(result.confidence).isEqualTo(0.7);
    }

    @Test
    @DisplayName("高度恰好 50 时走 reroute 分支（边界 alt < 50）")
    void boundaryAlt50TriggersReroute() {
        DecisionResult result = strategy.evaluate(true, 50.0);

        assertThat(result).isNotNull();
        assertThat(result.reason).isEqualTo("obstacle ahead, reroute");
        assertThat(result.confidence).isEqualTo(0.7);
    }

    @Test
    @DisplayName("高度恰好 49 时走 climb 分支")
    void boundaryAlt49TriggersClimb() {
        DecisionResult result = strategy.evaluate(true, 49.0);

        assertThat(result).isNotNull();
        assertThat(result.reason).isEqualTo("obstacle ahead, climb");
        assertThat(result.confidence).isEqualTo(0.75);
    }

    @Test
    @DisplayName("爬升决策 triggerValue 为当前高度")
    void climbDecisionCarriesAltitude() {
        DecisionResult result = strategy.evaluate(true, 25.0);

        assertThat(result).isNotNull();
        assertThat(result.triggerValue).isEqualTo(25.0);
    }

    @Test
    @DisplayName("重规划决策 triggerValue 为 0")
    void rerouteDecisionZeroTriggerValue() {
        DecisionResult result = strategy.evaluate(true, 100.0);

        assertThat(result).isNotNull();
        assertThat(result.triggerValue).isEqualTo(0.0);
    }
}