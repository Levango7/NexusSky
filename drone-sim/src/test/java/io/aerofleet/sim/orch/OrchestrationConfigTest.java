package io.aerofleet.sim.orch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrchestrationConfig 单测（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 覆盖默认值正确性、参数验证（非法值抛 IllegalArgumentException）、defaults() 有效性。
 */
@DisplayName("OrchestrationConfig 编排配置 (M9 T2)")
class OrchestrationConfigTest {

    @Test
    @DisplayName("defaults() 返回有效配置，各字段符合设计值")
    void defaultsReturnsValidConfig() {
        OrchestrationConfig c = OrchestrationConfig.defaults();

        assertThat(c.enabled).isFalse();
        assertThat(c.maxConcurrentPlans).isEqualTo(10);
        assertThat(c.maxDrones).isEqualTo(50);
        assertThat(c.heartbeatTimeoutMs).isEqualTo(30000L);
        assertThat(c.lowBatteryThreshold).isEqualTo(30);
        assertThat(c.criticalBatteryThreshold).isEqualTo(15);
        assertThat(c.maxRetryPerPhase).isEqualTo(3);
        assertThat(c.coverageOptStepM).isEqualTo(500.0);
        assertThat(c.localOptRangeM).isEqualTo(100.0);
        assertThat(c.localOptIterations).isEqualTo(3);
        assertThat(c.meshOneHopRangeM).isEqualTo(2000.0);
        assertThat(c.coverageDeclineReplanThreshold).isEqualTo(10.0);
    }

    @Test
    @DisplayName("defaults() toString 包含关键字段")
    void defaultsToString() {
        OrchestrationConfig c = OrchestrationConfig.defaults();
        String s = c.toString();
        assertThat(s).contains("enabled=false")
                .contains("maxDrones=50")
                .contains("heartbeatTimeout=30000ms");
    }

    @Test
    @DisplayName("maxConcurrentPlans <= 0 抛 IllegalArgumentException")
    void invalidMaxConcurrentPlans() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 0, 50, 30000L, 30, 15, 3, 500.0, 100.0, 3, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrentPlans");
    }

    @Test
    @DisplayName("maxDrones <= 0 抛 IllegalArgumentException")
    void invalidMaxDrones() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 0, 30000L, 30, 15, 3, 500.0, 100.0, 3, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDrones");
    }

    @Test
    @DisplayName("heartbeatTimeoutMs <= 0 抛 IllegalArgumentException")
    void invalidHeartbeatTimeout() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 0L, 30, 15, 3, 500.0, 100.0, 3, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeatTimeoutMs");
    }

    @Test
    @DisplayName("lowBatteryThreshold 越界抛 IllegalArgumentException")
    void invalidLowBatteryThreshold() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 30000L, 101, 15, 3, 500.0, 100.0, 3, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowBatteryThreshold");
    }

    @Test
    @DisplayName("criticalBatteryThreshold 越界抛 IllegalArgumentException")
    void invalidCriticalBatteryThreshold() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 30000L, 30, -1, 3, 500.0, 100.0, 3, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("criticalBatteryThreshold");
    }

    @Test
    @DisplayName("criticalBatteryThreshold > lowBatteryThreshold 抛 IllegalArgumentException")
    void criticalGreaterThanLow() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 30000L, 15, 30, 3, 500.0, 100.0, 3, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("criticalBatteryThreshold");
    }

    @Test
    @DisplayName("maxRetryPerPhase <= 0 抛 IllegalArgumentException")
    void invalidMaxRetry() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 30000L, 30, 15, 0, 500.0, 100.0, 3, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetryPerPhase");
    }

    @Test
    @DisplayName("coverageOptStepM <= 0 抛 IllegalArgumentException")
    void invalidCoverageOptStep() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 30000L, 30, 15, 3, 0.0, 100.0, 3, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverageOptStepM");
    }

    @Test
    @DisplayName("localOptRangeM <= 0 抛 IllegalArgumentException")
    void invalidLocalOptRange() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 30000L, 30, 15, 3, 500.0, -1.0, 3, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localOptRangeM");
    }

    @Test
    @DisplayName("localOptIterations <= 0 抛 IllegalArgumentException")
    void invalidLocalOptIterations() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 30000L, 30, 15, 3, 500.0, 100.0, 0, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localOptIterations");
    }

    @Test
    @DisplayName("meshOneHopRangeM <= 0 抛 IllegalArgumentException")
    void invalidMeshOneHopRange() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 30000L, 30, 15, 3, 500.0, 100.0, 3, 0.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("meshOneHopRangeM");
    }

    @Test
    @DisplayName("coverageDeclineReplanThreshold < 0 抛 IllegalArgumentException")
    void invalidCoverageDeclineThreshold() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 30000L, 30, 15, 3, 500.0, 100.0, 3, 2000.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverageDeclineReplanThreshold");
    }

    @Test
    @DisplayName("NaN 数值参数抛 IllegalArgumentException")
    void nanValuesRejected() {
        assertThatThrownBy(() -> new OrchestrationConfig(
                true, 10, 50, 30000L, 30, 15, 3, Double.NaN, 100.0, 3, 2000.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("合法自定义配置正常构造")
    void validCustomConfig() {
        OrchestrationConfig c = new OrchestrationConfig(
                true, 5, 20, 10000L, 40, 10, 2, 300.0, 80.0, 5, 1500.0, 15.0);
        assertThat(c.enabled).isTrue();
        assertThat(c.maxConcurrentPlans).isEqualTo(5);
        assertThat(c.maxDrones).isEqualTo(20);
        assertThat(c.maxRetryPerPhase).isEqualTo(2);
    }
}