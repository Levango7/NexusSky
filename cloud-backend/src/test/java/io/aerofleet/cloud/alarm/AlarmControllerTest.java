package io.aerofleet.cloud.alarm;

import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.api.EmergencyOrchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AlarmController} REST 端点单测（M10 报警联动编排，FR-31）。
 * <p>
 * 直接实例化 Controller + Engine + Service（无 MockMvc / Spring 上下文），
 * 验证端点逻辑与状态码。与 EmergencyOrchControllerTest 风格一致。
 */
@DisplayName("AlarmController REST 端点 (FR-31)")
class AlarmControllerTest {

    private AlarmEventStore store;
    private AlarmLinkageEngine engine;
    private AlarmController controller;

    @BeforeEach
    void setUp() {
        EmergencyOrchService orchService = new EmergencyOrchService(null);
        store = new AlarmEventStore();
        AlarmToOrchBridge bridge = new AlarmToOrchBridge(orchService);
        engine = new AlarmLinkageEngine(store, bridge);
        controller = new AlarmController(engine, store);
    }

    private static Map<String, Object> eventBody(String deviceId, String type, String severity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceDeviceId", deviceId);
        body.put("sourceDeviceName", "cam-" + deviceId);
        body.put("eventType", type);
        body.put("severity", severity);
        body.put("description", "test alarm");
        body.put("lat", 39.9);
        body.put("lon", 116.3);
        return body;
    }

    private static Map<String, Object> ruleBody(String id, String name, String eventType,
                                                 String actionType, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("enabled", enabled);
        body.put("matchEventType", eventType);
        body.put("matchSeverity", "INFO");
        body.put("actionType", actionType);
        body.put("droneCount", 2);
        body.put("targetRadiusM", 500);
        body.put("altitudeM", 80);
        return body;
    }

    @Test
    @DisplayName("POST /events 接收报警事件并返回匹配结果")
    void receiveEventReturnsMatchResult() {
        Map<String, Object> body = eventBody("dev-1", "FIRE", "CRITICAL");
        ResponseEntity<Map<String, Object>> resp = controller.receiveEvent(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result).isNotNull();
        assertThat(result.get("eventId")).isNotNull();
        assertThat(result.get("matchedCount")).isEqualTo(0);
        assertThat(result).containsKey("executions");
        assertThat(result).containsKey("timestamp");
    }

    @Test
    @DisplayName("POST /events 匹配规则时返回联动执行结果")
    void receiveEventWithMatchingRuleReturnsExecution() {
        controller.createRule(ruleBody("r1", "fire-rule", "FIRE", "DEPLOY_DRONE", true));
        Map<String, Object> body = eventBody("dev-1", "FIRE", "CRITICAL");

        ResponseEntity<Map<String, Object>> resp = controller.receiveEvent(body);
        Map<String, Object> result = resp.getBody();
        assertThat(result.get("matchedCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> execs = (List<Map<String, Object>>) result.get("executions");
        assertThat(execs).hasSize(1);
        assertThat(execs.get(0).get("actionType")).isEqualTo("DEPLOY_DRONE");
    }

    @Test
    @DisplayName("GET /events 分页查询返回事件列表")
    void queryEventsReturnsPaginatedList() {
        controller.receiveEvent(eventBody("dev-1", "MOTION", "WARN"));
        controller.receiveEvent(eventBody("dev-2", "FIRE", "CRITICAL"));

        ResponseEntity<Map<String, Object>> resp = controller.queryEvents(0, 10, null, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result.get("total")).isEqualTo(2);
        assertThat(result.get("page")).isEqualTo(0);
        assertThat(result.get("size")).isEqualTo(10);
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) result.get("items");
        assertThat(items).hasSize(2);
    }

    @Test
    @DisplayName("GET /events 按 severity 筛选")
    void queryEventsFilterBySeverity() {
        controller.receiveEvent(eventBody("dev-1", "MOTION", "WARN"));
        controller.receiveEvent(eventBody("dev-2", "FIRE", "CRITICAL"));

        ResponseEntity<Map<String, Object>> resp = controller.queryEvents(0, 10, "CRITICAL", null);
        assertThat(resp.getBody().get("total")).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /events/{id} 返回事件详情")
    void getEventReturnsDetail() {
        ResponseEntity<Map<String, Object>> created = controller.receiveEvent(
                eventBody("dev-1", "FIRE", "CRITICAL"));
        String eventId = (String) created.getBody().get("eventId");

        ResponseEntity<Map<String, Object>> resp = controller.getEvent(eventId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("sourceDeviceId")).isEqualTo("dev-1");
        assertThat(resp.getBody().get("eventType")).isEqualTo("FIRE");
    }

    @Test
    @DisplayName("GET /events/{id} 不存在时抛 NotFoundException")
    void getEventNotFoundThrows() {
        assertThatThrownBy(() -> controller.getEvent("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("POST /events/{id}/ack 确认报警")
    void acknowledgeEvent() {
        ResponseEntity<Map<String, Object>> created = controller.receiveEvent(
                eventBody("dev-1", "FIRE", "CRITICAL"));
        String eventId = (String) created.getBody().get("eventId");

        ResponseEntity<Map<String, Object>> resp = controller.acknowledge(eventId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("acknowledged")).isEqualTo(true);
    }

    @Test
    @DisplayName("POST /events/{id}/ack 不存在时抛 NotFoundException")
    void acknowledgeNotFoundThrows() {
        assertThatThrownBy(() -> controller.acknowledge("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("POST /rules 创建联动规则")
    void createRule() {
        ResponseEntity<Map<String, Object>> resp = controller.createRule(
                ruleBody("r1", "fire-rule", "FIRE", "DEPLOY_DRONE", true));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("ruleId")).isEqualTo("r1");
        assertThat(resp.getBody().get("created")).isEqualTo(true);
        assertThat(engine.getRule("r1")).isNotNull();
    }

    @Test
    @DisplayName("GET /rules 列出联动规则")
    void listRules() {
        controller.createRule(ruleBody("r1", "rule1", "FIRE", "DEPLOY_DRONE", true));
        controller.createRule(ruleBody("r2", "rule2", "MOTION", "NOTIFY_ONLY", true));

        ResponseEntity<Map<String, Object>> resp = controller.listRules();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("total")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) resp.getBody().get("items");
        assertThat(items).hasSize(2);
    }

    @Test
    @DisplayName("PUT /rules/{id} 更新联动规则")
    void updateRule() {
        controller.createRule(ruleBody("r1", "rule1", "FIRE", "DEPLOY_DRONE", true));
        ResponseEntity<Map<String, Object>> resp = controller.updateRule("r1",
                ruleBody("r1", "updated", "MOTION", "NOTIFY_ONLY", false));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("updated")).isEqualTo(true);
        assertThat(engine.getRule("r1").getName()).isEqualTo("updated");
    }

    @Test
    @DisplayName("PUT /rules/{id} 不存在时抛 NotFoundException")
    void updateRuleNotFoundThrows() {
        assertThatThrownBy(() -> controller.updateRule("nonexistent",
                ruleBody("x", "x", "FIRE", "DEPLOY_DRONE", true)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("DELETE /rules/{id} 删除联动规则")
    void deleteRule() {
        controller.createRule(ruleBody("r1", "rule1", "FIRE", "DEPLOY_DRONE", true));
        ResponseEntity<Map<String, Object>> resp = controller.deleteRule("r1");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("deleted")).isEqualTo(true);
        assertThat(engine.getRule("r1")).isNull();
    }

    @Test
    @DisplayName("DELETE /rules/{id} 不存在时抛 NotFoundException")
    void deleteRuleNotFoundThrows() {
        assertThatThrownBy(() -> controller.deleteRule("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("POST /rules/{id}/test 测试联动规则返回匹配结果")
    void testRuleReturnsMatchResult() {
        controller.createRule(ruleBody("r1", "fire-rule", "FIRE", "DEPLOY_DRONE", true));
        ResponseEntity<Map<String, Object>> resp = controller.testRule("r1", new LinkedHashMap<>());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result.get("ruleId")).isEqualTo("r1");
        assertThat(result.get("matched")).isEqualTo(true);
        assertThat(result.get("matchedCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /rules/{id}/test 不存在时抛 NotFoundException")
    void testRuleNotFoundThrows() {
        assertThatThrownBy(() -> controller.testRule("nonexistent", new LinkedHashMap<>()))
                .isInstanceOf(NotFoundException.class);
    }
}