package io.aerofleet.cloud.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DecisionMonitorService 决策监控服务单测（M11）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖决策记录/查询/聚合。
 */
@DisplayName("DecisionMonitorService 决策监控 (M11)")
class DecisionMonitorServiceTest {

    private DecisionMonitorService service;

    @BeforeEach
    void setUp() {
        service = new DecisionMonitorService();
    }

    @Test
    @DisplayName("recordDecision 后 getDecisions 返回该 sysid 的决策历史")
    void recordDecisionStoresBySysid() {
        service.recordDecision(1, "RTL", "low battery", 0.9);

        List<Map<String, Object>> decisions = service.getDecisions(1);
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).get("type")).isEqualTo("RTL");
        assertThat(decisions.get(0).get("reason")).isEqualTo("low battery");
        assertThat(decisions.get(0).get("confidence")).isEqualTo(0.9);
        assertThat(decisions.get(0).get("timestamp")).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("getDecisions 未记录的 sysid 返回空列表")
    void getDecisionsEmptyForUnknownSysid() {
        List<Map<String, Object>> decisions = service.getDecisions(99);
        assertThat(decisions).isEmpty();
    }

    @Test
    @DisplayName("recordDecision 同一 sysid 多次记录累积保留")
    void recordDecisionAccumulatesForSameSysid() {
        service.recordDecision(1, "RTL", "low battery", 0.9);
        service.recordDecision(1, "AVOID", "obstacle ahead", 0.75);
        service.recordDecision(1, "ADAPT_PATH", "strong wind", 0.6);

        List<Map<String, Object>> decisions = service.getDecisions(1);
        assertThat(decisions).hasSize(3);
        assertThat(decisions).extracting(m -> m.get("type"))
                .containsExactly("RTL", "AVOID", "ADAPT_PATH");
    }

    @Test
    @DisplayName("recordDecision 不同 sysid 独立存储")
    void recordDecisionSeparatesBySysid() {
        service.recordDecision(1, "RTL", "low battery", 0.9);
        service.recordDecision(2, "AVOID", "obstacle ahead", 0.75);

        assertThat(service.getDecisions(1)).hasSize(1);
        assertThat(service.getDecisions(2)).hasSize(1);
        assertThat(service.getDecisions(1).get(0).get("type")).isEqualTo("RTL");
        assertThat(service.getDecisions(2).get(0).get("type")).isEqualTo("AVOID");
    }

    @Test
    @DisplayName("getAllDecisions 返回所有 sysid 的决策映射")
    void getAllDecisionsReturnsAllSysids() {
        service.recordDecision(1, "RTL", "low battery", 0.9);
        service.recordDecision(2, "AVOID", "obstacle ahead", 0.75);

        Map<Integer, List<Map<String, Object>>> all = service.getAllDecisions();
        assertThat(all).hasSize(2);
        assertThat(all.keySet()).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("getAllDecisions 初始为空")
    void getAllDecisionsEmptyInitially() {
        assertThat(service.getAllDecisions()).isEmpty();
    }

    @Test
    @DisplayName("getAllDecisions 返回不可修改视图")
    void getAllDecisionsIsUnmodifiable() {
        Map<Integer, List<Map<String, Object>>> all = service.getAllDecisions();
        assertThatThrownBy(() -> all.put(99, List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("recordDecision 置信度边界值 0 与 1 均可存储")
    void recordDecisionConfidenceBoundaries() {
        service.recordDecision(1, "IDLE", "no issue", 0.0);
        service.recordDecision(1, "EMERGENCY", "critical", 1.0);

        List<Map<String, Object>> decisions = service.getDecisions(1);
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).get("confidence")).isEqualTo(0.0);
        assertThat(decisions.get(1).get("confidence")).isEqualTo(1.0);
    }
}