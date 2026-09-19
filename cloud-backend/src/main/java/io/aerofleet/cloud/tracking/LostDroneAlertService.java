package io.aerofleet.cloud.tracking;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 失联告警 + 辅助查找服务。
 * <p>
 * 监听 {@link DeviceRegistry#sweepOffline()} 返回的心跳超时 sysid，
 * 为每架失联无人机生成 {@link LostAlert}，并提供辅助查找信息
 * {@link SearchGuide}：
 * <ul>
 *   <li>最后已知位置（轨迹末点或快照位置）</li>
 *   <li>最后轨迹方向（航向 + 水平速度）</li>
 *   <li>电池最后电量</li>
 *   <li>预计坠落范围（根据最后高度 + 风速估算）</li>
 * </ul>
 * <p>
 * 坠落范围估算采用简化运动学模型：
 * <pre>
 *   t_fall = sqrt(2 * alt / g)                    // 自由落体时间
 *   drift = windSpeed * t_fall                    // 风偏距离
 *   radius = max(drift, minRadius)                // 搜索半径
 * </pre>
 * 其中 g=9.81 m/s²，minRadius 默认 50m 兜底。
 */
@Service
public class LostDroneAlertService {

    private static final Logger log = LoggerFactory.getLogger(LostDroneAlertService.class);

    private static final double GRAVITY = 9.81; // m/s^2
    private static final double EARTH_RADIUS_M = 6_371_000.0; // WGS84 平均半径

    private final DeviceRegistry registry;
    private final FlightTrackStore trackStore;

    /** 最小搜索半径（m），兜底防止 0 半径。 */
    @Value("${aerofleet.tracking.search.min-radius:50.0}")
    private double minSearchRadius;

    /** 默认风速（m/s），当无环境风速数据时使用。 */
    @Value("${aerofleet.tracking.search.default-wind-speed:5.0}")
    private double defaultWindSpeed;

    /** 已生成的失联告警：sysid -> LostAlert。 */
    private final Map<Integer, LostAlert> lostAlerts = new ConcurrentHashMap<>();

    public LostDroneAlertService(DeviceRegistry registry, FlightTrackStore trackStore) {
        this.registry = registry;
        this.trackStore = trackStore;
    }

    /**
     * 扫描所有无人机，对心跳超时的设备生成失联告警。
     * <p>
     * 调用 {@link DeviceRegistry#sweepOffline()} 触发离线检测，
     * 并为每个新失联的 sysid 生成告警记录。
     *
     * @return 本次扫描中新失联的 sysid 列表
     */
    public List<Integer> checkLostDrones() {
        List<Integer> newlyOffline = registry.sweepOffline();
        for (Integer sysid : newlyOffline) {
            if (!lostAlerts.containsKey(sysid)) {
                LostAlert alert = buildAlert(sysid);
                lostAlerts.put(sysid, alert);
                log.warn("Lost drone alert: sysid={} lastKnown={} batteryPct={}%",
                        sysid, alert.lastKnownPos, alert.batteryPct);
            }
        }
        return newlyOffline;
    }

    /** 获取所有失联告警列表（按 sysid 升序）。 */
    public List<LostAlert> getLostAlerts() {
        List<LostAlert> list = new ArrayList<>(lostAlerts.values());
        list.sort((a, b) -> Integer.compare(a.sysid, b.sysid));
        return list;
    }

    /** 指定 sysid 是否处于失联状态。 */
    public boolean isLost(int sysid) {
        return lostAlerts.containsKey(sysid);
    }

    /** 清除指定无人机的失联告警（恢复在线后调用）。 */
    public void clearAlert(int sysid) {
        LostAlert removed = lostAlerts.remove(sysid);
        if (removed != null) {
            log.info("Lost alert cleared: sysid={}", sysid);
        }
    }

    /** 清除所有失联告警。 */
    public void clearAllAlerts() {
        int n = lostAlerts.size();
        lostAlerts.clear();
        log.info("All lost alerts cleared: count={}", n);
    }

    /**
     * 获取辅助查找信息：最后已知位置 + 最后轨迹方向 + 电池电量 + 预计坠落范围。
     * <p>
     * 若无人机未在失联告警列表中，仍返回当前可计算的查找信息（便于预防性查询）。
     *
     * @param sysid 无人机 systemId
     * @return 查找信息；若无人机未注册返回 null
     */
    public SearchGuide getSearchGuide(int sysid) {
        DroneSnapshot snapshot = registry.get(sysid);
        if (snapshot == null) {
            return null;
        }
        FlightTrackStore.TrackPoint lastTrack = trackStore.getLastKnown(sysid);
        return buildSearchGuide(sysid, snapshot, lastTrack);
    }

    // ------------------------------------------------------------------
    // 内部构建方法
    // ------------------------------------------------------------------

    private LostAlert buildAlert(int sysid) {
        DroneSnapshot snapshot = registry.get(sysid);
        FlightTrackStore.TrackPoint lastTrack = trackStore.getLastKnown(sysid);

        LastKnownPos pos = extractLastKnown(sysid, snapshot, lastTrack);
        double batteryPct = extractBatteryPct(snapshot, lastTrack);
        double heading = extractHeading(snapshot, lastTrack);
        double groundSpeed = extractGroundSpeed(snapshot, lastTrack);
        long lastContactMs = snapshot != null ? snapshot.lastHeartbeatMs : 0L;

        return new LostAlert(sysid, lastContactMs, System.currentTimeMillis(),
                pos, heading, groundSpeed, batteryPct);
    }

    private SearchGuide buildSearchGuide(int sysid, DroneSnapshot snapshot,
                                         FlightTrackStore.TrackPoint lastTrack) {
        LastKnownPos pos = extractLastKnown(sysid, snapshot, lastTrack);
        double batteryPct = extractBatteryPct(snapshot, lastTrack);
        double heading = extractHeading(snapshot, lastTrack);
        double groundSpeed = extractGroundSpeed(snapshot, lastTrack);
        double lastAlt = pos != null ? pos.alt : 0.0;
        double windSpeed = extractWindSpeed(snapshot);
        long lastContactMs = snapshot.lastHeartbeatMs;

        FallEstimate fall = estimateFallRange(lastAlt, windSpeed, heading, groundSpeed,
                pos != null ? pos.lat : Double.NaN,
                pos != null ? pos.lon : Double.NaN);

        return new SearchGuide(sysid, lastContactMs, pos, heading, groundSpeed,
                batteryPct, windSpeed, fall);
    }

    /** 提取最后已知位置：优先使用轨迹末点（更精确），降级使用快照位置。 */
    private LastKnownPos extractLastKnown(int sysid, DroneSnapshot snapshot,
                                          FlightTrackStore.TrackPoint lastTrack) {
        if (lastTrack != null && !Double.isNaN(lastTrack.lat) && !Double.isNaN(lastTrack.lon)) {
            return new LastKnownPos(lastTrack.lat, lastTrack.lon, lastTrack.alt,
                    lastTrack.timestampMs, "track");
        }
        if (snapshot != null && !Double.isNaN(snapshot.lat) && !Double.isNaN(snapshot.lon)) {
            return new LastKnownPos(snapshot.lat, snapshot.lon,
                    Double.isNaN(snapshot.relativeAlt) ? 0.0 : snapshot.relativeAlt,
                    snapshot.lastHeartbeatMs, "snapshot");
        }
        return null;
    }

    /** 提取电池电量：优先快照，降级轨迹点。 */
    private double extractBatteryPct(DroneSnapshot snapshot,
                                     FlightTrackStore.TrackPoint lastTrack) {
        if (snapshot != null && snapshot.battery >= 0) {
            return snapshot.battery;
        }
        if (lastTrack != null && lastTrack.batteryPct >= 0) {
            return lastTrack.batteryPct;
        }
        return -1.0;
    }

    /** 提取航向（deg）：优先轨迹点，降级快照。 */
    private double extractHeading(DroneSnapshot snapshot,
                                  FlightTrackStore.TrackPoint lastTrack) {
        if (lastTrack != null && !Double.isNaN(lastTrack.heading)) {
            return lastTrack.heading;
        }
        if (snapshot != null && !Double.isNaN(snapshot.heading)) {
            return snapshot.heading;
        }
        return Double.NaN;
    }

    /** 提取水平速度（m/s）：优先轨迹点，降级快照。 */
    private double extractGroundSpeed(DroneSnapshot snapshot,
                                      FlightTrackStore.TrackPoint lastTrack) {
        if (lastTrack != null) {
            double gs = lastTrack.groundSpeed();
            if (!Double.isNaN(gs)) {
                return gs;
            }
        }
        if (snapshot != null) {
            if (!Double.isNaN(snapshot.groundspeed)) {
                return snapshot.groundspeed;
            }
            if (!Double.isNaN(snapshot.vx) && !Double.isNaN(snapshot.vy)) {
                return Math.sqrt(snapshot.vx * snapshot.vx + snapshot.vy * snapshot.vy);
            }
        }
        return Double.NaN;
    }

    /** 提取风速（m/s）：使用快照中的环境风速，降级默认值。 */
    private double extractWindSpeed(DroneSnapshot snapshot) {
        if (snapshot != null && !Double.isNaN(snapshot.envWindSpeed)) {
            return snapshot.envWindSpeed;
        }
        return defaultWindSpeed;
    }

    /**
     * 估算坠落范围：基于最后高度 + 风速 + 水平速度的简化运动学模型。
     * <p>
     * 自由落体时间 t = sqrt(2h/g)；风偏距离 = windSpeed * t；
     * 水平惯性距离 = groundSpeed * t；搜索半径 = max(风偏 + 惯性, minRadius)。
     * 同时计算预测落点经纬度（基于最后位置 + 航向 + 偏移距离）。
     */
    private FallEstimate estimateFallRange(double alt, double windSpeed,
                                           double heading, double groundSpeed,
                                           double lat, double lon) {
        double safeAlt = Math.max(0.0, alt);
        double fallTimeSec = safeAlt > 0 ? Math.sqrt(2.0 * safeAlt / GRAVITY) : 0.0;
        double windDrift = Math.max(0.0, windSpeed) * fallTimeSec;
        double inertiaDrift = Double.isNaN(groundSpeed) ? 0.0 : Math.max(0.0, groundSpeed) * fallTimeSec;
        double rawRadius = windDrift + inertiaDrift;
        double searchRadius = Math.max(rawRadius, minSearchRadius);

        // 预测落点：沿航向偏移（风偏 + 惯性）/ 2 作为估计中点
        double predictedLat = lat;
        double predictedLon = lon;
        if (!Double.isNaN(lat) && !Double.isNaN(lon) && !Double.isNaN(heading)
                && rawRadius > 0) {
            double offsetM = rawRadius / 2.0;
            double headingRad = Math.toRadians(heading);
            // 北向偏移 = offset * cos(heading)，东向偏移 = offset * sin(heading)
            double dNorth = offsetM * Math.cos(headingRad);
            double dEast = offsetM * Math.sin(headingRad);
            predictedLat = lat + Math.toDegrees(dNorth / EARTH_RADIUS_M);
            predictedLon = lon + Math.toDegrees(dEast / (EARTH_RADIUS_M * Math.cos(Math.toRadians(lat))));
        }

        return new FallEstimate(safeAlt, fallTimeSec, windDrift, inertiaDrift,
                searchRadius, predictedLat, predictedLon);
    }

    // ------------------------------------------------------------------
    // 公共数据类型
    // ------------------------------------------------------------------

    /** 最后已知位置。 */
    public static final class LastKnownPos {
        public final double lat;
        public final double lon;
        public final double alt;
        public final long timestampMs;
        /** 来源标记："track" 或 "snapshot"。 */
        public final String source;

        public LastKnownPos(double lat, double lon, double alt, long timestampMs, String source) {
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.timestampMs = timestampMs;
            this.source = source;
        }

        @Override
        public String toString() {
            return "LastKnownPos{lat=" + lat + ", lon=" + lon + ", alt=" + alt
                    + "m, ts=" + timestampMs + ", src=" + source + "}";
        }
    }

    /** 失联告警记录。 */
    public static final class LostAlert {
        public final int sysid;
        /** 最后心跳时间戳（epoch ms）。 */
        public final long lastContactMs;
        /** 告警生成时间戳（epoch ms）。 */
        public final long alertMs;
        public final LastKnownPos lastKnownPos;
        /** 最后航向（deg），NaN 表示未知。 */
        public final double heading;
        /** 最后水平速度（m/s），NaN 表示未知。 */
        public final double groundSpeed;
        /** 电池最后电量（%），-1 表示未知。 */
        public final double batteryPct;

        public LostAlert(int sysid, long lastContactMs, long alertMs,
                         LastKnownPos lastKnownPos, double heading,
                         double groundSpeed, double batteryPct) {
            this.sysid = sysid;
            this.lastContactMs = lastContactMs;
            this.alertMs = alertMs;
            this.lastKnownPos = lastKnownPos;
            this.heading = heading;
            this.groundSpeed = groundSpeed;
            this.batteryPct = batteryPct;
        }

        @Override
        public String toString() {
            return "LostAlert{sysid=" + sysid + ", lastContact=" + lastContactMs
                    + ", pos=" + lastKnownPos + ", battery=" + batteryPct + "%}";
        }
    }

    /** 坠落范围估算结果。 */
    public static final class FallEstimate {
        /** 最后高度（m）。 */
        public final double lastAlt;
        /** 自由落体时间（s）。 */
        public final double fallTimeSec;
        /** 风偏距离（m）。 */
        public final double windDrift;
        /** 水平惯性距离（m）。 */
        public final double inertiaDrift;
        /** 建议搜索半径（m）。 */
        public final double searchRadius;
        /** 预测落点纬度；与最后位置相同时表示无法预测。 */
        public final double predictedLat;
        /** 预测落点经度。 */
        public final double predictedLon;

        public FallEstimate(double lastAlt, double fallTimeSec, double windDrift,
                            double inertiaDrift, double searchRadius,
                            double predictedLat, double predictedLon) {
            this.lastAlt = lastAlt;
            this.fallTimeSec = fallTimeSec;
            this.windDrift = windDrift;
            this.inertiaDrift = inertiaDrift;
            this.searchRadius = searchRadius;
            this.predictedLat = predictedLat;
            this.predictedLon = predictedLon;
        }

        @Override
        public String toString() {
            return "FallEstimate{alt=" + lastAlt + "m, t=" + fallTimeSec + "s"
                    + ", radius=" + searchRadius + "m}";
        }
    }

    /** 辅助查找信息：综合最后位置 + 方向 + 电量 + 坠落范围。 */
    public static final class SearchGuide {
        public final int sysid;
        public final long lastContactMs;
        public final LastKnownPos lastKnownPos;
        public final double heading;
        public final double groundSpeed;
        public final double batteryPct;
        public final double windSpeed;
        public final FallEstimate fallEstimate;

        public SearchGuide(int sysid, long lastContactMs, LastKnownPos lastKnownPos,
                           double heading, double groundSpeed, double batteryPct,
                           double windSpeed, FallEstimate fallEstimate) {
            this.sysid = sysid;
            this.lastContactMs = lastContactMs;
            this.lastKnownPos = lastKnownPos;
            this.heading = heading;
            this.groundSpeed = groundSpeed;
            this.batteryPct = batteryPct;
            this.windSpeed = windSpeed;
            this.fallEstimate = fallEstimate;
        }

        @Override
        public String toString() {
            return "SearchGuide{sysid=" + sysid + ", pos=" + lastKnownPos
                    + ", battery=" + batteryPct + "%, fall=" + fallEstimate + "}";
        }
    }

    // 测试辅助方法
    double getMinSearchRadius() {
        return minSearchRadius;
    }

    void setMinSearchRadius(double r) {
        this.minSearchRadius = r;
    }

    double getDefaultWindSpeed() {
        return defaultWindSpeed;
    }

    void setDefaultWindSpeed(double w) {
        this.defaultWindSpeed = w;
    }

    /** 直接访问告警 Map（用于测试）。 */
    Map<Integer, LostAlert> alertMap() {
        return Collections.unmodifiableMap(lostAlerts);
    }
}