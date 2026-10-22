package io.aerofleet.cloud.surveillance.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OfflineAlarmController} 单元测试（该子包此前**零测试**）。
 *
 * <p>直接调方法（不起 Spring 上下文）：验的是**HTTP 语义**——哪些入参该 400、
 * 依赖缺失该 503 而不是 500/NPE、flush 失败该报 degraded 而不是假装 ok。
 * 这些是前端面板唯一能看见的东西。
 */
@DisplayName("OfflineAlarmController 离线自治 API")
class OfflineAlarmControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OfflineAlarmCache cache;
    private EdgeAiTrigger trigger;
    private OfflineAlarmController controller;

    @BeforeEach
    void setUp() {
        cache = new OfflineAlarmCache(16);
        trigger = new EdgeAiTrigger();
        ReflectionTestUtils.setField(trigger, "offlineAlarmCache", cache);
        controller = new OfflineAlarmController(cache, trigger);
    }

    private static int status(ResponseEntity<Map<String, Object>> r) {
        return r.getStatusCode().value();
    }

    // ===== 降级：依赖缺失一律 503（不是 500） =====

    @Test
    @DisplayName("cache/trigger 都没注入时，所有端点返回 503 而不是 NPE")
    void allEndpointsReturn503WhenDependenciesMissing() throws Exception {
        OfflineAlarmController bare = new OfflineAlarmController(null, null);
        JsonNode empty = MAPPER.createObjectNode();
        JsonNode emptyArray = MAPPER.createArrayNode();

        assertThat(status(bare.batchUpload(emptyArray))).isEqualTo(503);
        assertThat(status(bare.getPending())).isEqualTo(503);
        assertThat(status(bare.getCacheStats())).isEqualTo(503);
        assertThat(status(bare.flush())).isEqualTo(503);
        assertThat(status(bare.triggerEdgeAi(empty))).isEqualTo(503);
        assertThat(status(bare.getEdgeAiStats())).isEqualTo(503);
        assertThat(status(bare.configureEdgeAi(empty))).isEqualTo(503);
    }

    // ===== 批量上传 =====

    @Test
    @DisplayName("batch-upload 的 body 不是数组 ⇒ 400")
    void batchUploadRejectsNonArray() throws Exception {
        ResponseEntity<Map<String, Object>> r = controller.batchUpload(MAPPER.createObjectNode());
        assertThat(status(r)).isEqualTo(400);
        assertThat(r.getBody()).containsEntry("error", "body must be a JSON array");
    }

    @Test
    @DisplayName("batch-upload 收下合法事件、拒绝缺 deviceId 的事件，并回报 pendingCount")
    void batchUploadAcceptsValidAndRejectsMissingDeviceId() throws Exception {
        JsonNode body = MAPPER.readTree("""
                [
                  {"deviceId":"cam-1","deviceName":"东门","eventType":"INTRUSION","severity":"WARN",
                   "lat":30.1,"lon":120.2,"timestampMs":1760000000000},
                  {"deviceName":"没有 id 的"}
                ]
                """);

        ResponseEntity<Map<String, Object>> r = controller.batchUpload(body);

        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("status", "ok")
                .containsEntry("accepted", 1)
                .containsEntry("rejected", 1)
                .containsEntry("pendingCount", 1);
        OfflineAlarmCache.OfflineAlarmEvent e = cache.getPendingAlarms().get(0);
        assertThat(e.getDeviceId()).isEqualTo("cam-1");
        assertThat(e.getTimestampMs()).isEqualTo(1760000000000L);
        assertThat(e.getCachedAtMs()).isPositive();
    }

    @Test
    @DisplayName("pending 返回事件的完整视图（前端靠这些字段画列表）")
    void pendingReturnsFullEventView() {
        cache.cacheAlarm(new OfflineAlarmCache.OfflineAlarmEvent(
                "cam-1", "东门", "FIRE", "CRITICAL", "冒烟", 30.1, 120.2, 5.0, 100L, 200L));

        ResponseEntity<Map<String, Object>> r = controller.getPending();

        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("count", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alarms = (List<Map<String, Object>>) r.getBody().get("alarms");
        assertThat(alarms).hasSize(1);
        assertThat(alarms.get(0))
                .containsEntry("deviceId", "cam-1")
                .containsEntry("eventType", "FIRE")
                .containsEntry("severity", "CRITICAL")
                .containsEntry("alt", 5.0)
                .containsEntry("timestampMs", 100L)
                .containsEntry("cachedAtMs", 200L);
    }

    // ===== flush =====

    @Test
    @DisplayName("没有 store 时 flush 报 503 + degraded（有积压却没传上去，不能报 ok）")
    void flushWithoutStoreIsDegraded() {
        cache.cacheAlarm(new OfflineAlarmCache.OfflineAlarmEvent(
                "cam-1", "东门", "INTRUSION", "WARN", "d", 0, 0, 0, 1L, 1L));

        ResponseEntity<Map<String, Object>> r = controller.flush();

        assertThat(status(r)).isEqualTo(503);
        assertThat(r.getBody()).containsEntry("status", "degraded")
                .containsEntry("uploaded", 0)
                .containsEntry("remaining", 1);
    }

    @Test
    @DisplayName("注入了 store 后 flush 成功：uploaded=1、remaining=0、200")
    void flushWithStoreUploads() {
        cache.cacheAlarm(new OfflineAlarmCache.OfflineAlarmEvent(
                "cam-1", "东门", "INTRUSION", "WARN", "d", 0, 0, 0, 1L, 1L));
        // AlarmEventStore 会写 JPA repository（裸 new 会 NPE）——这里要验的是控制器语义，用 mock
        ReflectionTestUtils.setField(cache, "alarmEventStore", org.mockito.Mockito.mock(AlarmEventStore.class));

        ResponseEntity<Map<String, Object>> r = controller.flush();

        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("status", "ok")
                .containsEntry("uploaded", 1)
                .containsEntry("remaining", 0);
    }

    @Test
    @DisplayName("缓存为空时 flush 也是 200 / uploaded=0（不是 degraded）")
    void flushOnEmptyCacheIsOk() {
        ResponseEntity<Map<String, Object>> r = controller.flush();
        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("uploaded", 0).containsEntry("remaining", 0);
    }

    // ===== 边缘 AI =====

    @Test
    @DisplayName("edge-ai/trigger：缺 deviceId / 缺 detectType / 未知类型 ⇒ 400")
    void triggerValidatesInput() throws Exception {
        assertThat(status(controller.triggerEdgeAi(MAPPER.readTree("{\"detectType\":\"PERSON\"}"))))
                .isEqualTo(400);
        assertThat(status(controller.triggerEdgeAi(MAPPER.readTree("{\"deviceId\":\"cam-1\"}"))))
                .isEqualTo(400);
        assertThat(status(controller.triggerEdgeAi(
                MAPPER.readTree("{\"deviceId\":\"cam-1\",\"detectType\":\"ALIEN\"}"))))
                .isEqualTo(400);
    }

    @Test
    @DisplayName("edge-ai/trigger：启用的类型 ⇒ TRIGGERED；未启用的 ⇒ SKIPPED（200，不是错误）")
    void triggerReportsTriggeredOrSkipped() throws Exception {
        ResponseEntity<Map<String, Object>> ok = controller.triggerEdgeAi(
                MAPPER.readTree("{\"deviceId\":\"cam-1\",\"deviceName\":\"东门\",\"detectType\":\"person\","
                        + "\"lat\":30.1,\"lon\":120.2}"));
        assertThat(status(ok)).isEqualTo(200);
        assertThat(ok.getBody()).containsEntry("status", "TRIGGERED")
                .containsEntry("detectType", "PERSON")
                .containsEntry("message", "边缘 AI 检测已触发并缓存");
        assertThat(cache.pendingCount()).isEqualTo(1);

        ResponseEntity<Map<String, Object>> skipped = controller.triggerEdgeAi(
                MAPPER.readTree("{\"deviceId\":\"cam-1\",\"detectType\":\"VEHICLE\"}"));
        assertThat(status(skipped)).isEqualTo(200);
        assertThat(skipped.getBody()).containsEntry("status", "SKIPPED")
                .containsEntry("message", "检测类型未启用或缓存不可用");
        assertThat(cache.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("edge-ai/configure：缺 deviceId / enabledTypes 不是数组 / 未知类型 ⇒ 400")
    void configureValidatesInput() throws Exception {
        assertThat(status(controller.configureEdgeAi(MAPPER.readTree("{\"enabledTypes\":[]}"))))
                .isEqualTo(400);
        assertThat(status(controller.configureEdgeAi(
                MAPPER.readTree("{\"deviceId\":\"cam-1\",\"enabledTypes\":\"PERSON\"}"))))
                .isEqualTo(400);
        assertThat(status(controller.configureEdgeAi(
                MAPPER.readTree("{\"deviceId\":\"cam-1\",\"enabledTypes\":[\"ALIEN\"]}"))))
                .isEqualTo(400);
    }

    @Test
    @DisplayName("edge-ai/configure 生效：只开 VEHICLE 后 PERSON 不再触发")
    void configureAppliesToTrigger() throws Exception {
        ResponseEntity<Map<String, Object>> r = controller.configureEdgeAi(
                MAPPER.readTree("{\"deviceId\":\"cam-1\",\"enabledTypes\":[\"vehicle\"]}"));

        assertThat(status(r)).isEqualTo(200);
        assertThat(trigger.isDetectionEnabled("cam-1", EdgeAiTrigger.AiDetectType.VEHICLE)).isTrue();
        assertThat(trigger.isDetectionEnabled("cam-1", EdgeAiTrigger.AiDetectType.PERSON)).isFalse();
    }

    @Test
    @DisplayName("stats 端点把触发次数透出来（前端用它确认边缘 AI 在干活）")
    void statsEndpointsExposeCounters() throws Exception {
        controller.triggerEdgeAi(MAPPER.readTree("{\"deviceId\":\"cam-1\",\"detectType\":\"FIRE\"}"));

        ResponseEntity<Map<String, Object>> edge = controller.getEdgeAiStats();
        assertThat(status(edge)).isEqualTo(200);
        assertThat(edge.getBody()).containsEntry("totalDetections", 1L);

        ResponseEntity<Map<String, Object>> cacheStats = controller.getCacheStats();
        assertThat(status(cacheStats)).isEqualTo(200);
        assertThat(cacheStats.getBody()).containsEntry("totalCount", 1);
    }
}
