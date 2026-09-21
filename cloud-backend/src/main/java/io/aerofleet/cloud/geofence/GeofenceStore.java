package io.aerofleet.cloud.geofence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 * 混合模式：内存缓存保证并发读性能 + JPA 持久化保证重启恢复。
 * 所有读操作从内存缓存读取，写操作同时更新内存和数据库。
 * <p>
 * 经验参考：运行时并发对象与 JPA Entity 分离，内存缓存保持现有并发性能，
 * Entity 仅用于持久化。写操作标注 {@code @Transactional} 保证一致性。
 * 来源：2026-09-21-concurrent-runtime-object-jpa-entity-separation
 */
@Component
public class GeofenceStore {

    private static final Logger log = LoggerFactory.getLogger(GeofenceStore.class);

    /** 越界历史最大保留条数。 */
    static final int MAX_BREACH_HISTORY = 1000;

    /** zoneId -> 围栏区域（内存缓存，并发读）。 */
    private final Map<Integer, GeofenceZone> zones = new ConcurrentHashMap<>();

    /** 越界事件历史（最新在队尾，内存缓存）。 */
    private final ConcurrentLinkedDeque<GeofenceBreachEvent> breachHistory = new ConcurrentLinkedDeque<>();

    @Autowired(required = false)
    private GeofenceZoneRepository zoneRepository;

    @Autowired(required = false)
    private GeofenceBreachEventRepository breachEventRepository;

    // ------------------------------------------------------------------
    // 启动恢复
    // ------------------------------------------------------------------

    /**
     * 启动时从数据库加载围栏区域和越界事件到内存缓存。
     * <p>
     * 不标注 {@code @Transactional}：Bean 初始化阶段事务可能未完全就绪，
     * 且此处仅做读取操作。
     */
    @PostConstruct
    public void loadFromDb() {
        if (zoneRepository == null && breachEventRepository == null) {
            log.info("GeofenceStore: 纯内存模式（无 JPA repository）");
            return;
        }
        // 加载围栏区域
        if (zoneRepository != null) {
            try {
                List<GeofenceZoneEntity> zoneEntities = zoneRepository.findAll();
                for (GeofenceZoneEntity entity : zoneEntities) {
                    try {
                        zones.put(entity.getId(), entity.toZone());
                    } catch (Exception e) {
                        log.warn("Failed to load geofence zone from DB: id={} err={}",
                                entity.getId(), e.getMessage());
                    }
                }
                log.info("Loaded {} geofence zones from database", zones.size());
            } catch (Exception e) {
                log.warn("Failed to load geofence zones from database: {}", e.getMessage());
            }
        }

        // 加载越界事件（按时间升序，最多 MAX_BREACH_HISTORY 条）
        if (breachEventRepository != null) {
            try {
                List<GeofenceBreachEventEntity> breachEntities = breachEventRepository.findAll();
                breachEntities.sort((a, b) -> Long.compare(a.getTimestampMs(), b.getTimestampMs()));
                int loaded = 0;
                for (GeofenceBreachEventEntity entity : breachEntities) {
                    try {
                        breachHistory.addLast(entity.toEvent());
                        loaded++;
                    } catch (Exception e) {
                        log.warn("Failed to load breach event from DB: id={} err={}",
                                entity.getId(), e.getMessage());
                    }
                }
                // 超过上限时丢弃最旧记录
                while (breachHistory.size() > MAX_BREACH_HISTORY) {
                    breachHistory.pollFirst();
                }
                log.info("Loaded {} geofence breach events from database", loaded);
            } catch (Exception e) {
                log.warn("Failed to load breach events from database: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // 围栏区域 CRUD
    // ------------------------------------------------------------------

    /** 新增围栏区域；若 zoneId 已存在则覆盖并返回旧值。 */
    @Transactional
    public GeofenceZone addZone(GeofenceZone zone) {
        GeofenceZone previous = zones.put(zone.getId(), zone);
        if (zoneRepository != null) {
            try {
                zoneRepository.save(GeofenceZoneEntity.fromZone(zone));
            } catch (Exception e) {
                log.warn("Failed to persist geofence zone: id={} err={}", zone.getId(), e.getMessage());
            }
        }
        if (previous != null) {
            log.info("Geofence zone replaced: id={} name='{}'", zone.getId(), zone.getName());
        } else {
            log.info("Geofence zone added: id={} name='{}' type={}",
                    zone.getId(), zone.getName(), zone.getType());
        }
        return previous;
    }

    /** 更新围栏区域；若不存在返回 null（不新增）。 */
    @Transactional
    public GeofenceZone updateZone(GeofenceZone zone) {
        if (!zones.containsKey(zone.getId())) {
            return null;
        }
        zones.put(zone.getId(), zone);
        if (zoneRepository != null) {
            try {
                zoneRepository.save(GeofenceZoneEntity.fromZone(zone));
            } catch (Exception e) {
                log.warn("Failed to persist geofence zone update: id={} err={}", zone.getId(), e.getMessage());
            }
        }
        log.info("Geofence zone updated: id={} name='{}'", zone.getId(), zone.getName());
        return zone;
    }

    /** 删除围栏区域；返回被删除的围栏，不存在返回 null。 */
    @Transactional
    public GeofenceZone removeZone(int zoneId) {
        GeofenceZone removed = zones.remove(zoneId);
        if (removed != null) {
            if (zoneRepository != null) {
                try {
                    zoneRepository.deleteById(zoneId);
                } catch (Exception e) {
                    log.warn("Failed to delete geofence zone from DB: id={} err={}", zoneId, e.getMessage());
                }
            }
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
    @Transactional
    public void recordBreach(GeofenceBreachEvent event) {
        breachHistory.addLast(event);
        while (breachHistory.size() > MAX_BREACH_HISTORY) {
            breachHistory.pollFirst();
        }
        if (breachEventRepository != null) {
            try {
                breachEventRepository.save(GeofenceBreachEventEntity.fromEvent(event));
            } catch (Exception e) {
                log.warn("Failed to persist breach event: sysid={} err={}", event.getSysid(), e.getMessage());
            }
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
    @Transactional
    public void clearBreachHistory() {
        int n = breachHistory.size();
        breachHistory.clear();
        if (breachEventRepository != null) {
            try {
                breachEventRepository.deleteAll();
            } catch (Exception e) {
                log.warn("Failed to clear breach events from DB: {}", e.getMessage());
            }
        }
        log.info("Geofence breach history cleared: count={}", n);
    }
}
