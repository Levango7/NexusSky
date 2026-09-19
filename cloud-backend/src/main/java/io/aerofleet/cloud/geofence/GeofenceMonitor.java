package io.aerofleet.cloud.geofence;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 电子围栏监控服务：定期检查无人机位置是否越界并生成告警事件。
 * <p>
 * 围栏语义：围栏区域 = 允许活动区（keep-in zone）。
 * <ul>
 *   <li>无人机从区域内 → 区域外：生成 {@link GeofenceBreachEvent.BreachType#EXIT EXIT} 事件（离开允许区 = 越界告警）。</li>
 *   <li>无人机从区域外 → 区域内：生成 {@link GeofenceBreachEvent.BreachType#ENTER ENTER} 事件（进入允许区 = 恢复通知）。</li>
 *   <li>首次检查时若已在区域外，生成 EXIT 事件。</li>
 * </ul>
 * <p>
 * 几何计算：
 * <ul>
 *   <li>圆形围栏：{@link #haversineDistance Haversine 大圆距离} ≤ 半径。</li>
 *   <li>多边形围栏：{@link #isInsidePolygon 射线法（ray casting）}判断点是否在多边形内部。</li>
 * </ul>
 * <p>
 * 依赖 {@link DeviceRegistry} 和 {@code FlightTrackStore} 通过 {@code @Lazy} 注入避免循环依赖。
 * {@code @EnableScheduling} 已由 {@code CloudBackendApplication} 启用。
 */
@Service
public class GeofenceMonitor {

    private static final Logger log = LoggerFactory.getLogger(GeofenceMonitor.class);

    /** WGS84 地球平均半径（米）。 */
    static final double EARTH_RADIUS_M = 6_371_000.0;

    private final GeofenceStore store;
    private final DeviceRegistry registry;

    /** 检查间隔（毫秒），可通过配置覆盖。 */
    @Value("${aerofleet.geofence.check-interval-ms:5000}")
    private long checkIntervalMs = 5000L;

    /**
     * 围栏内/外状态：key = sysid * 100000 + zoneId（假设 zoneId < 100000），
     * value = 上次是否在围栏内。用于检测状态切换并生成事件。
     */
    private final Map<Long, Boolean> insideState = new ConcurrentHashMap<>();

    public GeofenceMonitor(GeofenceStore store,
                           @Lazy DeviceRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    // ------------------------------------------------------------------
    // 位置检查
    // ------------------------------------------------------------------

    /**
     * 检查指定无人机位置是否越界，对每个启用的围栏区域判断 inside/outside 状态切换，
     * 状态切换时生成 {@link GeofenceBreachEvent} 存入 {@link GeofenceStore}。
     *
     * @param sysid 无人机 systemId
     * @param lat   纬度
     * @param lon   经度
     * @return 本次检查新生成的越界事件列表（可能为空）
     */
    public List<GeofenceBreachEvent> checkPosition(int sysid, double lat, double lon) {
        List<GeofenceBreachEvent> newEvents = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (GeofenceZone zone : store.getAllZones()) {
            if (!zone.isEnabled()) {
                continue;
            }
            boolean inside = isInsideZone(lat, lon, zone);
            long stateKey = stateKey(sysid, zone.getId());
            Boolean previous = insideState.get(stateKey);

            if (previous == null) {
                // 首次检查：若在围栏外，生成 EXIT 事件
                if (!inside) {
                    GeofenceBreachEvent ev = new GeofenceBreachEvent(
                            sysid, zone.getId(), zone.getName(),
                            GeofenceBreachEvent.BreachType.EXIT,
                            lat, lon, now);
                    store.recordBreach(ev);
                    newEvents.add(ev);
                }
            } else if (previous && !inside) {
                // inside → outside：离开允许区（越界告警）
                GeofenceBreachEvent ev = new GeofenceBreachEvent(
                        sysid, zone.getId(), zone.getName(),
                        GeofenceBreachEvent.BreachType.EXIT,
                        lat, lon, now);
                store.recordBreach(ev);
                newEvents.add(ev);
            } else if (!previous && inside) {
                // outside → inside：进入允许区（恢复通知）
                GeofenceBreachEvent ev = new GeofenceBreachEvent(
                        sysid, zone.getId(), zone.getName(),
                        GeofenceBreachEvent.BreachType.ENTER,
                        lat, lon, now);
                store.recordBreach(ev);
                newEvents.add(ev);
            }
            insideState.put(stateKey, inside);
        }
        return newEvents;
    }

    /**
     * 检查所有在线无人机位置是否越界。
     * <p>
     * 从 {@link DeviceRegistry} 获取所有无人机快照，对在线且有有效位置（lat/lon 非 NaN）的无人机调用 {@link #checkPosition}。
     *
     * @return 本次检查新生成的所有越界事件列表
     */
    public List<GeofenceBreachEvent> checkAllDrones() {
        List<GeofenceBreachEvent> allEvents = new ArrayList<>();
        List<DroneSnapshot> drones = registry.all();
        for (DroneSnapshot d : drones) {
            if (!d.online) {
                continue;
            }
            if (Double.isNaN(d.lat) || Double.isNaN(d.lon)) {
                continue;
            }
            List<GeofenceBreachEvent> events = checkPosition(d.sysid, d.lat, d.lon);
            allEvents.addAll(events);
        }
        if (!allEvents.isEmpty()) {
            log.info("Geofence check all drones: {} breach event(s) from {} drone(s)",
                    allEvents.size(), drones.size());
        }
        return allEvents;
    }

    /**
     * 定期检查所有无人机位置是否越界。
     * <p>
     * 间隔由 {@code aerofleet.geofence.check-interval-ms} 配置，默认 5000ms。
     */
    @Scheduled(fixedRateString = "${aerofleet.geofence.check-interval-ms:5000}")
    public void scheduledCheck() {
        try {
            checkAllDrones();
        } catch (Exception e) {
            log.error("Scheduled geofence check failed", e);
        }
    }

    /** 清除指定无人机的围栏状态记录（用于无人机注销或测试）。 */
    public void clearState(int sysid) {
        insideState.keySet().removeIf(k -> k / 100000 == sysid);
    }

    /** 清除所有围栏状态记录（用于测试）。 */
    public void clearAllState() {
        insideState.clear();
    }

    // ------------------------------------------------------------------
    // 几何计算
    // ------------------------------------------------------------------

    /** 判断点是否在围栏区域内（按围栏类型分派）。 */
    public boolean isInsideZone(double lat, double lon, GeofenceZone zone) {
        if (zone.getType() == GeofenceZone.Type.CIRCLE) {
            return isInsideCircle(lat, lon, zone.getCenterLat(), zone.getCenterLon(), zone.getRadiusM());
        }
        return isInsidePolygon(lat, lon, zone.getPoints());
    }

    /**
     * 判断点是否在圆形围栏内（Haversine 距离 ≤ 半径）。
     */
    public boolean isInsideCircle(double lat, double lon,
                                  double centerLat, double centerLon, double radiusM) {
        return haversineDistance(lat, lon, centerLat, centerLon) <= radiusM;
    }

    /**
     * 判断点是否在多边形内部（射线法 / ray casting）。
     * <p>
     * 从待测点向右发射水平射线，计算与多边形各边的交点数：
     * 奇数 = 内部，偶数 = 外部。支持凹多边形。
     *
     * @param lat    待测点纬度
     * @param lon    待测点经度
     * @param points 多边形顶点列表（至少 3 个，按顺序连接）
     * @return true = 在多边形内部（含边界），false = 在外部
     */
    public boolean isInsidePolygon(double lat, double lon, List<GeofenceZone.GeoPoint> points) {
        int n = points.size();
        if (n < 3) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            GeofenceZone.GeoPoint pi = points.get(i);
            GeofenceZone.GeoPoint pj = points.get(j);
            double yi = pi.lat();
            double xi = pi.lon();
            double yj = pj.lat();
            double xj = pj.lon();

            // 判断射线是否穿过边 (pj, pi)
            if (((yi > lat) != (yj > lat))
                    && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Haversine 大圆距离：计算两个经纬度点之间的球面距离（米）。
     * <p>
     * 公式：{@code d = 2 * R * asin(sqrt(sin²(Δlat/2) + cos(lat1)*cos(lat2)*sin²(Δlon/2)))}
     *
     * @param lat1 点1 纬度
     * @param lon1 点1 经度
     * @param lat2 点2 纬度
     * @param lon2 点2 经度
     * @return 球面距离（米），非负
     */
    public double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return EARTH_RADIUS_M * c;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /** 组合状态键：sysid * 100000 + zoneId（要求 zoneId < 100000）。 */
    private static long stateKey(int sysid, int zoneId) {
        return (long) sysid * 100000L + zoneId;
    }

    /** 测试辅助：获取检查间隔。 */
    public long getCheckIntervalMs() {
        return checkIntervalMs;
    }

    /** 测试辅助：设置检查间隔。 */
    public void setCheckIntervalMs(long checkIntervalMs) {
        this.checkIntervalMs = checkIntervalMs;
    }
}