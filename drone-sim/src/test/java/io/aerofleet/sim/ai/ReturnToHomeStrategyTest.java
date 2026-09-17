package io.aerofleet.sim.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReturnToHomeStrategy 应急返航策略单测（M11）。
 * <p>
 * 纯 JUnit 5，覆盖低电量/链路丢失/GPS 退化/正常无决策等场景。
 */
@DisplayName("ReturnToHomeStrategy 应急返航策略 (M11)")
class ReturnToHomeStrategyTest {

    private ReturnToHomeStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ReturnToHomeStrategy();
    }

    @Test
    @DisplayName("电量低于 25% 触发 RTL，置信度 0.9")
    void lowBatteryTriggersRtl() {
        DecisionResult result = strategy.evaluate(15.0, true, true, 500.0);

        assertThat(result).isNotNull();
        assertThat(result.decisionType).isEqualTo("RTL");
        assertThat(result.reason).isEqualTo("low battery");
        assertThat(result.triggerValue).isEqualTo(15.0);
        assertThat(result.confidence).isEqualTo(0.9);
    }

    @Test
    @DisplayName("电量恰好 25% 不触发低电量 RTL（边界 < 25）")
    void batteryAtThresholdDoesNotTriggerRtl() {
        DecisionResult result = strategy.evaluate(25.0, true, true, 500.0);
        // 25.0 < 25.0 = false，不触发
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("链路丢失触发 RTL，置信度 0.85")
    void linkLostTriggersRtl() {
        DecisionResult result = strategy.evaluate(80.0, false, true, 500.0);

        assertThat(result).isNotNull();
        assertThat(result.decisionType).isEqualTo("RTL");
        assertThat(result.reason).isEqualTo("link lost");
        assertThat(result.confidence).isEqualTo(0.85);
    }

    @Test
    @DisplayName("GPS 退化触发 EMERGENCY_LAND，置信度 0.8")
    void gpsDegradedTriggersEmergencyLand() {
        DecisionResult result = strategy.evaluate(80.0, true, false, 500.0);

        assertThat(result).isNotNull();
        assertThat(result.decisionType).isEqualTo("EMERGENCY_LAND");
        assertThat(result.reason).isEqualTo("GPS degraded");
        assertThat(result.confidence).isEqualTo(0.8);
    }

    @Test
    @DisplayName("全部健康时返回 null（无决策）")
    void allHealthyReturnsNull() {
        DecisionResult result = strategy.evaluate(80.0, true, true, 500.0);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("低电量优先于链路丢失（先检查电量）")
    void lowBatteryTakesPrecedenceOverLinkLost() {
        DecisionResult result = strategy.evaluate(10.0, false, true, 500.0);

        assertThat(result).isNotNull();
        assertThat(result.decisionType).isEqualTo("RTL");
        assertThat(result.reason).isEqualTo("low battery");
    }

    @Test
    @DisplayName("链路丢失优先于 GPS 退化（先检查链路）")
    void linkLostTakesPrecedenceOverGpsDegraded() {
        DecisionResult result = strategy.evaluate(80.0, false, false, 500.0);

        assertThat(result).isNotNull();
        assertThat(result.decisionType).isEqualTo("RTL");
        assertThat(result.reason).isEqualTo("link lost");
    }
}