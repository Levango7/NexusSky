package io.aerofleet.cloud.alarm;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.api.service.EmergencyOrchService;
import io.aerofleet.cloud.mission.emergency.EmergencyCommand;
import io.aerofleet.cloud.mission.emergency.EmergencyCommandWorkflow;
import io.aerofleet.cloud.mission.emergency.OneClickEmergencyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
    private EmergencyCommandWorkflow emergencyWorkflow;
    private OneClickEmergencyResponse oneClickResponse;
    private AlarmController controller;
    private final ApplicationEventPublisher noopPublisher = event -> { };

    @BeforeEach
    void setUp() {
        EmergencyOrchService orchService = new EmergencyOrchService(null, noopPublisher);
        store = new AlarmEventStore();

        // 创建 mock AlarmEventRepository 并注入，模拟内存存储行为
        AlarmEventRepository mockRepo = Mockito.mock(AlarmEventRepository.class);
        Map<String, AlarmEvent> eventMap = new ConcurrentHashMap<>();
        Mockito.when(mockRepo.save(Mockito.any(AlarmEvent.class))).thenAnswer(inv -> {
            AlarmEvent e = inv.getArgument(0);
            eventMap.put(e.getId(), e);
            return e;
        });
        Mockito.when(mockRepo.findById(Mockito.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(eventMap.get(inv.getArgument(0))));
        Mockito.when(mockRepo.findAll())
                .thenAnswer(inv -> new ArrayList<>(eventMap.values()));
        // 配置 findAll(Pageable)：按排序方向返回分页结果
        Mockito.when(mockRepo.findAll(Mockito.any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            List<AlarmEvent> all = new ArrayList<>(eventMap.values());
            Sort sort = pageable.getSort();
            if (sort != null && sort.isSorted()) {
                for (Sort.Order order : sort) {
                    if ("timestampMs".equals(order.getProperty())) {
                        Comparator<AlarmEvent> cmp = Comparator.comparingLong(AlarmEvent::getTimestampMs);
                        if (order.isDescending()) {
                            cmp = cmp.reversed();
                        }
                        all.sort(cmp);
                    }
                }
            }
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), all.size());
            List<AlarmEvent> subList = start < all.size() ? new ArrayList<>(all.subList(start, end)) : new ArrayList<>();
            return new PageImpl<>(subList, pageable, all.size());
        });
        Mockito.when(mockRepo.count()).thenAnswer(inv -> (long) eventMap.size());
        Mockito.doAnswer(inv -> {
            AlarmEvent e = inv.getArgument(0);
            eventMap.remove(e.getId());
            return null;
        }).when(mockRepo).delete(Mockito.any(AlarmEvent.class));
        try {
            java.lang.reflect.Field f = AlarmEventStore.class.getDeclaredField("repository");
            f.setAccessible(true);
            f.set(store, mockRepo);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("注入 mock repository 失败", e);
        }

        AlarmToOrchBridge bridge = new AlarmToOrchBridge(orchService);
        engine = new AlarmLinkageEngine(store, bridge);
        emergencyWorkflow = new EmergencyCommandWorkflow();
        oneClickResponse = new OneClickEmergencyResponse(emergencyWorkflow, orchService);
        controller = new AlarmController(engine, store, emergencyWorkflow, oneClickResponse);
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

    // ===== POST /events/ack-batch 批量确认 =====

    @Test
    @DisplayName("POST /events/ack-batch 批量确认多个报警事件")
    void acknowledgeBatchSuccess() {
        ResponseEntity<Map<String, Object>> e1 = controller.receiveEvent(eventBody("dev-1", "FIRE", "CRITICAL"));
        ResponseEntity<Map<String, Object>> e2 = controller.receiveEvent(eventBody("dev-2", "MOTION", "WARN"));
        String id1 = (String) e1.getBody().get("eventId");
        String id2 = (String) e2.getBody().get("eventId");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventIds", List.of(id1, id2));
        ResponseEntity<Map<String, Object>> resp = controller.acknowledgeBatch(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result.get("totalRequested")).isEqualTo(2);
        assertThat(result.get("successCount")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<String> failed = (List<String>) result.get("failedIds");
        assertThat(failed).isEmpty();
    }

    @Test
    @DisplayName("POST /events/ack-batch 部分不存在时返回失败 ID 列表")
    void acknowledgeBatchPartialNotFound() {
        ResponseEntity<Map<String, Object>> e1 = controller.receiveEvent(eventBody("dev-1", "FIRE", "CRITICAL"));
        String id1 = (String) e1.getBody().get("eventId");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventIds", List.of(id1, "nonexistent-id"));
        ResponseEntity<Map<String, Object>> resp = controller.acknowledgeBatch(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result.get("totalRequested")).isEqualTo(2);
        assertThat(result.get("successCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<String> failed = (List<String>) result.get("failedIds");
        assertThat(failed).containsExactly("nonexistent-id");
    }

    @Test
    @DisplayName("POST /events/ack-batch 缺 eventIds 抛 BadRequestException")
    void acknowledgeBatchMissingEventIdsThrows() {
        assertThatThrownBy(() -> controller.acknowledgeBatch(new LinkedHashMap<>()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("POST /events/ack-batch 空 eventIds 抛 BadRequestException")
    void acknowledgeBatchEmptyEventIdsThrows() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventIds", List.of());
        assertThatThrownBy(() -> controller.acknowledgeBatch(body))
                .isInstanceOf(BadRequestException.class);
    }

    // ===== POST /events/{id}/respond 一键应急响应 =====

    @Test
    @DisplayName("POST /events/{id}/respond 触发一键应急响应")
    void triggerEmergencyResponseSuccess() {
        ResponseEntity<Map<String, Object>> created = controller.receiveEvent(
                eventBody("dev-1", "FIRE", "CRITICAL"));
        String eventId = (String) created.getBody().get("eventId");

        ResponseEntity<Map<String, Object>> resp = controller.triggerEmergencyResponse(eventId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result.get("commandId")).isNotNull();
        assertThat(result.get("eventId")).isEqualTo(eventId);
        assertThat(result.get("status")).isNotNull();
        assertThat(result).containsKey("message");
    }

    @Test
    @DisplayName("POST /events/{id}/respond 不存在时抛 NotFoundException")
    void triggerEmergencyResponseNotFoundThrows() {
        assertThatThrownBy(() -> controller.triggerEmergencyResponse("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("POST /events/{id}/respond 安防报警事件类型正确映射")
    void triggerEmergencyResponseSecurityAlarmMapping() {
        ResponseEntity<Map<String, Object>> created = controller.receiveEvent(
                eventBody("dev-1", "INTRUSION", "WARN"));
        String eventId = (String) created.getBody().get("eventId");

        ResponseEntity<Map<String, Object>> resp = controller.triggerEmergencyResponse(eventId);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // 验证命令已创建
        String commandId = (String) resp.getBody().get("commandId");
        assertThat(emergencyWorkflow.getCommand(commandId)).isNotNull();
    }

    // ===== GET /stream SSE 实时推送 =====

    @Test
    @DisplayName("GET /stream 返回 SseEmitter")
    void streamEventsReturnsSseEmitter() {
        SseEmitter emitter = controller.streamEvents();
        assertThat(emitter).isNotNull();
        // emitter 应有非零超时
        assertThat(emitter.getTimeout()).isPositive();
    }

    @Test
    @DisplayName("GET /stream 多次调用返回独立 emitter")
    void streamEventsMultipleIndependentEmitters() {
        SseEmitter e1 = controller.streamEvents();
        SseEmitter e2 = controller.streamEvents();
        assertThat(e1).isNotSameAs(e2);
    }

    // ===== GET /linkage-logs 联动日志查询 =====

    @Test
    @DisplayName("GET /linkage-logs 无联动时返回空列表")
    void listLinkageLogsEmpty() {
        ResponseEntity<Map<String, Object>> resp = controller.listLinkageLogs(100);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result.get("total")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) result.get("items");
        assertThat(items).isEmpty();
    }

    @Test
    @DisplayName("GET /linkage-logs 有联动时返回日志列表")
    void listLinkageLogsAfterLinkageExecution() {
        // 创建规则并触发联动
        controller.createRule(ruleBody("r1", "fire-rule", "FIRE", "DEPLOY_DRONE", true));
        controller.receiveEvent(eventBody("dev-1", "FIRE", "CRITICAL"));

        ResponseEntity<Map<String, Object>> resp = controller.listLinkageLogs(100);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result.get("total")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("ruleId")).isEqualTo("r1");
        assertThat(items.get(0).get("actionType")).isEqualTo("DEPLOY_DRONE");
        assertThat(items.get(0)).containsKey("timestampMs");
    }

    @Test
    @DisplayName("GET /linkage-logs limit 参数限制返回条数")
    void listLinkageLogsWithLimit() {
        // 创建规则并触发多次联动
        controller.createRule(ruleBody("r1", "fire-rule", "FIRE", "NOTIFY_ONLY", true));
        controller.receiveEvent(eventBody("dev-1", "FIRE", "CRITICAL"));
        controller.receiveEvent(eventBody("dev-2", "FIRE", "CRITICAL"));
        controller.receiveEvent(eventBody("dev-3", "FIRE", "CRITICAL"));

        ResponseEntity<Map<String, Object>> resp = controller.listLinkageLogs(2);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> result = resp.getBody();
        assertThat(result.get("total")).isEqualTo(2);
        assertThat(result.get("limit")).isEqualTo(2);
    }

    @Test
    @DisplayName("GET /linkage-logs limit<=0 时使用默认值 100")
    void listLinkageLogsInvalidLimitUsesDefault() {
        ResponseEntity<Map<String, Object>> resp = controller.listLinkageLogs(0);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("limit")).isEqualTo(100);
    }
}