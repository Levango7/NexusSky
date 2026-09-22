package io.aerofleet.cloud.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmergencyOrchService 业务逻辑单测（M9 应急任务编排，FR-30）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖启动/中止/上报/查询/重规划/优先级/场景预设。
 */
@DisplayName("EmergencyOrchService 应急编排服务 (FR-30)")
class EmergencyOrchServiceTest {

    private final ApplicationEventPublisher noopPublisher = event -> { };

    private EmergencyOrchService newService() {
        return new EmergencyOrchService(null, noopPublisher);
    }

    @Test
    @DisplayName("start 创建计划并返回 planId")
    void startCreatesPlan() {
        EmergencyOrchService svc = newService();
        long planId = svc.start(0, 399000000, 1163000000, 5000, List.of(1, 2, 3));

        assertThat(planId).isPositive();
        Map<String, Object> plan = svc.getPlan(planId);
        assertThat(plan).isNotNull();
        assertThat(plan.get("scenarioType")).isEqualTo(0);
        assertThat(plan.get("status")).isEqualTo("RUNNING");
        assertThat(plan.get("radius")).isEqualTo(5000);
        assertThat(plan.get("droneCount")).isEqualTo(3);
        assertThat(plan.get("phase")).isEqualTo(0);
        assertThat(plan.get("phaseStatus")).isEqualTo(1);
    }

    @Test
    @DisplayName("start 多次调用返回不同 planId（递增）")
    void startReturnsIncrementingPlanIds() {
        EmergencyOrchService svc = newService();
        long id1 = svc.start(0, 0, 0, 1000, List.of(1));
        long id2 = svc.start(1, 0, 0, 1000, List.of(2));
        long id3 = svc.start(2, 0, 0, 1000, List.of(3));
        assertThat(id2).isGreaterThan(id1);
        assertThat(id3).isGreaterThan(id2);
    }

    @Test
    @DisplayName("getPlan 未知的 planId 返回 null")
    void getPlanReturnsNullForUnknown() {
        EmergencyOrchService svc = newService();
        assertThat(svc.getPlan(99999)).isNull();
    }

    @Test
    @DisplayName("abort 中止计划后状态为 ABORTED")
    void abortSetsAbortedStatus() {
        EmergencyOrchService svc = newService();
        long planId = svc.start(0, 0, 0, 1000, List.of(1));

        boolean ok = svc.abort(planId);
        assertThat(ok).isTrue();
        Map<String, Object> plan = svc.getPlan(planId);
        assertThat(plan.get("status")).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("abort 未知的 planId 返回 false")
    void abortReturnsFalseForUnknown() {
        EmergencyOrchService svc = newService();
        assertThat(svc.abort(88888)).isFalse();
    }

    @Test
    @DisplayName("onEmergencyMissionPlan 更新计划阶段与覆盖率")
    void onEmergencyMissionPlanUpdatesState() {
        EmergencyOrchService svc = newService();
        long planId = svc.start(0, 399000000, 1163000000, 5000, List.of(1, 2, 3));

        svc.onEmergencyMissionPlan(planId, 0, 2, 1, 12, 85, 92, 1);

        Map<String, Object> plan = svc.getPlan(planId);
        assertThat(plan.get("phase")).isEqualTo(2);
        assertThat(plan.get("phaseStatus")).isEqualTo(1);
        assertThat(plan.get("droneCount")).isEqualTo(12);
        assertThat(plan.get("coverageRate")).isEqualTo(85);
        assertThat(plan.get("connectRate")).isEqualTo(92);
        assertThat(plan.get("priority")).isEqualTo(1);
    }

    @Test
    @DisplayName("onEmergencyMissionPlan 对未知 planId 隐式创建计划")
    void onEmergencyMissionPlanCreatesImplicitly() {
        EmergencyOrchService svc = newService();
        long implicitPlanId = 55555L;

        svc.onEmergencyMissionPlan(implicitPlanId, 1, 0, 1, 5, 10, 80, 2);

        Map<String, Object> plan = svc.getPlan(implicitPlanId);
        assertThat(plan).isNotNull();
        assertThat(plan.get("scenarioType")).isEqualTo(1);
        assertThat(plan.get("coverageRate")).isEqualTo(10);
    }

    @Test
    @DisplayName("onCoverageOptimization 记录部署方案")
    void onCoverageOptimizationRecordsDeployment() {
        EmergencyOrchService svc = newService();
        long planId = svc.start(0, 0, 0, 1000, List.of(1, 2));

        svc.onCoverageOptimization(planId, 1, 1, 2, 20, 60, 80);

        Map<String, Object> coverage = svc.getCoverage(planId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deployments = (List<Map<String, Object>>) coverage.get("deployments");
        assertThat(deployments).hasSize(1);
        assertThat(deployments.get(0).get("droneId")).isEqualTo(1);
        assertThat(deployments.get(0).get("cellType")).isEqualTo("LTE");
        assertThat(deployments.get(0).get("relayRole")).isEqualTo("HAPS");
        assertThat(deployments.get(0).get("expectedCoverage")).isEqualTo(60);
    }

    @Test
    @DisplayName("onEmergencyPriority 记录优先级调度事件")
    void onEmergencyPriorityRecordsEvent() {
        EmergencyOrchService svc = newService();
        long planId = svc.start(0, 0, 0, 1000, List.of(1, 2));

        svc.onEmergencyPriority(planId, 67890L, 1, 0, 0L, "search rescue");

        Map<String, Object> queue = svc.getPriorityQueue(planId);
        @SuppressWarnings("unchecked")
        Map<String, List<Long>> queues = (Map<String, List<Long>>) queue.get("queues");
        assertThat(queues.get("SEARCH_RESCUE")).contains(67890L);
    }

    @Test
    @DisplayName("getScenarios 返回 4 种预设")
    void getScenariosReturnsFour() {
        EmergencyOrchService svc = newService();
        List<Map<String, Object>> scenarios = svc.getScenarios();

        assertThat(scenarios).hasSize(4);
        assertThat(scenarios.get(0).get("type")).isEqualTo(0);
        assertThat(scenarios.get(0).get("name")).isEqualTo("地震");
        assertThat(scenarios.get(1).get("type")).isEqualTo(1);
        assertThat(scenarios.get(1).get("name")).isEqualTo("泥石流");
        assertThat(scenarios.get(2).get("type")).isEqualTo(2);
        assertThat(scenarios.get(2).get("name")).isEqualTo("火灾");
        assertThat(scenarios.get(3).get("type")).isEqualTo(3);
        assertThat(scenarios.get(3).get("name")).isEqualTo("自定义");
    }

    @Test
    @DisplayName("getScenarios 预设含 defaults 字段")
    void getScenariosContainsDefaults() {
        EmergencyOrchService svc = newService();
        List<Map<String, Object>> scenarios = svc.getScenarios();

        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) scenarios.get(0).get("defaults");
        assertThat(defaults).containsKeys("radius", "droneCount", "priority");
        assertThat(defaults.get("radius")).isEqualTo(5000);
        assertThat(defaults.get("droneCount")).isEqualTo(12);
    }

    @Test
    @DisplayName("startScenario 加载预设并启动")
    void startScenarioLoadsPresetAndStarts() {
        EmergencyOrchService svc = newService();
        long planId = svc.startScenario(0, 399000000, 1163000000, 0, List.of(1, 2, 3));

        assertThat(planId).isPositive();
        Map<String, Object> plan = svc.getPlan(planId);
        assertThat(plan.get("scenarioType")).isEqualTo(0);
        assertThat(plan.get("radius")).isEqualTo(5000); // 预设默认半径
    }

    @Test
    @DisplayName("startScenario 未知的类型返回 -1")
    void startScenarioReturnsNegativeForUnknownType() {
        EmergencyOrchService svc = newService();
        long planId = svc.startScenario(99, 0, 0, 0, List.of(1));
        assertThat(planId).isEqualTo(-1);
    }

    @Test
    @DisplayName("replan 基于已有计划创建新计划")
    void replanCreatesNewPlan() {
        EmergencyOrchService svc = newService();
        long planId = svc.start(0, 399000000, 1163000000, 5000, List.of(1, 2));

        long newPlanId = svc.replan(planId, "drone lost");
        assertThat(newPlanId).isGreaterThan(planId);

        Map<String, Object> newPlan = svc.getPlan(newPlanId);
        assertThat(newPlan).isNotNull();
        assertThat(newPlan.get("scenarioType")).isEqualTo(0);
        assertThat(newPlan.get("radius")).isEqualTo(5000);
    }

    @Test
    @DisplayName("replan 未知的 planId 返回 -1")
    void replanReturnsNegativeForUnknown() {
        EmergencyOrchService svc = newService();
        assertThat(svc.replan(77777, "test")).isEqualTo(-1);
    }

    @Test
    @DisplayName("adjustPriority 调整任务优先级")
    void adjustPriorityChangesPriority() {
        EmergencyOrchService svc = newService();
        long planId = svc.start(0, 0, 0, 1000, List.of(1));

        Map<String, Object> result = svc.adjustPriority(planId, 67890L, 1, "search rescue");
        assertThat(result).isNotNull();
        assertThat(result.get("taskId")).isEqualTo(67890L);
        assertThat(result.get("oldPriority")).isEqualTo(4); // 默认常规
        assertThat(result.get("newPriority")).isEqualTo(1);
    }

    @Test
    @DisplayName("adjustPriority 未知的 planId 返回 null")
    void adjustPriorityReturnsNullForUnknown() {
        EmergencyOrchService svc = newService();
        assertThat(svc.adjustPriority(66666, 1L, 1, "test")).isNull();
    }

    @Test
    @DisplayName("getProgress 返回 5 个阶段")
    void getProgressReturnsFivePhases() {
        EmergencyOrchService svc = newService();
        long planId = svc.start(0, 0, 0, 1000, List.of(1));

        Map<String, Object> progress = svc.getProgress(planId);
        @SuppressWarnings("unchecked")
        List<?> phases = (List<?>) progress.get("phases");
        assertThat(phases).hasSize(5);
    }

    @Test
    @DisplayName("getCoverage 返回覆盖信息")
    void getCoverageReturnsCoverageInfo() {
        EmergencyOrchService svc = newService();
        long planId = svc.start(0, 0, 0, 1000, List.of(1));

        Map<String, Object> coverage = svc.getCoverage(planId);
        assertThat(coverage).containsKeys("coverageRate", "connectRate", "deployments", "uncoveredAreas");
    }

    @Test
    @DisplayName("getPriorityQueue 返回 4 级队列")
    void getPriorityQueueReturnsFourQueues() {
        EmergencyOrchService svc = newService();
        long planId = svc.start(0, 0, 0, 1000, List.of(1));

        Map<String, Object> queue = svc.getPriorityQueue(planId);
        @SuppressWarnings("unchecked")
        Map<String, List<Long>> queues = (Map<String, List<Long>>) queue.get("queues");
        assertThat(queues).containsOnlyKeys("SEARCH_RESCUE", "COMMAND", "MAPPING", "ROUTINE");
    }

    @Test
    @DisplayName("scenarioName 正确映射 4 种场景名")
    void scenarioNameMapping() {
        assertThat(EmergencyOrchService.scenarioName(0)).isEqualTo("地震");
        assertThat(EmergencyOrchService.scenarioName(1)).isEqualTo("泥石流");
        assertThat(EmergencyOrchService.scenarioName(2)).isEqualTo("火灾");
        assertThat(EmergencyOrchService.scenarioName(3)).isEqualTo("自定义");
        assertThat(EmergencyOrchService.scenarioName(99)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("phaseStatusName 正确映射阶段状态名")
    void phaseStatusNameMapping() {
        assertThat(EmergencyOrchService.phaseStatusName(0)).isEqualTo("PENDING");
        assertThat(EmergencyOrchService.phaseStatusName(1)).isEqualTo("RUNNING");
        assertThat(EmergencyOrchService.phaseStatusName(2)).isEqualTo("COMPLETED");
        assertThat(EmergencyOrchService.phaseStatusName(3)).isEqualTo("FAILED");
        assertThat(EmergencyOrchService.phaseStatusName(4)).isEqualTo("ABORTED");
    }
}