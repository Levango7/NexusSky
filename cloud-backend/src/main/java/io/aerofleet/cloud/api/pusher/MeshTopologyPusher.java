package io.aerofleet.cloud.api.pusher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.dto.MeshNodeSnapshot;
import io.aerofleet.cloud.api.service.MeshTopologyService;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mesh 拓扑变化 WebSocket 推送（M5 应急 mesh，FR-29）。
 * <p>
 * 2Hz @Scheduled 检测拓扑变化，1 秒 debounce 抑制事件风暴，
 * 复用 {@link TelemetryWebSocketHandler#broadcast}（既有不变）。
 * <p>
 * 推送格式：
 * <pre>
 * {"type":"mesh-topology","version":N,"events":[...],"snapshot":{...}}
 * </pre>
 * 事件类型：NODE_JOINED / NODE_LEFT / LINK_UP / LINK_DOWN / ROUTE_CHANGED。
 * <p>
 * 节点离线判定：6s 未上报视为离线 → 生成 NODE_LEFT 事件。
 */
@Component
public class MeshTopologyPusher {

    private static final Logger log = LoggerFactory.getLogger(MeshTopologyPusher.class);

    private final TelemetryWebSocketHandler handler;
    private final MeshTopologyService topologyService;
    private final ObjectMapper mapper;

    /** debounce 缓存：事件 key → 最近一次时间戳。 */
    private final Map<String, Long> debounceCache = new HashMap<>();
    /** debounce 阈值（ms）。 */
    private static final long DEBOUNCE_MS = 1000;

    public MeshTopologyPusher(TelemetryWebSocketHandler handler,
                              MeshTopologyService topologyService,
                              ObjectMapper mapper) {
        this.handler = handler;
        this.topologyService = topologyService;
        this.mapper = mapper;
    }

    /**
     * 2Hz 推送检测（FR-29）：调用 detectChanges()，若有变化则组装事件帧广播。
     * 连接数为 0 时跳过（仿 HardwarePusher 范式）。
     */
    @Scheduled(fixedDelay = 500)
    public void pushOnce() {
        if (handler.connectionCount() == 0) {
            return;
        }
        try {
            List<MeshTopologyService.MeshTopologyEvent> events = topologyService.detectChanges();
            if (events.isEmpty()) {
                return;
            }
            // debounce：同一事件 1 秒内只推送最后一次
            long now = System.currentTimeMillis();
            List<MeshTopologyService.MeshTopologyEvent> filtered = new ArrayList<>();
            for (MeshTopologyService.MeshTopologyEvent e : events) {
                String key = eventKey(e);
                Long lastSeen = debounceCache.get(key);
                if (lastSeen == null || now - lastSeen > DEBOUNCE_MS) {
                    filtered.add(e);
                    debounceCache.put(key, now);
                }
            }
            if (filtered.isEmpty()) {
                return;
            }
            // 组装推送帧
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", "mesh-topology");
            frame.put("version", topologyService.currentVersion());
            List<Map<String, Object>> eventList = new ArrayList<>();
            for (MeshTopologyService.MeshTopologyEvent e : filtered) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("type", e.type().name());
                ev.put("from", e.fromSysid());
                ev.put("to", e.toSysid());
                ev.put("timestamp", e.timestamp());
                eventList.add(ev);
            }
            frame.put("events", eventList);
            // 附带当前快照
            Map<String, Object> snapshot = new LinkedHashMap<>();
            Map<Integer, MeshNodeSnapshot> all = topologyService.getAllSnapshots();
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (MeshNodeSnapshot s : all.values()) {
                Map<String, Object> nv = new LinkedHashMap<>();
                nv.put("sysid", s.sysid);
                nv.put("lastUpdateMs", s.lastUpdateMs);
                nv.put("neighborCount", s.neighbors.size());
                nodes.add(nv);
            }
            snapshot.put("nodeCount", nodes.size());
            snapshot.put("nodes", nodes);
            frame.put("snapshot", snapshot);
            handler.broadcast(mapper.writeValueAsString(frame), mapper);
        } catch (Exception e) {
            log.warn("mesh topology push failed: {}", e.getMessage());
        }
    }

    /** 事件去重 key。 */
    private static String eventKey(MeshTopologyService.MeshTopologyEvent e) {
        return e.type().name() + ":" + e.fromSysid() + ":" + e.toSysid();
    }
}