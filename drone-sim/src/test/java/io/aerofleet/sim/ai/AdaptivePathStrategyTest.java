package io.aerofleet.sim.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdaptivePathStrategy 自适应航线策略单测（M11）。
 * <p>
 * 纯 JUnit 5，覆盖强风/低电量/正常无决策 + 边界。
 */
@DisplayName("AdaptivePathStrategy 自适应航线策略 (M11)")
class AdaptivePathStrategyTest {

    private AdaptivePathStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new AdaptivePathStrategy();
    }

    @Test
    @DisplayName("风速 > 8 m/s 触发 ADAPT_PATH，reason=strong wind，置信度 0.6")
    void strongWindTriggersAdaptPath() {
        DecisionResult result = strategy.evaluate(12.0, 80.0);

        assertThat(result).isNotNull();
        assertThat(result.decisionType).isEqualTo("ADAPT_PATH");
        assertThat(result.reason).isEqualTo("strong wind");
        assertThat(result.triggerValue).isEqualTo(12.0);
        assertThat(result.confidence).isEqualTo(0.6);
    }

    @Test
    @DisplayName("风速恰好 8 m/s 不触发强风决策（边界 wind > 8）")
    void boundaryWind8DoesNotTriggerStrongWind() {
        DecisionResult result = strategy.evaluate(8.0, 80.0);
        // 8.0 > 8.0 = false，不触发强风；电量 80 >= 40，也不触发
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("电量 < 40 触发 ADAPT_PATH，reason=battery optimization，置信度 0.5")
    void lowBatteryTriggersAdaptPath() {
        DecisionResult result = strategy.evaluate(5.0, 30.0);

        assertThat(result).isNotNull();
        assertThat(result.decisionType).isEqualTo("ADAPT_PATH");
        assertThat(result.reason).isEqualTo("battery optimization");
        assertThat(result.triggerValue).isEqualTo(30.0);
        assertThat(result.confidence).isEqualTo(0.5);
    }

    @Test
    @DisplayName("电量恰好 40 不触发低电量决策（边界 battery < 40）")
    void boundaryBattery40DoesNotTrigger() {
        DecisionResult result = strategy.evaluate(5.0, 40.0);
        // 40.0 < 40.0 = false
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("风速和电量都正常时返回 null（无决策）")
    void normalConditionsReturnNull() {
        DecisionResult result = strategy.evaluate(5.0, 80.0);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("强风优先于低电量（先检查风速）")
    void strongWindTakesPrecedenceOverLowBattery() {
        DecisionResult result = strategy.evaluate(15.0, 20.0);

        assertThat(result).isNotNull();
        assertThat(result.reason).isEqualTo("strong wind");
    }

    @Test
    @DisplayName("低风速 + 低电量触发 battery optimization")
    void lowWindLowBatteryTriggersBatteryOptimization() {
        DecisionResult result = strategy.evaluate(3.0, 20.0);

        assertThat(result).isNotNull();
        assertThat(result.reason).isEqualTo("battery optimization");
        assertThat(result.triggerValue).isEqualTo(20.0);
    }
}