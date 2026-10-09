package io.aerofleet.cloud.sensing;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 非合作目标态势聚合（E2/E4，spec D4）：多源拉取 → 航迹 upsert → 超时清理 → 近域告警。
 * <p>
 * 告警语义（防御方视角）：任一非合作航迹与**本仓在线设备**距离 < 阈值 → 标记 alert；
 * 每条航迹**首次进入只报一次**（WARN），离开后再进才再报——不重复刷屏。
 */
@Service
public class SensingTrackService {

    private static final Logger log = LoggerFactory.getLogger(SensingTrackService.class);

    private final List<AerialSensingSource> sources;
    private final DeviceRegistry registry;
    private final long trackTtlMs;
    private final double proximityAlertM;

    /** trackId → 航迹（含来源内唯一键：sourceId + ":" + trackId 复合，防跨源撞 id）。 */
    private final Map<String, SensingTrack> tracks = new ConcurrentHashMap<>();
    /** 告警状态：复合键 → 是否已告警（离开清除）。 */
    private final Map<String, Boolean> alertState = new ConcurrentHashMap<>();
    /** 来源累计航迹计数（态势统计）。 */
    private final Map<String, Integer> sourceCounts = new ConcurrentHashMap<>();

    public SensingTrackService(List<AerialSensingSource> sources,
                               DeviceRegistry registry,
                               @Value("${aerofleet.sensing.track-ttl-ms:30000}") long trackTtlMs,
                               @Value("${aerofleet.sensing.proximity-alert-m:1000}") double proximityAlertM) {
        this.sources = sources;
        this.registry = registry;
        this.trackTtlMs = trackTtlMs;
        this.proximityAlertM = proximityAlertM;
        log.info("SensingTrackService: {} source(s), ttl={}ms, proximityAlert={}m",
                sources.size(), trackTtlMs, (int) proximityAlertM);
    }

    /** 周期聚合（默认 5s，与源产出节奏匹配）。 */
    @Scheduled(fixedDelayString = "${aerofleet.sensing.aggregate-ms:5000}")
    public void aggregate() {
        try {
            for (AerialSensingSource source : sources) {
                List<SensingTrack> batch = source.poll();
                for (SensingTrack t : batch) {
                    String key = source.sourceId() + ":" + t.trackId();
                    SensingTrack upsert;
                    SensingTrack existing = tracks.get(key);
                    upsert = existing == null ? t
                            : existing.seenAgain(t.lastSeenMs(), t.lat(), t.lon(),
                            t.altM(), t.speedMps(), t.headingDeg());
                    tracks.put(key, upsert);
                    sourceCounts.merge(source.sourceId(), 1, Integer::sum);
                }
            }
            evictStale();
            evaluateProximity();
        } catch (Exception e) {
            // 聚合器故障不拖垮感知源（与既有旁路纪律一致）
            log.warn("sensing aggregate failed (ignored): {}", e.getMessage());
        }
    }

    /** 超时清理（TTL 未更新的航迹移除，告警状态一并清除）。 */
    void evictStale() {
        long cutoff = System.currentTimeMillis() - trackTtlMs;
        tracks.entrySet().removeIf(e -> {
            if (e.getValue().lastSeenMs() < cutoff) {
                alertState.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    /** 近域告警（进入/离开/再进入三段语义）。 */
    void evaluateProximity() {
        for (Map.Entry<String, SensingTrack> e : tracks.entrySet()) {
            SensingTrack t = e.getValue();
            double nearest = nearestFleetDistanceM(t);
            boolean inProximity = nearest < proximityAlertM;
            Boolean alerted = alertState.get(e.getKey());
            if (inProximity && !Boolean.TRUE.equals(alerted)) {
                alertState.put(e.getKey(), true);
                log.warn("非合作目标近域告警: {} ({}) 距我方最近设备 {}m < {}m — classification={}",
                        t.trackId(), t.sourceType(), Math.round(nearest),
                        (int) proximityAlertM, t.classification());
            } else if (!inProximity && Boolean.TRUE.equals(alerted)) {
                alertState.remove(e.getKey());   // 离开清除——再进入会重新告警
            }
        }
    }

    /** 航迹到最近在线设备的距离（无在线设备 → MAX_VALUE）。 */
    double nearestFleetDistanceM(SensingTrack t) {
        double min = Double.MAX_VALUE;
        for (DroneSnapshot s : registry.all()) {
            if (!s.online || Double.isNaN(s.lat) || Double.isNaN(s.lon)) {
                continue;
            }
            min = Math.min(min, FiveGSensingSource.UspaceGeometry.haversineM(
                    t.lat(), t.lon(), s.lat, s.lon));
        }
        return min;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 航迹视图（source 过滤 / alertOnly）。 */
    public List<Map<String, Object>> tracks(String sourceType, boolean alertOnly) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, SensingTrack> e : tracks.entrySet()) {
            SensingTrack t = e.getValue();
            boolean alert = Boolean.TRUE.equals(alertState.get(e.getKey()));
            if (sourceType != null && !sourceType.isBlank() && !sourceType.equals(t.sourceType())) {
                continue;
            }
            if (alertOnly && !alert) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trackKey", e.getKey());
            m.put("trackId", t.trackId());
            m.put("sourceType", t.sourceType());
            m.put("sourceId", t.sourceId());
            m.put("lat", t.lat());
            m.put("lon", t.lon());
            m.put("altM", Double.isNaN(t.altM()) ? null : t.altM());
            m.put("speedMps", Double.isNaN(t.speedMps()) ? null : t.speedMps());
            m.put("headingDeg", Double.isNaN(t.headingDeg()) ? null : t.headingDeg());
            m.put("confidence", t.confidence());
            m.put("classification", t.classification());
            m.put("firstSeenMs", t.firstSeenMs());
            m.put("lastSeenMs", t.lastSeenMs());
            m.put("alert", alert);
            m.put("nearestFleetM", Double.MAX_VALUE == nearestFleetDistanceM(t)
                    ? null : Math.round(nearestFleetDistanceM(t)));
            out.add(m);
        }
        return out;
    }

    /** 来源清单（类型/标识/累计航迹数）。 */
    public List<Map<String, Object>> sources() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AerialSensingSource s : sources) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceType", s.sourceType());
            m.put("sourceId", s.sourceId());
            m.put("totalTracks", sourceCounts.getOrDefault(s.sourceId(), 0));
            out.add(m);
        }
        return out;
    }

    /** 告警数（面板态势卡）。 */
    public long alertCount() {
        return alertState.values().stream().filter(Boolean::booleanValue).count();
    }
}
