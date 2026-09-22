package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.api.service.EmergencyOrchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmergencyOrchController REST 端点单测（M9 应急任务编排，FR-30）。
 * <p>
 * 直接实例化 Controller + Service（无 MockMvc / Spring 上下文），验证端点逻辑与状态码。
 */
@DisplayName("EmergencyOrchController REST 端点 (FR-30)")
class EmergencyOrchControllerTest {

    private EmergencyOrchService service;
    private EmergencyOrchController controller;
    private final ApplicationEventPublisher noopPublisher = event -> { };

    @BeforeEach
    void setUp() {
        // pusher 传 null：测试不验证 WebSocket 推送
        service = new EmergencyOrchService(null, noopPublisher);
        controller = new EmergencyOrchController(service);
    }

    private static Map<String, Object> startBody(int scenarioType, int centerLat, int centerLon,
                                                 int radius, List<Integer> droneIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenarioType", scenarioType);
        body.put("centerLat", centerLat);
        body.put("centerLon", centerLon);
        body.put("radius", radius);
        body.put("droneIds", droneIds);
        return body;
    }

    @Test
    @DisplayName("POST /orch/start 返回 planId 与 RUNNING 状态")
    void startReturnsPlanId() {
        ResponseEntity<Map<String, Object>> resp = controller.start(
                startBody(0, 399000000, 1163000000, 5000, List.of(1, 2, 3)));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("planId")).isInstanceOf(Long.class);
        assertThat(body.get("status")).isEqualTo("RUNNING");
        assertThat(body.get("timestamp")).isNotNull();
    }

    @Test
    @DisplayName("GET /orch/{planId} 返回 plan 状态")
    void getPlanReturnsStatus() {
        long planId = service.start(0, 399000000, 1163000000, 5000, List.of(1, 2, 3));

        ResponseEntity<Map<String, Object>> resp = controller.getPlan(planId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("planId")).isEqualTo(planId);
        assertThat(body.get("scenarioType")).isEqualTo(0);
        assertThat(body.get("status")).isEqualTo("RUNNING");
        assertThat(body.get("radius")).isEqualTo(5000);
        assertThat(body.get("droneCount")).isEqualTo(3);
    }

    @Test
    @DisplayName("GET /orch/{planId} 不存在时返回 404")
    void getPlanReturns404ForUnknown() {
        ResponseEntity<Map<String, Object>> resp = controller.getPlan(99999);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().get("error").toString()).contains("99999");
    }

    @Test
    @DisplayName("POST /orch/{planId}/abort 返回 ABORTED 状态")
    void abortReturnsAborted() {
        long planId = service.start(0, 399000000, 1163000000, 5000, List.of(1, 2));

        ResponseEntity<Map<String, Object>> resp = controller.abort(planId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("planId")).isEqualTo(planId);
        assertThat(body.get("status")).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("POST /orch/{planId}/abort 不存在时返回 404")
    void abortReturns404ForUnknown() {
        ResponseEntity<Map<String, Object>> resp = controller.abort(88888);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("GET /scenarios 返回 4 种预设")
    void getScenariosReturnsFour() {
        ResponseEntity<Map<String, Object>> resp = controller.getScenarios();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) resp.getBody().get("scenarios");
        assertThat(scenarios).hasSize(4);
        assertThat(scenarios.get(0).get("name")).isEqualTo("地震");
        assertThat(scenarios.get(1).get("name")).isEqualTo("泥石流");
        assertThat(scenarios.get(2).get("name")).isEqualTo("火灾");
        assertThat(scenarios.get(3).get("name")).isEqualTo("自定义");
    }

    @Test
    @DisplayName("POST /scenarios/0/start 返回 planId 与 RUNNING 状态")
    void startScenarioReturnsPlanId() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("centerLat", 399000000);
        body.put("centerLon", 1163000000);
        body.put("radius", 5000);
        body.put("droneIds", List.of(1, 2, 3));

        ResponseEntity<Map<String, Object>> resp = controller.startScenario(0, body);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result).isNotNull();
        assertThat(result.get("planId")).isInstanceOf(Long.class);
        assertThat(result.get("status")).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("POST /scenarios/{type}/start 不存在的类型返回 404")
    void startScenarioReturns404ForUnknownType() {
        ResponseEntity<Map<String, Object>> resp = controller.startScenario(99, new LinkedHashMap<>());
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("GET /orch/{planId}/progress 返回阶段与事件列表")
    void getProgressReturnsPhasesAndEvents() {
        long planId = service.start(0, 399000000, 1163000000, 5000, List.of(1, 2));

        ResponseEntity<Map<String, Object>> resp = controller.getProgress(planId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        List<?> phases = (List<?>) body.get("phases");
        assertThat(phases).hasSize(5);
        @SuppressWarnings("unchecked")
        List<?> events = (List<?>) body.get("events");
        assertThat(events).isNotEmpty();
    }

    @Test
    @DisplayName("GET /orch/{planId}/coverage 返回覆盖信息")
    void getCoverageReturnsCoverage() {
        long planId = service.start(0, 399000000, 1163000000, 5000, List.of(1, 2));

        ResponseEntity<Map<String, Object>> resp = controller.getCoverage(planId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("coverageRate")).isEqualTo(0);
        assertThat(body.get("connectRate")).isEqualTo(0);
        assertThat(body).containsKey("deployments");
        assertThat(body).containsKey("uncoveredAreas");
    }

    @Test
    @DisplayName("POST /orch/{planId}/replan 返回新 planId")
    void replanReturnsNewPlanId() {
        long planId = service.start(0, 399000000, 1163000000, 5000, List.of(1, 2));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", "drone lost");

        ResponseEntity<Map<String, Object>> resp = controller.replan(planId, body);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result).isNotNull();
        assertThat(result.get("newPlanId")).isInstanceOf(Long.class);
        assertThat((long) result.get("newPlanId")).isGreaterThan(planId);
        assertThat(result.get("status")).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("POST /orch/{planId}/priority 调整优先级返回结果")
    void adjustPriorityReturnsResult() {
        long planId = service.start(0, 399000000, 1163000000, 5000, List.of(1, 2));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", 67890);
        body.put("priority", 1);
        body.put("reason", "search rescue");

        ResponseEntity<Map<String, Object>> resp = controller.adjustPriority(planId, body);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result).isNotNull();
        assertThat(result.get("taskId")).isEqualTo(67890L);
        assertThat(result.get("newPriority")).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /orch/{planId}/priority/queue 返回 4 级队列")
    void getPriorityQueueReturnsFourQueues() {
        long planId = service.start(0, 399000000, 1163000000, 5000, List.of(1, 2));

        ResponseEntity<Map<String, Object>> resp = controller.getPriorityQueue(planId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, List<Long>> queues = (Map<String, List<Long>>) resp.getBody().get("queues");
        assertThat(queues).containsOnlyKeys("SEARCH_RESCUE", "COMMAND", "MAPPING", "ROUTINE");
    }
}