package io.aerofleet.cloud.alarm;

import io.aerofleet.cloud.mission.EmergencyCommand;
import io.aerofleet.cloud.mission.EmergencyCommandWorkflow;
import io.aerofleet.cloud.mission.OneClickEmergencyResponse;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * 报警联动 REST 端点（M10 报警联动编排，FR-31）。
 * <p>
 * 独立路径前缀 /api/alarms/*，提供报警事件接收/查询/确认与联动规则 CRUD。
 * <p>
 * 端点清单：
 * <pre>
 * POST   /api/alarms/events            接收报警事件（来自安防设备）
 * GET    /api/alarms/events            查询报警事件列表（分页/筛选）
 * GET    /api/alarms/events/{id}       获取报警事件详情
 * POST   /api/alarms/events/{id}/ack   确认报警
 * POST   /api/alarms/events/ack-batch  批量确认报警
 * POST   /api/alarms/events/{id}/respond 一键应急响应
 * GET    /api/alarms/stream            报警事件 SSE 实时推送
 * GET    /api/alarms/linkage-logs      联动执行日志查询
 * GET    /api/alarms/rules             列出联动规则
 * POST   /api/alarms/rules             创建联动规则
 * PUT    /api/alarms/rules/{id}        更新联动规则
 * DELETE /api/alarms/rules/{id}        删除联动规则
 * POST   /api/alarms/rules/{id}/test   测试联动规则（模拟触发）
 * </pre>
 */
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private static final Logger log = LoggerFactory.getLogger(AlarmController.class);

    /** SSE 心跳间隔（秒）。 */
    private static final long SSE_HEARTBEAT_SECONDS = 15L;
    /** SSE 超时时间（毫秒，30 分钟）。 */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    /** SSE 事件轮询间隔（毫秒）。 */
    private static final long SSE_POLL_INTERVAL_MS = 2000L;

    private final AlarmLinkageEngine engine;
    private final AlarmEventStore store;
    private final EmergencyCommandWorkflow emergencyWorkflow;
    private final OneClickEmergencyResponse oneClickResponse;
    /** SSE 心跳与事件轮询调度器：单线程足够，多个 emitter 共享。 */
    private final ScheduledExecutorService sseScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "alarm-sse-scheduler");
                t.setDaemon(true);
                return t;
            });

    public AlarmController(AlarmLinkageEngine engine, AlarmEventStore store,
                           EmergencyCommandWorkflow emergencyWorkflow,
                           OneClickEmergencyResponse oneClickResponse) {
        this.engine = engine;
        this.store = store;
        this.emergencyWorkflow = emergencyWorkflow;
        this.oneClickResponse = oneClickResponse;
    }

    /**
     * 容器销毁时关闭 SSE 调度器，避免线程泄漏。
     */
    @PreDestroy
    public void shutdown() {
        sseScheduler.shutdownNow();
        log.info("Alarm SSE scheduler shut down");
    }

    // =====================================================================
    // 报警事件端点
    // =====================================================================

    /**
     * 接收报警事件（来自安防设备）。
     * <p>
     * 接收后立即由 {@link AlarmLinkageEngine} 处理：存储 + 匹配规则 + 执行联动。
     */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> receiveEvent(@RequestBody Map<String, Object> body) {
        AlarmEvent event = parseEvent(body);
        AlarmLinkageEngine.ProcessResult result = engine.processEvent(event);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("eventId", event.getId());
        resp.put("matchedCount", result.getMatchedCount());
        resp.put("executions", executionsToList(result.getExecutions()));
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    /**
     * 查询报警事件列表（分页/筛选）。
     *
     * @param page     页码（0-based，默认 0）
     * @param size     每页大小（默认 20）
     * @param severity 严重程度过滤（INFO/WARN/CRITICAL）
     * @param type     事件类型过滤（MOTION/INTRUSION/FIRE/DOOR/CUSTOM）
     */
    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> queryEvents(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "severity", required = false) String severity,
            @RequestParam(value = "type", required = false) String type) {
        AlarmEventStore.PageResult result = store.query(page, size, severity, type);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", eventsToList(result.getItems()));
        resp.put("total", result.getTotal());
        resp.put("page", result.getPage());
        resp.put("size", result.getSize());
        return ResponseEntity.ok(resp);
    }

    /** 获取报警事件详情。 */
    @GetMapping("/events/{id}")
    public ResponseEntity<Map<String, Object>> getEvent(@PathVariable("id") String id) {
        AlarmEvent event = store.getById(id);
        if (event == null) {
            throw new NotFoundException("alarm event not found: " + id);
        }
        return ResponseEntity.ok(eventToMap(event));
    }

    /** 确认报警。 */
    @PostMapping("/events/{id}/ack")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> acknowledge(@PathVariable("id") String id) {
        boolean ok = store.acknowledge(id);
        if (!ok) {
            throw new NotFoundException("alarm event not found: " + id);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("eventId", id);
        resp.put("acknowledged", true);
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    // =====================================================================
    // 批量确认 / 一键应急响应 / SSE 实时推送 / 联动日志
    // =====================================================================

    /**
     * 批量确认报警。
     * <p>
     * body: {@code {"eventIds": ["id1", "id2", ...]}}
     * <p>
     * 循环调用 {@link AlarmEventStore#acknowledge}，返回成功确认的数量。
     */
    @PostMapping("/events/ack-batch")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> acknowledgeBatch(@RequestBody Map<String, Object> body) {
        Object idsObj = body.get("eventIds");
        if (idsObj == null) {
            throw new BadRequestException("field 'eventIds' is required");
        }
        if (!(idsObj instanceof List)) {
            throw new BadRequestException("field 'eventIds' must be a list of strings");
        }
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) idsObj;
        if (rawIds.isEmpty()) {
            throw new BadRequestException("field 'eventIds' must not be empty");
        }

        int successCount = 0;
        List<String> failedIds = new ArrayList<>();
        for (Object o : rawIds) {
            String eventId = String.valueOf(o);
            if (store.acknowledge(eventId)) {
                successCount++;
            } else {
                failedIds.add(eventId);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalRequested", rawIds.size());
        resp.put("successCount", successCount);
        resp.put("failedIds", failedIds);
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    /**
     * 一键应急响应。
     * <p>
     * 从报警事件触发无人机侦察任务：查找报警事件 → 创建 EmergencyCommand →
     * 调用 {@link OneClickEmergencyResponse#execute} 启动一键应急响应。
     * <p>
     * 返回: {@code {"commandId", "status", "message"}}
     */
    @PostMapping("/events/{id}/respond")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> triggerEmergencyResponse(@PathVariable("id") String id) {
        AlarmEvent event = store.getById(id);
        if (event == null) {
            throw new NotFoundException("alarm event not found: " + id);
        }

        // 报警事件类型 → 应急事件类型映射
        EmergencyCommand.IncidentType incidentType = mapIncidentType(event.getEventType());
        EmergencyCommand.Severity severity = mapSeverity(event.getSeverity());
        EmergencyCommand.Location location = new EmergencyCommand.Location(
                event.getLat(), event.getLon(), event.getAlt());

        // 创建应急指挥命令
        EmergencyCommand cmd = new EmergencyCommand(
                null, incidentType, severity, location,
                "报警事件触发: " + event.getDescription(),
                "alarm-system", "alarm-event:" + id,
                System.currentTimeMillis());
        EmergencyCommand created = emergencyWorkflow.createCommand(cmd);

        // 启动一键应急响应
        EmergencyCommand executing = oneClickResponse.execute(created.getId());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("commandId", created.getId());
        resp.put("eventId", id);
        if (executing != null) {
            resp.put("status", executing.getCurrentPhase().name());
            resp.put("message", "emergency response triggered successfully");
        } else {
            resp.put("status", "FAILED");
            resp.put("message", "failed to execute emergency response");
        }
        resp.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(resp);
    }

    /**
     * 报警事件 SSE 实时推送。
     * <p>
     * 客户端通过 EventSource 连接本端点，服务端会：
     * <ol>
     *   <li>每 2 秒轮询 {@link AlarmEventStore} 获取最新事件并推送</li>
     *   <li>每 15 秒发送一次 SSE 心跳注释，保持连接</li>
     * </ol>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 记录已推送的事件数量，用于增量推送
        int[] lastSeenSize = {store.size()};

        // 事件轮询：每 2 秒检查是否有新事件
        ScheduledFuture<?> pollFuture = sseScheduler.scheduleAtFixedRate(() -> {
            try {
                int currentSize = store.size();
                if (currentSize > lastSeenSize[0]) {
                    // 有新事件，查询最新的事件推送
                    AlarmEventStore.PageResult result = store.query(0, currentSize - lastSeenSize[0], null, null);
                    for (AlarmEvent e : result.getItems()) {
                        emitter.send(SseEmitter.event()
                                .name("alarm-event")
                                .data(eventToMap(e)));
                    }
                    lastSeenSize[0] = currentSize;
                }
            } catch (Exception e) {
                log.debug("SSE event poll failed: {}", e.getMessage());
            }
        }, SSE_POLL_INTERVAL_MS, SSE_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // SSE 心跳：每 15 秒发送注释行，保持连接活跃
        ScheduledFuture<?> heartbeatFuture = sseScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                log.debug("SSE heartbeat failed: {}", e.getMessage());
            }
        }, SSE_HEARTBEAT_SECONDS, SSE_HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        emitter.onCompletion(() -> {
            pollFuture.cancel(false);
            heartbeatFuture.cancel(false);
            log.info("Alarm SSE stream completed");
        });
        emitter.onTimeout(() -> {
            pollFuture.cancel(false);
            heartbeatFuture.cancel(false);
            log.info("Alarm SSE stream timed out");
        });
        emitter.onError(e -> {
            pollFuture.cancel(false);
            heartbeatFuture.cancel(false);
            log.warn("Alarm SSE stream error: {}", e.getMessage());
        });
        log.info("Alarm SSE stream established");
        return emitter;
    }

    /**
     * 查询联动执行日志。
     * <p>
     * 返回最近 N 条联动执行记录（含事件 ID、规则 ID、动作类型、执行状态等）。
     *
     * @param limit 最多返回条数（默认 100）
     */
    @GetMapping("/linkage-logs")
    public ResponseEntity<Map<String, Object>> listLinkageLogs(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (limit <= 0) {
            limit = 100;
        }
        List<AlarmLinkageEngine.LinkageLog> logs = engine.getLinkageLogs(limit);

        List<Map<String, Object>> items = new ArrayList<>();
        for (AlarmLinkageEngine.LinkageLog l : logs) {
            items.add(linkageLogToMap(l));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        resp.put("limit", limit);
        return ResponseEntity.ok(resp);
    }

    // =====================================================================
    // 联动规则端点
    // =====================================================================

    /** 列出联动规则。 */
    @GetMapping("/rules")
    public ResponseEntity<Map<String, Object>> listRules() {
        List<AlarmLinkageRule> rules = engine.getAllRules();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", rulesToList(rules));
        resp.put("total", rules.size());
        return ResponseEntity.ok(resp);
    }

    /** 创建联动规则。 */
    @PostMapping("/rules")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> createRule(@RequestBody Map<String, Object> body) {
        AlarmLinkageRule rule = parseRule(body);
        engine.addRule(rule);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ruleId", rule.getId());
        resp.put("created", true);
        return ResponseEntity.ok(resp);
    }

    /** 更新联动规则。 */
    @PutMapping("/rules/{id}")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> updateRule(@PathVariable("id") String id,
                                                          @RequestBody Map<String, Object> body) {
        AlarmLinkageRule existing = engine.getRule(id);
        if (existing == null) {
            throw new NotFoundException("rule not found: " + id);
        }
        AlarmLinkageRule rule = parseRule(body, id);
        engine.addRule(rule);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ruleId", id);
        resp.put("updated", true);
        return ResponseEntity.ok(resp);
    }

    /** 删除联动规则。 */
    @DeleteMapping("/rules/{id}")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable("id") String id) {
        AlarmLinkageRule removed = engine.removeRule(id);
        if (removed == null) {
            throw new NotFoundException("rule not found: " + id);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ruleId", id);
        resp.put("deleted", true);
        return ResponseEntity.ok(resp);
    }

    /**
     * 测试联动规则（模拟触发）。
     * <p>
     * 构造一个模拟报警事件（CRITICAL 严重程度 + 规则匹配的设备 ID），
     * 调用引擎处理，返回匹配结果。用于规则配置后的验证。
     */
    @PostMapping("/rules/{id}/test")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> testRule(@PathVariable("id") String id,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        AlarmLinkageRule rule = engine.getRule(id);
        if (rule == null) {
            throw new NotFoundException("rule not found: " + id);
        }
        // 构造模拟事件
        double lat = body != null && body.containsKey("lat") ? num(body, "lat", 0) : 39.9;
        double lon = body != null && body.containsKey("lon") ? num(body, "lon", 0) : 116.3;
        String deviceId = body != null && body.containsKey("deviceId")
                ? String.valueOf(body.get("deviceId"))
                : (rule.getMatchDeviceIds().isEmpty() ? "test-device" : rule.getMatchDeviceIds().iterator().next());
        AlarmEvent testEvent = AlarmEvent.from(
                deviceId, "test-device",
                rule.getMatchEventType() != null ? rule.getMatchEventType().name() : "CUSTOM",
                lat, lon, "test trigger for rule " + id);
        // 测试事件用 CRITICAL 严重程度确保匹配
        AlarmEvent criticalEvent = new AlarmEvent(
                testEvent.getId(), testEvent.getSourceDeviceId(), testEvent.getSourceDeviceName(),
                testEvent.getEventType(), AlarmEvent.Severity.CRITICAL, testEvent.getDescription(),
                testEvent.getLat(), testEvent.getLon(), testEvent.getAlt(),
                testEvent.getTimestampMs(), false);

        AlarmLinkageEngine.ProcessResult result = engine.processEvent(criticalEvent);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ruleId", id);
        resp.put("testEventId", result.getEventId());
        resp.put("matched", result.getMatchedCount() > 0);
        resp.put("matchedCount", result.getMatchedCount());
        resp.put("executions", executionsToList(result.getExecutions()));
        return ResponseEntity.ok(resp);
    }

    // =====================================================================
    // 请求/响应转换辅助
    // =====================================================================

    /** 从请求体解析报警事件。 */
    private static AlarmEvent parseEvent(Map<String, Object> body) {
        String deviceId = str(body, "sourceDeviceId", "");
        String deviceName = str(body, "sourceDeviceName", "");
        String eventType = str(body, "eventType", "CUSTOM");
        String severityStr = str(body, "severity", "WARN");
        String desc = str(body, "description", "");
        double lat = num(body, "lat", 0);
        double lon = num(body, "lon", 0);
        double alt = num(body, "alt", 0);
        long ts = body.containsKey("timestampMs") ? longVal(body, "timestampMs") : System.currentTimeMillis();
        String id = str(body, "id", java.util.UUID.randomUUID().toString());

        return new AlarmEvent(
                id, deviceId, deviceName,
                AlarmEvent.parseEventType(eventType),
                AlarmEvent.Severity.fromString(severityStr),
                desc, lat, lon, alt, ts, false);
    }

    /** 从请求体解析联动规则。 */
    private static AlarmLinkageRule parseRule(Map<String, Object> body) {
        String id = str(body, "id", java.util.UUID.randomUUID().toString());
        return parseRule(body, id);
    }

    /** 从请求体解析联动规则（指定 ID）。 */
    @SuppressWarnings("unchecked")
    private static AlarmLinkageRule parseRule(Map<String, Object> body, String id) {
        String name = str(body, "name", "unnamed");
        boolean enabled = body.containsKey("enabled") ? Boolean.parseBoolean(String.valueOf(body.get("enabled"))) : true;
        String eventTypeStr = str(body, "matchEventType", "");
        AlarmEvent.EventType matchEventType = eventTypeStr.isEmpty() ? null : AlarmEvent.parseEventType(eventTypeStr);
        String severityStr = str(body, "matchSeverity", "INFO");
        AlarmEvent.Severity matchSeverity = AlarmEvent.Severity.fromString(severityStr);
        Set<String> deviceIds = new java.util.LinkedHashSet<>();
        Object devObj = body.get("matchDeviceIds");
        if (devObj instanceof List) {
            for (Object o : (List<Object>) devObj) {
                deviceIds.add(String.valueOf(o));
            }
        }
        String actionStr = str(body, "actionType", "DEPLOY_DRONE");
        AlarmLinkageRule.ActionType actionType;
        try {
            actionType = AlarmLinkageRule.ActionType.valueOf(actionStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid actionType: " + actionStr);
        }
        int droneCount = (int) num(body, "droneCount", 1);
        if (droneCount < 1) {
            throw new BadRequestException("droneCount must be >= 1");
        }
        double targetLat = body.containsKey("targetLat") ? num(body, "targetLat", 0) : Double.NaN;
        double targetLon = body.containsKey("targetLon") ? num(body, "targetLon", 0) : Double.NaN;
        double targetRadiusM = num(body, "targetRadiusM", 500);
        double altitudeM = num(body, "altitudeM", 80);
        String taskTemplate = str(body, "taskTemplate", "");

        return new AlarmLinkageRule(
                id, name, enabled, matchEventType, matchSeverity, deviceIds,
                actionType, droneCount, targetLat, targetLon, targetRadiusM, altitudeM,
                taskTemplate.isEmpty() ? null : taskTemplate);
    }

    /** 事件 → map。 */
    static Map<String, Object> eventToMap(AlarmEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("sourceDeviceId", e.getSourceDeviceId());
        m.put("sourceDeviceName", e.getSourceDeviceName());
        m.put("eventType", e.getEventType().name());
        m.put("severity", e.getSeverity().name());
        m.put("description", e.getDescription());
        m.put("lat", e.getLat());
        m.put("lon", e.getLon());
        m.put("alt", e.getAlt());
        m.put("timestampMs", e.getTimestampMs());
        m.put("acknowledged", e.isAcknowledged());
        return m;
    }

    private static List<Map<String, Object>> eventsToList(List<AlarmEvent> events) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AlarmEvent e : events) {
            list.add(eventToMap(e));
        }
        return list;
    }

    /** 规则 → map。 */
    static Map<String, Object> ruleToMap(AlarmLinkageRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("enabled", r.isEnabled());
        m.put("matchEventType", r.getMatchEventType() == null ? null : r.getMatchEventType().name());
        m.put("matchSeverity", r.getMatchSeverity().name());
        m.put("matchDeviceIds", new ArrayList<>(r.getMatchDeviceIds()));
        m.put("actionType", r.getActionType().name());
        m.put("droneCount", r.getDroneCount());
        m.put("targetLat", r.getTargetLat());
        m.put("targetLon", r.getTargetLon());
        m.put("targetRadiusM", r.getTargetRadiusM());
        m.put("altitudeM", r.getAltitudeM());
        m.put("taskTemplate", r.getTaskTemplate());
        return m;
    }

    private static List<Map<String, Object>> rulesToList(List<AlarmLinkageRule> rules) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AlarmLinkageRule r : rules) {
            list.add(ruleToMap(r));
        }
        return list;
    }

    /** 执行结果 → map。 */
    private static Map<String, Object> executionToMap(AlarmLinkageEngine.ActionExecution e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", e.getRuleId());
        m.put("actionType", e.getActionType());
        m.put("status", e.getStatus());
        m.put("planId", e.getPlanId());
        if (e.getTaskTemplate() != null) {
            m.put("taskTemplate", e.getTaskTemplate());
        }
        if (e.getError() != null) {
            m.put("error", e.getError());
        }
        return m;
    }

    private static List<Map<String, Object>> executionsToList(List<AlarmLinkageEngine.ActionExecution> execs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AlarmLinkageEngine.ActionExecution e : execs) {
            list.add(executionToMap(e));
        }
        return list;
    }

    /** 联动日志 → map。 */
    static Map<String, Object> linkageLogToMap(AlarmLinkageEngine.LinkageLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", l.getEventId());
        m.put("ruleId", l.getRuleId());
        m.put("actionType", l.getActionType());
        m.put("status", l.getStatus());
        m.put("planId", l.getPlanId());
        if (l.getError() != null) {
            m.put("error", l.getError());
        }
        m.put("timestampMs", l.getTimestampMs());
        return m;
    }

    /** 报警事件类型 → 应急事件类型映射。 */
    private static EmergencyCommand.IncidentType mapIncidentType(AlarmEvent.EventType type) {
        return switch (type) {
            case FIRE -> EmergencyCommand.IncidentType.FIRE;
            case INTRUSION, MOTION, DOOR -> EmergencyCommand.IncidentType.SECURITY_ALARM;
            case CUSTOM -> EmergencyCommand.IncidentType.OTHER;
        };
    }

    /** 报警严重程度 → 应急严重级别映射。 */
    private static EmergencyCommand.Severity mapSeverity(AlarmEvent.Severity severity) {
        return switch (severity) {
            case INFO -> EmergencyCommand.Severity.INFO;
            case WARN -> EmergencyCommand.Severity.WARN;
            case CRITICAL -> EmergencyCommand.Severity.CRITICAL;
        };
    }

    private static String str(Map<String, Object> body, String key, String def) {
        Object v = body.get(key);
        return v == null ? def : v.toString();
    }

    private static double num(Map<String, Object> body, String key, double def) {
        Object v = body.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("field '" + key + "' is not a valid number: " + v);
        }
    }

    private static long longVal(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("field '" + key + "' is not a valid long: " + v);
        }
    }
}