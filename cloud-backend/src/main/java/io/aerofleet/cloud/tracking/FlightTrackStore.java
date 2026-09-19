package io.aerofleet.cloud.tracking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 飞行轨迹存储：为每架无人机维护最近 N 条轨迹点。
 * <p>
 * 线程安全实现：外层 {@link ConcurrentHashMap} 按 sysid 分桶，每桶使用
 * {@link ConcurrentLinkedDeque} 支持并发追加与遍历。容量限制通过在追加后
 * 修剪最旧点实现（非严格 N+1 瞬态，但保证最终不超过容量上限）。
 * <p>
 * 默认容量 3600 点，约 3 分钟 @20Hz 遥测速率。
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

    /** 追加一个轨迹点；超过容量上限时丢弃最旧点。 */
    public void addPoint(int sysid, TrackPoint point) {
        Deque<TrackPoint> deque = tracks.computeIfAbsent(sysid, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(point);
        // 修剪超容量部分：并发场景下可能短暂超过 1 个，最终一致即可
        while (deque.size() > maxPoints) {
            deque.pollFirst();
        }
        log.trace("Track point added: sysid={} size={}", sysid, deque.size());
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