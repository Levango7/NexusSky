package io.aerofleet.cloud.tracking;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.GpsRawInt;
import io.aerofleet.mavlink.messages.SysStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
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

    /** DeviceRegistry 用于获取 DroneSnapshot 的跨消息状态（如 battery）。 */
    @Autowired(required = false)
    private DeviceRegistry deviceRegistry;

    /** per-drone addPoint 计数器，用于节流每架无人机的持久化写入频率。 */
    private final Map<Integer, AtomicInteger> droneAddCounters = new ConcurrentHashMap<>();

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

        // 节流持久化最后已知位置：每 PERSIST_INTERVAL 次 addPoint 写一次数据库（per-drone 计数）
        AtomicInteger counter = droneAddCounters.computeIfAbsent(sysid, k -> new AtomicInteger(0));
        if (counter.incrementAndGet() % PERSIST_INTERVAL == 0) {
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

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 写入轨迹点
    // =====================================================================

    /**
     * 飞行轨迹存储：电量更新时同步写入轨迹点（仅当已有位置时）。
     * <p>
     * 从 TelemetryIngestService.onSysStatus 迁移。SYS_STATUS 含电量但无位置，
     * 需从 DeviceRegistry 获取 DroneSnapshot 的已有位置/速度/航向状态。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.SysStatus).ID")
    public void onSysStatus(MavlinkMessageEvent event) {
        if (deviceRegistry == null) return;
        int sysid = event.getSysid();
        DroneSnapshot s = deviceRegistry.get(sysid);
        if (s == null || Double.isNaN(s.lat) || Double.isNaN(s.lon)) return;
        long now = System.currentTimeMillis();
        addPoint(sysid, new TrackPoint(
                sysid, now, s.lat, s.lon,
                Double.isNaN(s.relativeAlt) ? 0.0 : s.relativeAlt,
                Double.isNaN(s.vx) ? Double.NaN : s.vx,
                Double.isNaN(s.vy) ? Double.NaN : s.vy,
                Double.isNaN(s.vz) ? Double.NaN : s.vz,
                Double.isNaN(s.heading) ? Double.NaN : s.heading,
                s.battery >= 0 ? s.battery : -1.0));
    }

    /**
     * 飞行轨迹存储：GPS 修复时记录轨迹点。
     * <p>
     * 从 TelemetryIngestService.onGps 迁移。GPS_RAW_INT 含 alt 但无 vx/vy，
     * battery 从 DeviceRegistry 获取。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.GpsRawInt).ID")
    public void onGps(MavlinkMessageEvent event) {
        if (deviceRegistry == null) return;
        int sysid = event.getSysid();
        GpsRawInt g = (GpsRawInt) event.getMessage();
        DroneSnapshot s = deviceRegistry.get(sysid);
        if (s == null || !s.gpsHealthy || g.latE7 == 0 || g.lonE7 == 0) return;
        long now = System.currentTimeMillis();
        double lat = g.latE7 / 1e7;
        double lon = g.lonE7 / 1e7;
        double altM = g.altMm / 1000.0;
        addPoint(sysid, new TrackPoint(
                sysid, now, lat, lon, altM,
                Double.NaN, Double.NaN, Double.NaN,
                g.yaw > 0 ? g.yaw / 100.0 : Double.NaN,
                s.battery >= 0 ? s.battery : -1.0));
    }

    /**
     * 飞行轨迹存储：GLOBAL_POSITION_INT 是最完整的遥测源，写入完整轨迹点。
     * <p>
     * 从 TelemetryIngestService.onPosition 迁移。battery 从 DeviceRegistry 获取。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.GlobalPositionInt).ID")
    public void onPosition(MavlinkMessageEvent event) {
        if (deviceRegistry == null) return;
        int sysid = event.getSysid();
        GlobalPositionInt p = (GlobalPositionInt) event.getMessage();
        if (p.latE7 == 0 && p.lonE7 == 0) return;
        DroneSnapshot s = deviceRegistry.get(sysid);
        double battery = (s != null && s.battery >= 0) ? s.battery : -1.0;
        long now = System.currentTimeMillis();
        addPoint(sysid, new TrackPoint(
                sysid, now, p.lat(), p.lon(), p.relativeAltM(),
                p.vx / 100.0, p.vy / 100.0, p.vz / 100.0,
                p.hdg != MavEnums.HDG_UNKNOWN ? p.hdg / 100.0 : Double.NaN,
                battery));
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
