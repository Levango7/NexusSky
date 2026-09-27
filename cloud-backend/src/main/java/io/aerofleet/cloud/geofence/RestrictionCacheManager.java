package io.aerofleet.cloud.geofence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 限飞区缓存管理器：负责从 {@link RestrictionDataSource} 拉取限飞区数据并缓存。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>启动时根据 {@link RestrictionSourceConfig#getSourceType()} 创建对应数据源实例并触发首次加载</li>
 *   <li>定期刷新缓存（{@code @Scheduled}），刷新失败时保留旧缓存并标记 stale</li>
 *   <li>提供线程安全的缓存读取接口</li>
 *   <li>支持手动刷新（{@link #refresh()}）和缓存状态查询（{@link #getStatus()}）</li>
 * </ul>
 * <p>
 * 线程安全：缓存刷新通过 {@link ReentrantLock} 串行化，读取通过 volatile 字段保证可见性。
 */
@Component
public class RestrictionCacheManager {

    private static final Logger log = LoggerFactory.getLogger(RestrictionCacheManager.class);

    private final RestrictionSourceConfig config;

    /** 可选注入的本地文件数据源（仅 LOCAL_FILE 模式使用）。 */
    @Autowired(required = false)
    private LocalFileRestrictionSource localFileSource;

    /** 当前数据源实例。 */
    private RestrictionDataSource dataSource;

    /** 限飞区缓存：zoneId → RestrictionZone。 */
    private final Map<String, RestrictionZone> cache = new LinkedHashMap<>();

    /** 最后一次成功拉取时间戳（毫秒）。 */
    private volatile long lastFetchMs = 0;

    /** 缓存是否已过期或从未成功加载。 */
    private volatile boolean stale = true;

    /** 刷新锁，防止并发刷新。 */
    private final ReentrantLock refreshLock = new ReentrantLock();

    public RestrictionCacheManager(RestrictionSourceConfig config) {
        this.config = config;
    }

    /**
     * 启动时根据 sourceType 创建对应数据源实例并触发首次加载。
     * <p>
     * 首次加载失败 → 空缓存 + stale=true + 告警日志。
     */
    @PostConstruct
    public void init() {
        if (!config.isEnabled()) {
            log.info("Restriction cache disabled by configuration (aerofleet.geofence.restriction.enabled=false)");
            return;
        }

        // 根据 sourceType 创建对应数据源实例（若 dataSource 已被外部注入则不覆盖）
        if (dataSource == null) {
            switch (config.getSourceType()) {
                case MOCK:
                    dataSource = new MockRestrictionSource();
                    break;
                case LOCAL_FILE:
                    if (localFileSource != null) {
                        dataSource = localFileSource;
                    } else {
                        log.warn("LOCAL_FILE restriction source requested but LocalFileRestrictionSource bean not available, falling back to MOCK");
                        dataSource = new MockRestrictionSource();
                    }
                    break;
                case HTTP:
                    log.warn("HTTP restriction source not yet implemented, falling back to MOCK");
                    dataSource = new MockRestrictionSource();
                    break;
                default:
                    log.warn("Unknown restriction source type: {}, falling back to MOCK", config.getSourceType());
                    dataSource = new MockRestrictionSource();
                    break;
            }
        }

        log.info("Restriction cache initializing with source type: {}, sourceId: {}",
                config.getSourceType(), dataSource.getSourceId());

        // 触发首次加载
        refresh();
    }

    /**
     * 定期刷新缓存。
     * <p>
     * 刷新间隔由 {@code aerofleet.geofence.restriction.refresh-interval-ms} 配置，默认 24 小时。
     */
    @Scheduled(fixedRateString = "${aerofleet.geofence.restriction.refresh-interval-ms:86400000}")
    public void scheduledRefresh() {
        if (!config.isEnabled()) {
            return;
        }
        refresh();
    }

    /**
     * 手动刷新缓存（加锁防并发）。
     * <p>
     * 成功 → 更新缓存 + 重置 stale；失败 → 保留旧缓存 + 告警。
     */
    public void refresh() {
        if (dataSource == null) {
            log.warn("Restriction cache refresh skipped: no data source initialized");
            return;
        }

        refreshLock.lock();
        try {
            List<RestrictionZone> zones = dataSource.fetch();
            Map<String, RestrictionZone> newCache = new LinkedHashMap<>();
            for (RestrictionZone zone : zones) {
                newCache.put(zone.getZoneId(), zone);
            }
            cache.clear();
            cache.putAll(newCache);
            lastFetchMs = System.currentTimeMillis();
            stale = false;
            log.info("Restriction cache refreshed: {} zones from source '{}'", zones.size(), dataSource.getSourceId());
        } catch (Exception e) {
            log.warn("Failed to refresh restriction cache from source '{}': {}", dataSource.getSourceId(), e.getMessage());
            // 保留旧缓存，标记为 stale
            stale = true;
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 获取缓存的限飞区列表（可能 stale）。
     *
     * @return 限飞区列表（可能为空，但不应为 null）
     */
    public List<RestrictionZone> getRestrictionZones() {
        refreshLock.lock();
        try {
            return new ArrayList<>(cache.values());
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 判断缓存是否已过期（超过 TTL 或从未成功加载）。
     *
     * @return true 表示缓存已过期或从未成功加载
     */
    public boolean isStale() {
        if (stale) {
            return true;
        }
        long age = System.currentTimeMillis() - lastFetchMs;
        return age > config.getCacheTtlMs();
    }

    /**
     * 获取缓存状态信息。
     *
     * @return 包含缓存条数、最后刷新时间、数据源类型、是否 stale 的 Map
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("zoneCount", cache.size());
        status.put("lastFetchMs", lastFetchMs);
        status.put("sourceType", config.getSourceType() != null ? config.getSourceType().name() : "UNKNOWN");
        status.put("sourceId", dataSource != null ? dataSource.getSourceId() : "none");
        status.put("stale", isStale());
        status.put("enabled", config.isEnabled());
        return status;
    }
}