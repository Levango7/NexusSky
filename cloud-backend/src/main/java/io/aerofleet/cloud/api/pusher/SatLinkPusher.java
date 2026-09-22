package io.aerofleet.cloud.api.pusher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.service.SatLinkMonitorService;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卫星链路 WebSocket 推送（M7 星-空-地多层级中继）。
 * <p>
 * 2Hz @Scheduled 检测链路状态变化，复用 {@link TelemetryWebSocketHandler#broadcast}。
 * 连接数为 0 时跳过（仿 MeshTopologyPusher 范式）。
 * <p>
 * 推送格式：
 * <pre>
 * {"type":"sat-link","version":N,"satLinks":[...],"passes":[...],"routeDecisions":[...]}
 * </pre>
 */
@Component
public class SatLinkPusher {

    private static final Logger log = LoggerFactory.getLogger(SatLinkPusher.class);

    private final TelemetryWebSocketHandler handler;
    private final SatLinkMonitorService monitorService;
    private final ObjectMapper mapper;
    private volatile long lastPushedVersion = 0;

    public SatLinkPusher(TelemetryWebSocketHandler handler,
                         SatLinkMonitorService monitorService,
                         ObjectMapper mapper) {
        this.handler = handler;
        this.monitorService = monitorService;
        this.mapper = mapper;
    }

    /**
     * 2Hz 推送检测：版本号变化时组装帧广播。
     * 连接数为 0 时跳过。
     */
    @Scheduled(fixedDelay = 500)
    public void pushOnce() {
        if (handler.connectionCount() == 0) {
            return;
        }
        long currentVersion = monitorService.currentVersion();
        if (currentVersion == lastPushedVersion) {
            return;
        }
        lastPushedVersion = currentVersion;
        try {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", "sat-link");
            frame.put("version", currentVersion);

            // 卫星链路状态
            List<Map<String, Object>> satLinks = new ArrayList<>();
            for (SatLinkMonitorService.SatLinkSnapshot s : monitorService.getAllLinkStatuses().values()) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("satId", s.satId());
                v.put("visible", s.visible() != 0);
                v.put("elevationDeg", s.elevationDeg());
                v.put("delayMs", s.delayMs());
                v.put("bandwidthMbps", s.bandwidthMbps());
                v.put("simulated", s.simulated());
                satLinks.add(v);
            }
            frame.put("satLinks", satLinks);

            // 路由决策
            List<Map<String, Object>> routeDecisions = new ArrayList<>();
            for (SatLinkMonitorService.RouteDecisionSnapshot d : monitorService.getRouteDecisions()) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("chosenLayer", d.chosenLayer() == 255 ? null : d.chosenLayer());
                v.put("estimatedDelayMs", d.estimatedDelayMs() == 65535 ? -1 : d.estimatedDelayMs());
                v.put("pathNodes", d.pathNodes());
                v.put("decisionReason", d.decisionReason());
                v.put("timestamp", d.timestamp());
                routeDecisions.add(v);
            }
            frame.put("routeDecisions", routeDecisions);

            // 当前策略
            frame.put("strategy", monitorService.getCurrentStrategy());

            handler.broadcast(mapper.writeValueAsString(frame), mapper);
        } catch (Exception e) {
            log.warn("sat-link push failed: {}", e.getMessage());
        }
    }
}