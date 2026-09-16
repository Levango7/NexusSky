package io.aerofleet.cloud.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地形变更 WebSocket 推送（M8 复杂地形适配，FR-32）。
 * <p>
 * 2Hz @Scheduled 检测地形版本变化，1 秒 debounce 抑制事件风暴，
 * 复用 {@link TelemetryWebSocketHandler#broadcast}（既有不变）。
 * <p>
 * 推送格式：
 * <pre>
 * {"type":"terrain-update","version":N,"changes":[...],"snapshot":{...}}
 * </pre>
 */
@Component
public class TerrainPusher {

    private static final Logger log = LoggerFactory.getLogger(TerrainPusher.class);

    private final TelemetryWebSocketHandler handler;
    private final TerrainMapService mapService;
    private final ObjectMapper mapper;

    /** 上一轮版本号（变化检测用）。 */
    private long lastVersion = 0;
    /** debounce 阈值（ms）。 */
    private static final long DEBOUNCE_MS = 1000;
    /** 上次推送时间。 */
    private long lastPushMs = 0;

    public TerrainPusher(TelemetryWebSocketHandler handler,
                         TerrainMapService mapService,
                         ObjectMapper mapper) {
        this.handler = handler;
        this.mapService = mapService;
        this.mapper = mapper;
    }

    /**
     * 2Hz 推送检测（FR-32）：检测地形版本变化，若有变化则广播 terrain-update 事件。
     * 连接数为 0 时跳过。
     */
    @Scheduled(fixedDelay = 500)
    public void pushOnce() {
        if (handler.connectionCount() == 0) {
            return;
        }
        long currentVersion = mapService.currentVersion();
        if (currentVersion <= lastVersion) {
            return;
        }
        // debounce：1 秒内只推送一次
        long now = System.currentTimeMillis();
        if (now - lastPushMs < DEBOUNCE_MS) {
            return;
        }
        try {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", "terrain-update");
            frame.put("version", currentVersion);
            // 附带最近变更
            List<TerrainMapService.TerrainChangeRecord> recent = mapService.getChangeHistory(0, 10);
            List<Map<String, Object>> changes = new java.util.ArrayList<>();
            for (TerrainMapService.TerrainChangeRecord r : recent) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("terrainVersion", r.terrainVersion());
                item.put("changeReason", r.changeReason());
                item.put("affectedCount", r.affectedCells().size());
                item.put("timestamp", r.timestamp());
                changes.add(item);
            }
            frame.put("changes", changes);
            // 附带当前快照
            TerrainMapService.TerrainMapSnapshot snapshot = mapService.getCurrentMap();
            if (snapshot != null) {
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("version", snapshot.version());
                snap.put("mapWidth", snapshot.mapWidth());
                snap.put("mapHeight", snapshot.mapHeight());
                snap.put("gridResolution", snapshot.gridResolution());
                snap.put("originLat", snapshot.originLat());
                snap.put("originLon", snapshot.originLon());
                snap.put("timestamp", snapshot.timestamp());
                frame.put("snapshot", snap);
            }
            handler.broadcast(mapper.writeValueAsString(frame), mapper);
            lastVersion = currentVersion;
            lastPushMs = now;
        } catch (Exception e) {
            log.warn("terrain push failed: {}", e.getMessage());
        }
    }
}