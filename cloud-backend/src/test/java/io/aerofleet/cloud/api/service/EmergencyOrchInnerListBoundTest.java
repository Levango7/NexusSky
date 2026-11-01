package io.aerofleet.cloud.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmergencyOrchService} 单计划内部列表的**有界性**回归测试。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * #223 收口的是「内存里能有几个计划」，这里收口的是「一个计划内部能长多大」：
 *
 * <ul>
 *   <li>{@code events}：阶段切换/优先级/重规划都往里追加，永不清；</li>
 *   <li>{@code deployments}：30046 覆盖上报是**周期性**的（每架机反复上报），
 *       同一架机会被反复记录，永不清。</li>
 * </ul>
 *
 * 两者都随计划存活时间线性增长，而整份计划还要随 V37 快照**整体 JSON 写穿/读穿**、
 * 并经 WebSocket 推送——列表越长，每次写库与推送的负载越大。
 *
 * <h2>本类证明的不变式</h2>
 *
 * 两个列表各自最多保留最新 N 条；最旧的先丢，最新的必在；原有读取语义不变
 * （{@code getProgress} / {@code getCoverage} 仍返回完整快照副本）。
 */
@DisplayName("EmergencyOrchService 单计划内部列表有界")
class EmergencyOrchInnerListBoundTest {

    private final ApplicationEventPublisher noopPublisher = event -> { };

    private EmergencyOrchService newService() {
        return new EmergencyOrchService(null, noopPublisher);
    }

    private long startPlan(EmergencyOrchService svc) {
        return svc.start(0, 399000000, 1163000000, 1500, List.of(1, 2));
    }

    @SuppressWarnings("unchecked")
    private List<String> eventsOf(EmergencyOrchService svc, long planId) {
        return (List<String>) svc.getPlan(planId).get("events");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deploymentsOf(EmergencyOrchService svc, long planId) {
        return (List<Map<String, Object>>) svc.getPlan(planId).get("deployments");
    }

    @Test
    @DisplayName("阶段/优先级事件超上限后只留最新 N 条，最旧的被丢弃")
    void eventsAreBounded() {
        EmergencyOrchService svc = newService();
        long planId = startPlan(svc);

        int extra = 25;
        for (int i = 0; i < EmergencyOrchService.MAX_PLAN_EVENTS + extra; i++) {
            svc.onEmergencyPriority(planId, 1000 + i, 2, 1, 0, "burst-" + i);
        }

        List<String> events = eventsOf(svc, planId);
        assertThat(events)
                .as("事件列表必须有界")
                .hasSize(EmergencyOrchService.MAX_PLAN_EVENTS);
        assertThat(events)
                .as("最新的那条必须保留")
                .last().asString().contains("burst-" + (EmergencyOrchService.MAX_PLAN_EVENTS + extra - 1));
        assertThat(events)
                .as("最早的 burst 事件应已被丢弃")
                .noneMatch(e -> e.contains("burst-0 ") || e.endsWith("burst-0"));
    }

    @Test
    @DisplayName("覆盖部署（周期上报）超上限后只留最新 N 条，防同一架机反复上报把列表撑大")
    void deploymentsAreBounded() {
        EmergencyOrchService svc = newService();
        long planId = startPlan(svc);

        int extra = 15;
        int total = EmergencyOrchService.MAX_PLAN_DEPLOYMENTS + extra;
        for (int i = 0; i < total; i++) {
            // 同一架机反复上报：这正是真实场景（30046 周期上报）
            svc.onCoverageOptimization(planId, 1, 0, 0, 20, 40 + i, 80);
        }

        List<Map<String, Object>> deployments = deploymentsOf(svc, planId);
        assertThat(deployments)
                .as("部署列表必须有界")
                .hasSize(EmergencyOrchService.MAX_PLAN_DEPLOYMENTS);
        assertThat(deployments.get(deployments.size() - 1).get("expectedCoverage"))
                .as("最新一次上报必须在")
                .isEqualTo(40 + total - 1);
    }

    @Test
    @DisplayName("读取语义不变：getProgress/getCoverage 仍返回完整副本，且与内部列表同长")
    void readApisStillReturnConsistentSnapshots() {
        EmergencyOrchService svc = newService();
        long planId = startPlan(svc);

        for (int i = 0; i < EmergencyOrchService.MAX_PLAN_DEPLOYMENTS + 5; i++) {
            svc.onCoverageOptimization(planId, 1, 0, 0, 20, 10, 80);
        }

        @SuppressWarnings("unchecked")
        List<Object> deployments = (List<Object>) svc.getCoverage(planId).get("deployments");
        assertThat(deployments)
                .as("getCoverage 必须与内部列表同长（截断对调用方透明）")
                .hasSize(EmergencyOrchService.MAX_PLAN_DEPLOYMENTS);

        assertThat(svc.getProgress(planId).get("events"))
                .as("getProgress 仍返回 events 列表")
                .isNotNull();
    }
}
