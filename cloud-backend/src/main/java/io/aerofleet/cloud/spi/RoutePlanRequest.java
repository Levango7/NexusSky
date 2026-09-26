package io.aerofleet.cloud.spi;

import java.util.List;

/**
 * 航线规划请求，封装航线规划算法所需的输入参数。
 * <p>
 * 由航线规划服务在调用 {@link RoutePlannerPlugin#plan(RoutePlanRequest)} 前构建，
 * 包含起点、终点、途经点、避障区域等。
 */
public class RoutePlanRequest {

    /** 起点航路点 */
    private final Waypoint start;

    /** 终点航路点 */
    private final Waypoint end;

    /** 途经点列表（可选） */
    private final List<Waypoint> waypoints;

    /** 避障区域列表（每个区域为 [lat1, lon1, lat2, lon2] 矩形边界） */
    private final List<double[]> avoidanceZones;

    /** 最大飞行高度（米） */
    private final double maxAltitude;

    /** 最小安全距离（米，与障碍物的距离） */
    private final double minSafeDistance;

    /** 飞行速度（米/秒） */
    private final double cruiseSpeed;

    public RoutePlanRequest(Waypoint start, Waypoint end, List<Waypoint> waypoints,
                            List<double[]> avoidanceZones, double maxAltitude,
                            double minSafeDistance, double cruiseSpeed) {
        this.start = start;
        this.end = end;
        this.waypoints = waypoints != null ? waypoints : List.of();
        this.avoidanceZones = avoidanceZones != null ? avoidanceZones : List.of();
        this.maxAltitude = maxAltitude;
        this.minSafeDistance = minSafeDistance;
        this.cruiseSpeed = cruiseSpeed;
    }

    public Waypoint getStart() {
        return start;
    }

    public Waypoint getEnd() {
        return end;
    }

    public List<Waypoint> getWaypoints() {
        return waypoints;
    }

    public List<double[]> getAvoidanceZones() {
        return avoidanceZones;
    }

    public double getMaxAltitude() {
        return maxAltitude;
    }

    public double getMinSafeDistance() {
        return minSafeDistance;
    }

    public double getCruiseSpeed() {
        return cruiseSpeed;
    }
}