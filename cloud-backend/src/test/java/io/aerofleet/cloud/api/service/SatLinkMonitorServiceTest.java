package io.aerofleet.cloud.api.service;

import io.aerofleet.mavlink.messages.HierarchicalRouteDecisionMsg;
import io.aerofleet.mavlink.messages.SatLinkStatusMsg;
import io.aerofleet.mavlink.messages.SatPassScheduleMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SatLinkMonitorService 卫星链路监控服务单测（M7 星-空-地多层级中继，FR-5.4/5.3）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖上报/查询/版本递增/策略设置。
 */
@DisplayName("SatLinkMonitorService 卫星链路监控服务 (FR-5.4/5.3)")
class SatLinkMonitorServiceTest {

    private static SatLinkStatusMsg statusOf(int satId, int visible, int elevation, int delay, int bw) {
        return new SatLinkStatusMsg(satId, visible, elevation, 180, delay, bw,
                100_000L, 3, 50_000L, 1);
    }

    private static SatPassScheduleMsg passOf(int satId, long start, long end, int maxEl) {
        return new SatPassScheduleMsg(satId, start, end, maxEl, 1, 50_000L);
    }

    private static HierarchicalRouteDecisionMsg routeOf(int chosenLayer, int delay, String reason) {
        return new HierarchicalRouteDecisionMsg(0, 4, chosenLayer, delay,
                List.of(1, 200, 2), 0, reason, 50_000L);
    }

    @Test
    @DisplayName("onSatLinkStatus 上报后 getLinkStatus 返回快照")
    void onSatLinkStatusStores() {
        SatLinkMonitorService svc = new SatLinkMonitorService();
        svc.onSatLinkStatus(1, statusOf(10, 1, 45, 80, 25));

        SatLinkMonitorService.SatLinkSnapshot snap = svc.getLinkStatus(10);
        assertThat(snap).isNotNull();
        assertThat(snap.satId()).isEqualTo(10);
        assertThat(snap.visible()).isEqualTo(1);
        assertThat(snap.elevationDeg()).isEqualTo(45);
        assertThat(snap.delayMs()).isEqualTo(80);
        assertThat(snap.bandwidthMbps()).isEqualTo(25);
    }

    @Test
    @DisplayName("getLinkStatus 未上报的 satId 返回 null")
    void getLinkStatusUnknownReturnsNull() {
        SatLinkMonitorService svc = new SatLinkMonitorService();
        assertThat(svc.getLinkStatus(99)).isNull();
    }

    @Test
    @DisplayName("getAllLinkStatuses 按 satId 排序（TreeMap）")
    void getAllLinkStatusesSorted() {
        SatLinkMonitorService svc = new SatLinkMonitorService();
        svc.onSatLinkStatus(1, statusOf(30, 1, 40, 60, 20));
        svc.onSatLinkStatus(1, statusOf(10, 1, 50, 70, 30));
        svc.onSatLinkStatus(1, statusOf(20, 1, 30, 80, 40));

        Map<Integer, SatLinkMonitorService.SatLinkSnapshot> all = svc.getAllLinkStatuses();
        assertThat(all.keySet()).containsExactly(10, 20, 30);
    }

    @Test
    @DisplayName("onSatPassSchedule 上报后 getPassSchedules 返回计划列表")
    void onSatPassScheduleStores() {
        SatLinkMonitorService svc = new SatLinkMonitorService();
        svc.onSatPassSchedule(1, passOf(10, 1000L, 6000L, 75));

        List<SatLinkMonitorService.SatPassSnapshot> passes = svc.getPassSchedules(10);
        assertThat(passes).hasSize(1);
        assertThat(passes.get(0).satId()).isEqualTo(10);
        assertThat(passes.get(0).passStartMs()).isEqualTo(1000L);
        assertThat(passes.get(0).passEndMs()).isEqualTo(6000L);
        assertThat(passes.get(0).maxElevationDeg()).isEqualTo(75);
    }

    @Test
    @DisplayName("getPassSchedules 未上报的 satId 返回空列表")
    void getPassSchedulesUnknownReturnsEmpty() {
        SatLinkMonitorService svc = new SatLinkMonitorService();
        assertThat(svc.getPassSchedules(99)).isEmpty();
    }

    @Test
    @DisplayName("同一卫星多次过境计划累积保留")
    void multiplePassSchedulesAccumulate() {
        SatLinkMonitorService svc = new SatLinkMonitorService();
        svc.onSatPassSchedule(1, passOf(10, 1000L, 6000L, 75));
        svc.onSatPassSchedule(1, passOf(10, 10000L, 15000L, 60));

        List<SatLinkMonitorService.SatPassSnapshot> passes = svc.getPassSchedules(10);
        assertThat(passes).hasSize(2);
    }

    @Test
    @DisplayName("onHierarchicalRouteDecision 上报后 getRouteDecisions 返回历史")
    void onRouteDecisionStores() {
        SatLinkMonitorService svc = new SatLinkMonitorService();
        svc.onHierarchicalRouteDecision(1, routeOf(2, 40, "L2 可达"));

        List<SatLinkMonitorService.RouteDecisionSnapshot> decisions = svc.getRouteDecisions();
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).chosenLayer()).isEqualTo(2);
        assertThat(decisions.get(0).estimatedDelayMs()).isEqualTo(40);
        assertThat(decisions.get(0).decisionReason()).isEqualTo("L2 可达");
    }

    @Test
    @DisplayName("currentVersion 每次上报递增")
    void currentVersionIncrements() {
        SatLinkMonitorService svc = new SatLinkMonitorService();
        assertThat(svc.currentVersion()).isZero();

        svc.onSatLinkStatus(1, statusOf(10, 1, 45, 80, 25));
        assertThat(svc.currentVersion()).isEqualTo(1);

        svc.onSatPassSchedule(1, passOf(10, 1000L, 6000L, 75));
        assertThat(svc.currentVersion()).isEqualTo(2);

        svc.onHierarchicalRouteDecision(1, routeOf(1, 20, "L1 可达"));
        assertThat(svc.currentVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("setStrategy 合法策略返回 true 并更新")
    void setStrategyValid() {
        SatLinkMonitorService svc = new SatLinkMonitorService();
        assertThat(svc.getCurrentStrategy()).isEqualTo("NEAR_FIRST");

        assertThat(svc.setStrategy("DELAY_OPTIMAL")).isTrue();
        assertThat(svc.getCurrentStrategy()).isEqualTo("DELAY_OPTIMAL");

        assertThat(svc.setStrategy("bandwidth_optimal")).isTrue();
        assertThat(svc.getCurrentStrategy()).isEqualTo("BANDWIDTH_OPTIMAL");
    }

    @Test
    @DisplayName("setStrategy 非法策略返回 false")
    void setStrategyInvalid() {
        SatLinkMonitorService svc = new SatLinkMonitorService();

        assertThat(svc.setStrategy("INVALID")).isFalse();
        assertThat(svc.setStrategy("")).isFalse();
        assertThat(svc.setStrategy(null)).isFalse();
        assertThat(svc.getCurrentStrategy()).isEqualTo("NEAR_FIRST");
    }

    @Test
    @DisplayName("setStrategy 成功后版本递增")
    void setStrategyIncrementsVersion() {
        SatLinkMonitorService svc = new SatLinkMonitorService();
        long v0 = svc.currentVersion();

        svc.setStrategy("RELIABILITY_OPTIMAL");
        assertThat(svc.currentVersion()).isEqualTo(v0 + 1);
    }
}