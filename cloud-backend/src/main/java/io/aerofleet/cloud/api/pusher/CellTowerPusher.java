package io.aerofleet.cloud.api.pusher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.dto.CellTowerSnapshot;
import io.aerofleet.cloud.api.service.CellTowerTopologyService;
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
 * 基站拓扑变化 WebSocket 推送（M6 移动基站载荷抽象，FR-NFER-OBS-02）。
 * <p>
 * 2Hz @Scheduled 检测拓扑变化，1 秒 debounce 抑制事件风暴，
 * 复用 {@link TelemetryWebSocketHandler#broadcast}（既有不变）。
 * <p>
 * 推送格式：
 * <pre>
 * {"type":"celltower-topology","version":N,"events":[...],"snapshot":{...}}
 * </pre>
 * 事件类型：TOWER_ONLINE / TOWER_OFFLINE / COVERAGE_CHANGED / TERMINAL_JOINED / TERMINAL_LEFT / HANDOVER。
 */
@Component
public class CellTowerPusher {

    private static final Logger log = LoggerFactory.getLogger(CellTowerPusher.class);

    private final TelemetryWebSocketHandler handler;
    private final CellTowerTopologyService topologyService;
    private final ObjectMapper mapper;

    /** debounce 缓存：事件 key → 最近一次时间戳。 */
    private final Map<String, Long> debounceCache = new HashMap<>();
    /** debounce 阈值（ms）。 */
    private static final long DEBOUNCE_MS = 1000;

    public CellTowerPusher(TelemetryWebSocketHandler handler,
                           CellTowerTopologyService topologyService,
                           ObjectMapper mapper) {
        this.handler = handler;
        this.topologyService = topologyService;
        this.mapper = mapper;
    }

    /**
     * 2Hz 推送检测：调用 detectChanges()，若有变化则组装事件帧广播。
     * 连接数为 0 时跳过（仿 MeshTopologyPusher 范式）。
     */
    @Scheduled(fixedDelay = 500)
    public void pushOnce() {
        if (handler.connectionCount() == 0) {
            return;
        }
        try {
            List<CellTowerTopologyService.CellTowerEvent> events = topologyService.detectChanges();
            if (events.isEmpty()) {
                return;
            }
            // debounce：同一事件 1 秒内只推送最后一次
            long now = System.currentTimeMillis();
            List<CellTowerTopologyService.CellTowerEvent> filtered = new ArrayList<>();
            for (CellTowerTopologyService.CellTowerEvent e : events) {
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
            frame.put("type", "celltower-topology");
            frame.put("version", topologyService.currentVersion());
            List<Map<String, Object>> eventList = new ArrayList<>();
            for (CellTowerTopologyService.CellTowerEvent e : filtered) {
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
            Map<Integer, CellTowerSnapshot> all = topologyService.getAllSnapshots();
            List<Map<String, Object>> towers = new ArrayList<>();
            for (CellTowerSnapshot s : all.values()) {
                Map<String, Object> tv = new LinkedHashMap<>();
                tv.put("sysid", s.sysid);
                tv.put("cellType", s.cellType);
                tv.put("coverageRadiusM", s.coverageRadiusM);
                tv.put("connectedTerminals", s.connectedTerminals);
                tv.put("capacityUtilization", s.capacityUtilization);
                tv.put("lastUpdateMs", s.lastUpdateMs);
                towers.add(tv);
            }
            snapshot.put("towerCount", towers.size());
            snapshot.put("terminalCount", topologyService.registeredTerminalCount());
            snapshot.put("towers", towers);
            frame.put("snapshot", snapshot);
            handler.broadcast(mapper.writeValueAsString(frame), mapper);
        } catch (Exception e) {
            log.warn("celltower topology push failed: {}", e.getMessage());
        }
    }

    /** 事件去重 key。 */
    private static String eventKey(CellTowerTopologyService.CellTowerEvent e) {
        return e.type().name() + ":" + e.fromSysid() + ":" + e.toSysid();
    }
}