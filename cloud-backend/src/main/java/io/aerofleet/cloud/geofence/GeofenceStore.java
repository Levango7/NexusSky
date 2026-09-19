package io.aerofleet.cloud.geofence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 电子围栏存储：围栏区域 CRUD + 越界事件历史。
 * <p>
 * 线程安全实现：围栏使用 {@link ConcurrentHashMap} 按 zoneId 分桶，
 * 越界历史使用 {@link ConcurrentLinkedDeque} 支持并发追加与遍历。
 * 越界历史上限 {@value #MAX_BREACH_HISTORY} 条，超过时丢弃最旧记录。
 * <p>
 * 纯内存存储，重启后状态丢失（与 {@code FlightTrackStore} 风格一致）。
 */
@Component
public class GeofenceStore {

    private static final Logger log = LoggerFactory.getLogger(GeofenceStore.class);

    /** 越界历史最大保留条数。 */
    static final int MAX_BREACH_HISTORY = 1000;

    /** zoneId -> 围栏区域。 */
    private final Map<Integer, GeofenceZone> zones = new ConcurrentHashMap<>();

    /** 越界事件历史（最新在队尾）。 */
    private final ConcurrentLinkedDeque<GeofenceBreachEvent> breachHistory = new ConcurrentLinkedDeque<>();

    // ------------------------------------------------------------------
    // 围栏区域 CRUD
    // ------------------------------------------------------------------

    /** 新增围栏区域；若 zoneId 已存在则覆盖并返回旧值。 */
    public GeofenceZone addZone(GeofenceZone zone) {
        GeofenceZone previous = zones.put(zone.getId(), zone);
        if (previous != null) {
            log.info("Geofence zone replaced: id={} name='{}'", zone.getId(), zone.getName());
        } else {
            log.info("Geofence zone added: id={} name='{}' type={}",
                    zone.getId(), zone.getName(), zone.getType());
        }
        return previous;
    }

    /** 更新围栏区域；若不存在返回 null（不新增）。 */
    public GeofenceZone updateZone(GeofenceZone zone) {
        if (!zones.containsKey(zone.getId())) {
            return null;
        }
        zones.put(zone.getId(), zone);
        log.info("Geofence zone updated: id={} name='{}'", zone.getId(), zone.getName());
        return zone;
    }

    /** 删除围栏区域；返回被删除的围栏，不存在返回 null。 */
    public GeofenceZone removeZone(int zoneId) {
        GeofenceZone removed = zones.remove(zoneId);
        if (removed != null) {
            log.info("Geofence zone removed: id={} name='{}'", zoneId, removed.getName());
        }
        return removed;
    }

    /** 获取指定围栏；不存在返回 null。 */
    public GeofenceZone getZone(int zoneId) {
        return zones.get(zoneId);
    }

    /** 列出所有围栏（按 zoneId 升序）。 */
    public List<GeofenceZone> getAllZones() {
        List<GeofenceZone> list = new ArrayList<>(zones.values());
        list.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        return list;
    }

    /** 当前围栏数量。 */
    public int zoneCount() {
        return zones.size();
    }

    // ------------------------------------------------------------------
    // 越界事件历史
    // ------------------------------------------------------------------

    /** 记入一条越界事件；超过上限时丢弃最旧记录。 */
    public void recordBreach(GeofenceBreachEvent event) {
        breachHistory.addLast(event);
        while (breachHistory.size() > MAX_BREACH_HISTORY) {
            breachHistory.pollFirst();
        }
        log.warn("Geofence breach: sysid={} zone={} type={} lat={} lon={}",
                event.getSysid(), event.getZoneName(),
                event.getBreachType(), event.getLat(), event.getLon());
    }

    /** 获取全部越界历史（按时间升序，最新在末尾）。 */
    public List<GeofenceBreachEvent> getBreachHistory() {
        return new ArrayList<>(breachHistory);
    }

    /** 获取指定无人机的越界历史。 */
    public List<GeofenceBreachEvent> getBreachesForDrone(int sysid) {
        List<GeofenceBreachEvent> result = new ArrayList<>();
        for (GeofenceBreachEvent e : breachHistory) {
            if (e.getSysid() == sysid) {
                result.add(e);
            }
        }
        return result;
    }

    /** 获取指定围栏的越界历史。 */
    public List<GeofenceBreachEvent> getBreachesForZone(int zoneId) {
        List<GeofenceBreachEvent> result = new ArrayList<>();
        for (GeofenceBreachEvent e : breachHistory) {
            if (e.getZoneId() == zoneId) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * 按时间范围查询越界历史（闭区间 [fromMs, toMs]）。
     *
     * @param fromMs 起始时间戳（epoch ms），<=0 表示不限制下界
     * @param toMs   结束时间戳（epoch ms），<=0 表示不限制上界
     */
    public List<GeofenceBreachEvent> getBreachesInRange(long fromMs, long toMs) {
        List<GeofenceBreachEvent> result = new ArrayList<>();
        for (GeofenceBreachEvent e : breachHistory) {
            long ts = e.getTimestampMs();
            if (fromMs > 0 && ts < fromMs) {
                continue;
            }
            if (toMs > 0 && ts > toMs) {
                continue;
            }
            result.add(e);
        }
        return result;
    }

    /** 越界历史记录数量。 */
    public int breachCount() {
        return breachHistory.size();
    }

    /** 清空越界历史（用于测试与管理）。 */
    public void clearBreachHistory() {
        int n = breachHistory.size();
        breachHistory.clear();
        log.info("Geofence breach history cleared: count={}", n);
    }
}