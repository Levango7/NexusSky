package io.aerofleet.cloud.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.orch.event.EmergencyEndEvent;
import io.aerofleet.cloud.orch.event.EmergencyStartEvent;
import io.aerofleet.cloud.orch.event.PausePlanEvent;
import io.aerofleet.cloud.orch.event.ResumePlanEvent;
import io.aerofleet.cloud.orch.event.StepCompleteEvent;
import io.aerofleet.cloud.orch.event.StepFailEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

import java.util.Map;

/**
 * 编排计划状态变更 WebSocket 推送。
 * <p>
 * 通过 {@link EventListener} 监听编排事件，自动组装 JSON 帧并
 * 复用 {@link TelemetryWebSocketHandler#broadcast} 推送到前端。
 * <p>
 * 推送帧类型：
 * <pre>
 * {"type":"step-complete",  "planId":N, "stepId":"...", "moduleTaskId":"...", "timestamp":T}
 * {"type":"step-fail",      "planId":N, "stepId":"...", "failReason":"...",    "timestamp":T}
 * {"type":"plan-pause",     "planId":N, "reason":"...",                       "timestamp":T}
 * {"type":"plan-resume",    "planId":N,                                       "timestamp":T}
 * {"type":"emergency-start","planId":N, "droneIds":[...],                     "timestamp":T}
 * {"type":"emergency-end",  "planId":N,                                       "timestamp":T}
 * </pre>
 * 无 WebSocket 连接或 handler 为 null 时降级为日志输出。
 */
@Component
public class OrchestrationPusher {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationPusher.class);

    private final TelemetryWebSocketHandler handler;
    private final ObjectMapper mapper;

    @Autowired
    public OrchestrationPusher(TelemetryWebSocketHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    /** 监听步骤完成事件，推送步骤完成通知。 */
    @EventListener
    public void onStepComplete(StepCompleteEvent event) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "step-complete");
        frame.put("planId", event.getPlanId());
        frame.put("stepId", event.getStepId());
        frame.put("moduleTaskId", event.getModuleTaskId());
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 监听步骤失败事件，推送步骤失败通知。 */
    @EventListener
    public void onStepFail(StepFailEvent event) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "step-fail");
        frame.put("planId", event.getPlanId());
        frame.put("stepId", event.getStepId());
        frame.put("failReason", event.getFailReason());
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 监听计划暂停事件，推送计划暂停通知。 */
    @EventListener
    public void onPausePlan(PausePlanEvent event) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "plan-pause");
        frame.put("planId", event.getPlanId());
        frame.put("reason", event.getReason());
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 监听计划恢复事件，推送计划恢复通知。 */
    @EventListener
    public void onResumePlan(ResumePlanEvent event) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "plan-resume");
        frame.put("planId", event.getPlanId());
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 监听应急启动事件，推送应急启动通知。 */
    @EventListener
    public void onEmergencyStart(EmergencyStartEvent event) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "emergency-start");
        frame.put("planId", event.getPlanId());
        frame.put("droneIds", event.getDroneIds());
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 监听应急结束事件，推送应急结束通知。 */
    @EventListener
    public void onEmergencyEnd(EmergencyEndEvent event) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "emergency-end");
        frame.put("planId", event.getPlanId());
        frame.put("timestamp", System.currentTimeMillis());
        broadcast(frame);
    }

    /** 广播帧；无连接或无 handler 时降级为日志。 */
    private void broadcast(Map<String, Object> frame) {
        if (handler == null || handler.connectionCount() == 0) {
            log.debug("orch push (no ws clients): type={} planId={}",
                    frame.get("type"), frame.get("planId"));
            return;
        }
        try {
            handler.broadcast(mapper.writeValueAsString(frame), mapper);
        } catch (Exception e) {
            log.warn("orch push failed: type={} planId={}: {}",
                    frame.get("type"), frame.get("planId"), e.getMessage());
        }
    }
}