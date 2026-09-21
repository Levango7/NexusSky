package io.aerofleet.cloud.tracking;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 飞行轨迹存储：为每架无人机维护最近 N 条轨迹点。
 * <p>
 * 线程安全实现：外层 {@link ConcurrentHashMap} 按 sysid 分桶，每桶使用
 * {@link ConcurrentLinkedDeque} 支持并发追加与遍历。容量限制通过在追加后
 * 修剪最旧点实现（非严格 N+1 瞬态，但保证最终不超过容量上限）。
 * <p>
 * 默认容量 3600 点，约 3 分钟 @20Hz 遥测速率。
 * <p>
 * 持久化策略：仅将每架无人机的最后已知位置定期写入数据库（通过计数器节流，
 * 每 {@link #PERSIST_INTERVAL} 次 addPoint 写一次），用于重启恢复。
 * 所有读操作仍从内存读取，不受持久化影响。
 * <p>
 * 轨迹点字段：sysid, timestampMs, lat, lon, alt, vx, vy, vz, heading, batteryPct。
 * 字段使用基本类型 double/int 以便 Jackson 直接序列化为 camelCase JSON。
 */
@Component
public class FlightTrackStore {

    private static final Logger log = LoggerFactory.getLogger(FlightTrackStore.class);

    /** 每架无人机保留的轨迹点上限，可通过配置覆盖。 */
    @Value("${aerofleet.tracking.max-points:3600}")
    private int maxPoints = 3600;

    /** sysid -> 轨迹双端队列（最新点在队尾）。 */
    private final Map<Integer, Deque<TrackPoint>> tracks = new ConcurrentHashMap<>();

    /** 最后已知位置持久化 Repository。 */
    @Autowired(required = false)
    private DroneLastKnownPositionRepository repository;

    /** addPoint 计数器，用于节流持久化写入频率。 */
    private final AtomicInteger addCounter = new AtomicInteger(0);

    /** 每 N 次 addPoint 才持久化一次最后已知位置，避免 20Hz 写入压力。 */
    private static final int PERSIST_INTERVAL = 10;

    /**
     * 启动时从数据库加载所有无人机的最后已知位置，作为每架机的第一个轨迹点。
     * 这样重启后前端可立即显示无人机的最后位置，而非空白。
     */
    @PostConstruct
    public void loadLastKnownPositions() {
        if (repository == null) {
            log.info("FlightTrackStore: 纯内存模式（无 JPA repository）");
            return;
        }
        try {
            List<DroneLastKnownPositionEntity> entities = repository.findAll();
            for (DroneLastKnownPositionEntity entity : entities) {
                TrackPoint point = entity.toTrackPoint();
                Deque<TrackPoint> deque = new ConcurrentLinkedDeque<>();
                deque.addLast(point);
                tracks.put(point.sysid, deque);
                log.info("Restored last known position: sysid={} lat={} lon={} alt={}m ts={}",
                        point.sysid, point.lat, point.lon, point.alt, point.timestampMs);
            }
            if (!entities.isEmpty()) {
                log.info("Loaded {} drone last known positions from database", entities.size());
            }
        } catch (Exception e) {
            log.warn("Failed to load last known positions from database: {}", e.getMessage());
        }
    }

    /**
     * 关闭时将所有无人机的最后已知位置写入数据库，确保下次重启可恢复。
     */
    @PreDestroy
    public void persistAllOnShutdown() {
        if (repository == null) return;
        int saved = 0;
        for (Map.Entry<Integer, Deque<TrackPoint>> entry : tracks.entrySet()) {
            TrackPoint last = entry.getValue().peekLast();
            if (last != null) {
                try {
                    DroneLastKnownPositionEntity entity = DroneLastKnownPositionEntity.fromTrackPoint(last);
                    repository.save(entity);
                    saved++;
                } catch (Exception e) {
                    log.warn("Failed to persist last known position for sysid={} on shutdown: {}",
                            entry.getKey(), e.getMessage());
                }
            }
        }
        log.info("Persisted {} drone last known positions on shutdown", saved);
    }

    /** 追加一个轨迹点；超过容量上限时丢弃最旧点。 */
    public void addPoint(int sysid, TrackPoint point) {
        Deque<TrackPoint> deque = tracks.computeIfAbsent(sysid, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(point);
        // 修剪超容量部分：并发场景下可能短暂超过 1 个，最终一致即可
        while (deque.size() > maxPoints) {
            deque.pollFirst();
        }
        log.trace("Track point added: sysid={} size={}", sysid, deque.size());

        // 节流持久化最后已知位置：每 PERSIST_INTERVAL 次 addPoint 写一次数据库
        if (addCounter.incrementAndGet() % PERSIST_INTERVAL == 0) {
            persistLastKnown(sysid, point);
        }
    }

    /**
     * 将指定无人机的最后已知位置写入数据库。
     * 使用 save() 实现 upsert（sysid 为主键，存在则更新）。
     */
    private void persistLastKnown(int sysid, TrackPoint point) {
        if (repository == null) return;
        try {
            DroneLastKnownPositionEntity entity = DroneLastKnownPositionEntity.fromTrackPoint(point);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist last known position for sysid={}: {}", sysid, e.getMessage());
        }
    }

    /**
     * 获取指定无人机的轨迹（最新点在列表末尾），最多返回 limit 条。
     * limit <= 0 时不限制数量（但仍受 maxPoints 上限约束）。
     */
    public List<TrackPoint> getTrack(int sysid, int limit) {
        Deque<TrackPoint> deque = tracks.get(sysid);
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }
        List<TrackPoint> all = new ArrayList<>(deque);
        if (limit <= 0 || limit >= all.size()) {
            return all;
        }
        // 返回最近 limit 条（列表末尾）
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    /** 获取全部轨迹（不限制数量）。 */
    public List<TrackPoint> getTrack(int sysid) {
        return getTrack(sysid, 0);
    }

    /**
     * 按时间范围查询轨迹点（fromMs <= timestampMs <= toMs），按时间升序返回。
     * <p>
     * 约定：
     * <ul>
     *   <li>{@code fromMs <= 0} 表示不限制起始时间</li>
     *   <li>{@code toMs <= 0} 表示不限制结束时间</li>
     *   <li>{@code limit > 0} 时最多返回 limit 条（取时间最近的 limit 条）</li>
     * </ul>
     * <p>
     * 线程安全：遍历 {@link ConcurrentLinkedDeque} 是弱一致的（遍历期间并发追加
     * 不保证可见），对历史回放场景可接受。结果按 {@code timestampMs} 升序排列。
     *
     * @param sysid  无人机 systemId
     * @param fromMs 起始时间戳（epoch ms），<=0 表示不限起始
     * @param toMs   结束时间戳（epoch ms），<=0 表示不限结束
     * @param limit  最多返回 N 条（<=0 表示不限制）
     * @return 轨迹点列表（按时间升序），无匹配时返回空列表
     */
    public List<TrackPoint> getTrack(int sysid, long fromMs, long toMs, int limit) {
        Deque<TrackPoint> deque = tracks.get(sysid);
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }
        List<TrackPoint> filtered = new ArrayList<>();
        for (TrackPoint p : deque) {
            long ts = p.timestampMs;
            if (fromMs > 0 && ts < fromMs) {
                continue;
            }
            if (toMs > 0 && ts > toMs) {
                continue;
            }
            filtered.add(p);
        }
        if (filtered.isEmpty()) {
            return Collections.emptyList();
        }
        // 按 timestampMs 升序排列（addPoint 不强制时间顺序，调用方可能乱序写入）
        filtered.sort((a, b) -> Long.compare(a.timestampMs, b.timestampMs));
        // limit > 0 时截断，取时间最近的 limit 条（列表末尾）
        if (limit > 0 && filtered.size() > limit) {
            return new ArrayList<>(filtered.subList(filtered.size() - limit, filtered.size()));
        }
        return filtered;
    }

    /**
     * 获取最后已知位置（最新轨迹点）；无轨迹时返回 null。
     */
    public TrackPoint getLastKnown(int sysid) {
        Deque<TrackPoint> deque = tracks.get(sysid);
        return deque == null ? null : deque.peekLast();
    }

    /** 清除指定无人机的全部轨迹。 */
    public void clearTrack(int sysid) {
        Deque<TrackPoint> removed = tracks.remove(sysid);
        if (removed != null) {
            log.info("Track cleared: sysid={} points={}", sysid, removed.size());
        }
    }

    /** 清除所有无人机的轨迹。 */
    public void clearAll() {
        int drones = tracks.size();
        tracks.clear();
        log.info("All tracks cleared: drones={}", drones);
    }

    /** 当前已记录轨迹的无人机数量。 */
    public int trackedDroneCount() {
        return tracks.size();
    }

    /** 指定无人机的轨迹点数量。 */
    public int pointCount(int sysid) {
        Deque<TrackPoint> deque = tracks.get(sysid);
        return deque == null ? 0 : deque.size();
    }

    /** 容量上限（用于测试与监控）。 */
    public int getMaxPoints() {
        return maxPoints;
    }

    /** 设置容量上限（主要用于测试注入）。 */
    public void setMaxPoints(int maxPoints) {
        this.maxPoints = maxPoints;
    }

    /**
     * 单个轨迹点：不可变值对象。
     * <p>
     * 字段命名遵循 camelCase JSON 契约，与 gcs-web 前端一致。
     */
    public static final class TrackPoint {
        public final int sysid;
        public final long timestampMs;
        public final double lat;
        public final double lon;
        public final double alt;
        public final double vx;
        public final double vy;
        public final double vz;
        public final double heading;
        public final double batteryPct;

        public TrackPoint(int sysid, long timestampMs,
                          double lat, double lon, double alt,
                          double vx, double vy, double vz,
                          double heading, double batteryPct) {
            this.sysid = sysid;
            this.timestampMs = timestampMs;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.heading = heading;
            this.batteryPct = batteryPct;
        }

        /** 便捷工厂：仅位置+时间，速度/航向/电量置 NaN/-1。 */
        public static TrackPoint of(int sysid, long ts, double lat, double lon, double alt) {
            return new TrackPoint(sysid, ts, lat, lon, alt,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1.0);
        }

        /** 水平速度大小（m/s）；vx/vy 为 NaN 时返回 NaN。 */
        public double groundSpeed() {
            if (Double.isNaN(vx) || Double.isNaN(vy)) {
                return Double.NaN;
            }
            return Math.sqrt(vx * vx + vy * vy);
        }

        @Override
        public String toString() {
            return "TrackPoint{sysid=" + sysid + ", ts=" + timestampMs
                    + ", lat=" + lat + ", lon=" + lon + ", alt=" + alt + "m}";
        }
    }
}
