package io.aerofleet.cloud.api;

import io.aerofleet.mavlink.messages.HierarchicalRouteDecisionMsg;
import io.aerofleet.mavlink.messages.SatLinkStatusMsg;
import io.aerofleet.mavlink.messages.SatPassScheduleMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SatLinkController REST 端点单测（M7 星-空-地多层级中继）。
 * <p>
 * 直接实例化 Controller + Service（无 MockMvc / Spring 上下文），验证端点逻辑与状态码。
 */
@DisplayName("SatLinkController REST 端点 (FR-5.4)")
class SatLinkControllerTest {

    private SatLinkMonitorService service;
    private SatLinkController controller;

    @BeforeEach
    void setUp() {
        service = new SatLinkMonitorService();
        controller = new SatLinkController(service);
    }

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

    // ===== /status =====

    @Test
    @DisplayName("GET /status 返回所有卫星链路状态与 satCount")
    void getAllStatusReturnsAll() {
        service.onSatLinkStatus(1, statusOf(10, 1, 45, 80, 25));
        service.onSatLinkStatus(1, statusOf(20, 1, 30, 60, 40));

        ResponseEntity<Map<String, Object>> resp = controller.getAllStatus();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("satCount")).isEqualTo(2);
        assertThat((List<?>) body.get("links")).hasSize(2);
        assertThat(body.get("version")).isNotNull();
    }

    @Test
    @DisplayName("GET /status 空状态返回 satCount=0")
    void getAllStatusEmpty() {
        ResponseEntity<Map<String, Object>> resp = controller.getAllStatus();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("satCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("GET /status/{satId} 存在时返回 200 与链路视图")
    void getStatusReturns200() {
        service.onSatLinkStatus(1, statusOf(10, 1, 45, 80, 25));

        ResponseEntity<Map<String, Object>> resp = controller.getStatus(10);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("satId")).isEqualTo(10);
        assertThat(resp.getBody().get("visible")).isEqualTo(true);
        assertThat(resp.getBody().get("elevationDeg")).isEqualTo(45);
    }

    @Test
    @DisplayName("GET /status/{satId} 不存在时返回 404")
    void getStatusReturns404() {
        ResponseEntity<Map<String, Object>> resp = controller.getStatus(99);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().get("error").toString()).contains("99");
    }

    // ===== /passes =====

    @Test
    @DisplayName("GET /passes 返回所有过境计划")
    void getAllPassesReturnsAll() {
        service.onSatPassSchedule(1, passOf(10, 1000L, 6000L, 75));
        service.onSatPassSchedule(1, passOf(20, 2000L, 8000L, 60));

        ResponseEntity<Map<String, Object>> resp = controller.getAllPasses();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("passCount")).isEqualTo(2);
        assertThat((List<?>) resp.getBody().get("passes")).hasSize(2);
    }

    @Test
    @DisplayName("GET /passes/{satId} 存在时返回 200 与过境列表")
    void getPassesReturns200() {
        service.onSatPassSchedule(1, passOf(10, 1000L, 6000L, 75));

        ResponseEntity<Map<String, Object>> resp = controller.getPasses(10);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("satId")).isEqualTo(10);
        assertThat(resp.getBody().get("passCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /passes/{satId} 不存在时返回 404")
    void getPassesReturns404() {
        ResponseEntity<Map<String, Object>> resp = controller.getPasses(99);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ===== /routes =====

    @Test
    @DisplayName("GET /routes 返回路由决策历史")
    void getRoutesReturnsHistory() {
        service.onHierarchicalRouteDecision(1, routeOf(2, 40, "L2 可达"));
        service.onHierarchicalRouteDecision(1, routeOf(1, 20, "L1 可达"));

        ResponseEntity<Map<String, Object>> resp = controller.getRoutes();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("routeCount")).isEqualTo(2);
        List<?> routes = (List<?>) resp.getBody().get("routes");
        assertThat(routes).hasSize(2);
    }

    @Test
    @DisplayName("GET /routes 空历史返回 routeCount=0")
    void getRoutesEmpty() {
        ResponseEntity<Map<String, Object>> resp = controller.getRoutes();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("routeCount")).isEqualTo(0);
    }

    // ===== /strategy =====

    @Test
    @DisplayName("GET /strategy 返回当前策略与合法策略列表")
    void getStrategyReturnsCurrent() {
        ResponseEntity<Map<String, Object>> resp = controller.getStrategy();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("strategy")).isEqualTo("NEAR_FIRST");
        assertThat((List<?>) resp.getBody().get("validStrategies")).hasSize(4);
    }

    @Test
    @DisplayName("PUT /strategy 合法策略返回 200")
    void setStrategyValid() {
        ResponseEntity<Map<String, Object>> resp = controller.setStrategy(
                Map.of("strategy", "DELAY_OPTIMAL"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("strategy")).isEqualTo("DELAY_OPTIMAL");
        assertThat(resp.getBody().get("message")).isNotNull();
    }

    @Test
    @DisplayName("PUT /strategy 缺少 strategy 字段返回 400")
    void setStrategyMissingField() {
        ResponseEntity<Map<String, Object>> resp = controller.setStrategy(Map.of());
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("error").toString()).contains("required");
    }

    @Test
    @DisplayName("PUT /strategy 非法策略返回 400")
    void setStrategyInvalid() {
        ResponseEntity<Map<String, Object>> resp = controller.setStrategy(
                Map.of("strategy", "INVALID"));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("error").toString()).contains("invalid");
    }

    // ===== /constellation =====

    @Test
    @DisplayName("GET /constellation 返回星座配置信息")
    void getConstellationReturnsConfig() {
        service.onSatLinkStatus(1, statusOf(10, 1, 45, 80, 25));
        service.onSatLinkStatus(1, statusOf(20, 1, 30, 60, 40));

        ResponseEntity<Map<String, Object>> resp = controller.getConstellation();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("simulated")).isEqualTo(true);
        assertThat(resp.getBody().get("linkCount")).isEqualTo(2);
    }
}