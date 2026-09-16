package io.aerofleet.cloud.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应急编排 WebSocket 推送（M9 应急任务编排，FR-30）。
 * <p>
 * 由 {@link EmergencyOrchService} 在计划状态变更时主动调用，组装 JSON 帧并
 * 复用 {@link TelemetryWebSocketHandler#broadcast}（既有不变）。
 * <p>
 * 推送帧类型：
 * <pre>
 * {"type":"emergency-plan",    "planId":N, "plan":{...}, "timestamp":T}
 * {"type":"emergency-coverage","planId":N, "coverage":{...}, "timestamp":T}
 * {"type":"emergency-priority","planId":N, "priority":{...}, "timestamp":T}
 * {"type":"emergency-event",   "planId":N, "event":{...},  "timestamp":T}
 * </pre>
 * 连接数为 0 或 handler 为 null（测试场景）时降级为日志输出。
 */
@Component
public class EmergencyOrchPusher {

    private static final Logger log = LoggerFactory.getLogger(EmergencyOrchPusher.class);

    private final TelemetryWebSocketHandler handler;
    private final ObjectMapper mapper;

    public EmergencyOrchPusher(TelemetryWebSocketHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    /** 推送计划状态更新。 */
    public void pushPlanUpdate(long planId, Map<String, Object> plan) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "emergency-plan");
        frame.put("planId", planId);
        frame.put("plan", plan);
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 推送覆盖优化更新。 */
    public void pushCoverageUpdate(long planId, Map<String, Object> coverage) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "emergency-coverage");
        frame.put("planId", planId);
        frame.put("coverage", coverage);
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 推送优先级调度更新。 */
    public void pushPriorityUpdate(long planId, Map<String, Object> priority) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "emergency-priority");
        frame.put("planId", planId);
        frame.put("priority", priority);
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 推送编排事件（阶段切换、异常等）。 */
    public void pushEvent(long planId, Map<String, Object> event) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "emergency-event");
        frame.put("planId", planId);
        frame.put("event", event);
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 广播帧；无连接或无 handler 时降级为日志。 */
    private void broadcast(Map<String, Object> frame) {
        if (handler == null || handler.connectionCount() == 0) {
            log.debug("emergency push (no ws clients): type={} planId={}",
                    frame.get("type"), frame.get("planId"));
            return;
        }
        try {
            handler.broadcast(mapper.writeValueAsString(frame), mapper);
        } catch (Exception e) {
            log.warn("emergency push failed: type={} planId={}: {}",
                    frame.get("type"), frame.get("planId"), e.getMessage());
        }
    }
}