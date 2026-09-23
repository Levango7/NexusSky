package io.aerofleet.cloud.surveillance.offline;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 安防设备离线自治 REST API。
 * <p>
 * 灾害断网场景下，安防设备在本地缓存报警事件并模拟边缘 AI 检测；
 * 网络恢复后通过此 API 批量上传缓存的报警事件到云端。
 * <p>
 * 端点清单：
 * <pre>
 * POST /api/v1/offline-alarm/batch-upload    批量上传离线缓存的报警事件
 * GET  /api/v1/offline-alarm/pending         获取待上传的离线报警列表
 * GET  /api/v1/offline-alarm/cache-stats     获取缓存统计信息
 * POST /api/v1/offline-alarm/flush           手动触发批量上传到 AlarmEventStore
 * POST /api/v1/offline-alarm/edge-ai/trigger 模拟触发一次边缘 AI 检测
 * GET  /api/v1/offline-alarm/edge-ai/stats   获取边缘 AI 检测统计
 * POST /api/v1/offline-alarm/edge-ai/configure 配置设备的启用检测类型
 * </pre>
 * <p>
 * 降级模式：当 OfflineAlarmCache 或 EdgeAiTrigger 未注入时，
 * 端点返回 503 Service Unavailable。
 */
@RestController
@RequestMapping("/api/v1/offline-alarm")
@Tag(name = "OfflineAlarm", description = "安防设备离线自治 REST API")
public class OfflineAlarmController {

    private static final Logger log = LoggerFactory.getLogger(OfflineAlarmController.class);

    private final OfflineAlarmCache offlineAlarmCache;
    private final EdgeAiTrigger edgeAiTrigger;

    @Autowired
    public OfflineAlarmController(@Autowired(required = false) OfflineAlarmCache offlineAlarmCache,
                                  @Autowired(required = false) EdgeAiTrigger edgeAiTrigger) {
        this.offlineAlarmCache = offlineAlarmCache;
        this.edgeAiTrigger = edgeAiTrigger;
    }

    // ------------------------------------------------------------------
    // 离线报警缓存相关端点
    // ------------------------------------------------------------------

    /**
     * 批量上传离线缓存的报警事件。
     * <p>
     * body 为 JSON 数组，每个元素包含：
     * deviceId/deviceName/eventType/severity/description/lat/lon/alt/timestampMs
     * <p>
     * 上传的事件会被缓存到 OfflineAlarmCache，等待后续 flush 到 AlarmEventStore。
     */
    @Operation(summary = "批量上传离线缓存的报警事件",
            description = "body 为 JSON 数组，每个元素包含 deviceId/deviceName/eventType/severity/description/lat/lon/alt/timestampMs")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上传成功"),
            @ApiResponse(responseCode = "400", description = "参数错误"),
            @ApiResponse(responseCode = "503", description = "离线报警缓存服务不可用（依赖未注入）")
    })
    @PostMapping("/batch-upload")
    public ResponseEntity<Map<String, Object>> batchUpload(@RequestBody JsonNode body) {
        if (offlineAlarmCache == null) {
            return serviceUnavailable("OfflineAlarmCache is not available");
        }

        if (body == null || !body.isArray()) {
            return badRequest("body must be a JSON array");
        }

        int accepted = 0;
        int rejected = 0;
        for (JsonNode item : body) {
            String deviceId = item.path("deviceId").asText("");
            if (deviceId.isBlank()) {
                rejected++;
                continue;
            }
            String deviceName = item.path("deviceName").asText("");
            String eventType = item.path("eventType").asText("CUSTOM");
            String severity = item.path("severity").asText("WARN");
            String description = item.path("description").asText("");
            double lat = item.path("lat").asDouble(0.0);
            double lon = item.path("lon").asDouble(0.0);
            double alt = item.path("alt").asDouble(0.0);
            long timestampMs = item.path("timestampMs").asLong(System.currentTimeMillis());

            long cachedAtMs = System.currentTimeMillis();
            OfflineAlarmCache.OfflineAlarmEvent event = new OfflineAlarmCache.OfflineAlarmEvent(
                    deviceId, deviceName, eventType, severity, description,
                    lat, lon, alt, timestampMs, cachedAtMs);
            offlineAlarmCache.cacheAlarm(event);
            accepted++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("accepted", accepted);
        result.put("rejected", rejected);
        result.put("pendingCount", offlineAlarmCache.pendingCount());
        log.info("批量上传离线报警: 接收 {} 条, 拒绝 {} 条, 待上传 {} 条",
                accepted, rejected, offlineAlarmCache.pendingCount());
        return ResponseEntity.ok(result);
    }

    /**
     * 获取当前待上传的离线报警列表。
     */
    @Operation(summary = "获取待上传的离线报警列表")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "报警列表"),
            @ApiResponse(responseCode = "503", description = "离线报警缓存服务不可用（依赖未注入）")
    })
    @GetMapping("/pending")
    public ResponseEntity<Map<String, Object>> getPending() {
        if (offlineAlarmCache == null) {
            return serviceUnavailable("OfflineAlarmCache is not available");
        }

        List<OfflineAlarmCache.OfflineAlarmEvent> pending = offlineAlarmCache.getPendingAlarms();
        List<Map<String, Object>> items = new ArrayList<>(pending.size());
        for (OfflineAlarmCache.OfflineAlarmEvent e : pending) {
            items.add(offlineAlarmEventView(e));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", pending.size());
        result.put("alarms", items);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取缓存统计信息。
     */
    @Operation(summary = "获取缓存统计信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "统计信息"),
            @ApiResponse(responseCode = "503", description = "离线报警缓存服务不可用（依赖未注入）")
    })
    @GetMapping("/cache-stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        if (offlineAlarmCache == null) {
            return serviceUnavailable("OfflineAlarmCache is not available");
        }

        return ResponseEntity.ok(offlineAlarmCache.getCacheStats());
    }

    /**
     * 手动触发批量上传到 AlarmEventStore。
     * <p>
     * 将 OfflineAlarmCache 中缓存的所有报警事件批量上传到 AlarmEventStore，
     * 上传成功后自动清空缓存。
     */
    @Operation(summary = "手动触发批量上传到 AlarmEventStore",
            description = "将缓存中的所有离线报警事件批量上传到 AlarmEventStore")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上传结果（含 uploaded 数量）"),
            @ApiResponse(responseCode = "503", description = "离线报警缓存服务不可用（依赖未注入）")
    })
    @PostMapping("/flush")
    public ResponseEntity<Map<String, Object>> flush() {
        if (offlineAlarmCache == null) {
            return serviceUnavailable("OfflineAlarmCache is not available");
        }

        int uploaded = offlineAlarmCache.flushToStore(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("uploaded", uploaded);
        result.put("remaining", offlineAlarmCache.pendingCount());
        log.info("手动触发批量上传: 成功 {} 条, 剩余 {} 条", uploaded, offlineAlarmCache.pendingCount());
        return ResponseEntity.ok(result);
    }

    // ------------------------------------------------------------------
    // 边缘 AI 检测相关端点
    // ------------------------------------------------------------------

    /**
     * 模拟触发一次边缘 AI 检测。
     * <p>
     * body 包含 deviceId/deviceName/detectType/lat/lon/description
     */
    @Operation(summary = "模拟触发一次边缘 AI 检测",
            description = "body 包含 deviceId/deviceName/detectType(PERSON/VEHICLE/FIRE/ANIMAL)/lat/lon/description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "触发结果"),
            @ApiResponse(responseCode = "400", description = "参数错误"),
            @ApiResponse(responseCode = "503", description = "边缘 AI 触发服务不可用（依赖未注入）")
    })
    @PostMapping("/edge-ai/trigger")
    public ResponseEntity<Map<String, Object>> triggerEdgeAi(@RequestBody JsonNode body) {
        if (edgeAiTrigger == null) {
            return serviceUnavailable("EdgeAiTrigger is not available");
        }

        String deviceId = body.path("deviceId").asText("");
        if (deviceId.isBlank()) {
            return badRequest("deviceId is required");
        }
        String deviceName = body.path("deviceName").asText("");
        String detectTypeStr = body.path("detectType").asText("");
        if (detectTypeStr.isBlank()) {
            return badRequest("detectType is required");
        }

        EdgeAiTrigger.AiDetectType detectType;
        try {
            detectType = EdgeAiTrigger.AiDetectType.valueOf(detectTypeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return badRequest("unknown detectType: " + detectTypeStr
                    + ", supported: PERSON/VEHICLE/FIRE/ANIMAL");
        }

        double lat = body.path("lat").asDouble(0.0);
        double lon = body.path("lon").asDouble(0.0);
        String description = body.path("description").asText("");

        boolean triggered = edgeAiTrigger.triggerDetection(deviceId, deviceName, detectType,
                lat, lon, description);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", triggered ? "TRIGGERED" : "SKIPPED");
        result.put("deviceId", deviceId);
        result.put("detectType", detectType.name());
        result.put("message", triggered ? "边缘 AI 检测已触发并缓存" : "检测类型未启用或缓存不可用");
        return ResponseEntity.ok(result);
    }

    /**
     * 获取边缘 AI 检测统计。
     */
    @Operation(summary = "获取边缘 AI 检测统计")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "统计信息"),
            @ApiResponse(responseCode = "503", description = "边缘 AI 触发服务不可用（依赖未注入）")
    })
    @GetMapping("/edge-ai/stats")
    public ResponseEntity<Map<String, Object>> getEdgeAiStats() {
        if (edgeAiTrigger == null) {
            return serviceUnavailable("EdgeAiTrigger is not available");
        }

        return ResponseEntity.ok(edgeAiTrigger.getDetectionStats());
    }

    /**
     * 配置设备的启用检测类型。
     * <p>
     * body 包含 deviceId/enabledTypes（数组，元素为 PERSON/VEHICLE/FIRE/ANIMAL）
     */
    @Operation(summary = "配置设备的启用检测类型",
            description = "body 包含 deviceId/enabledTypes（数组，元素为 PERSON/VEHICLE/FIRE/ANIMAL）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "配置成功"),
            @ApiResponse(responseCode = "400", description = "参数错误"),
            @ApiResponse(responseCode = "503", description = "边缘 AI 触发服务不可用（依赖未注入）")
    })
    @PostMapping("/edge-ai/configure")
    public ResponseEntity<Map<String, Object>> configureEdgeAi(@RequestBody JsonNode body) {
        if (edgeAiTrigger == null) {
            return serviceUnavailable("EdgeAiTrigger is not available");
        }

        String deviceId = body.path("deviceId").asText("");
        if (deviceId.isBlank()) {
            return badRequest("deviceId is required");
        }

        JsonNode enabledTypesNode = body.path("enabledTypes");
        if (!enabledTypesNode.isArray()) {
            return badRequest("enabledTypes must be a JSON array");
        }

        Set<EdgeAiTrigger.AiDetectType> enabledTypes = new HashSet<>();
        for (JsonNode typeNode : enabledTypesNode) {
            String typeStr = typeNode.asText("");
            try {
                enabledTypes.add(EdgeAiTrigger.AiDetectType.valueOf(typeStr.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return badRequest("unknown detectType: " + typeStr
                        + ", supported: PERSON/VEHICLE/FIRE/ANIMAL");
            }
        }

        edgeAiTrigger.configureDetection(deviceId, enabledTypes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("deviceId", deviceId);
        result.put("enabledTypes", new ArrayList<>(enabledTypes));
        log.info("设备 {} 的边缘 AI 检测配置已更新: {}", deviceId, enabledTypes);
        return ResponseEntity.ok(result);
    }

    // ------------------------------------------------------------------
    // 内部辅助方法
    // ------------------------------------------------------------------

    /** 构建离线报警事件的视图 map。 */
    private static Map<String, Object> offlineAlarmEventView(OfflineAlarmCache.OfflineAlarmEvent e) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deviceId", e.getDeviceId());
        view.put("deviceName", e.getDeviceName());
        view.put("eventType", e.getEventType());
        view.put("severity", e.getSeverity());
        view.put("description", e.getDescription());
        view.put("lat", e.getLat());
        view.put("lon", e.getLon());
        view.put("alt", e.getAlt());
        view.put("timestampMs", e.getTimestampMs());
        view.put("cachedAtMs", e.getCachedAtMs());
        return view;
    }

    /** 构建 400 Bad Request 响应。 */
    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        return ResponseEntity.status(400).body(result);
    }

    /** 构建 503 Service Unavailable 响应。 */
    private static ResponseEntity<Map<String, Object>> serviceUnavailable(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNAVAILABLE");
        result.put("message", message);
        return ResponseEntity.status(503).body(result);
    }
}