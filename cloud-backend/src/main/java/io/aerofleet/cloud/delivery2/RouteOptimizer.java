package io.aerofleet.cloud.delivery2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 配送路线优化服务。
 * <p>
 * 使用贪心最近邻算法求解多目标 TSP（简化版），
 * 支持 Haversine 距离估算和飞行时间估算。
 */
@Service
public class RouteOptimizer {

    private static final Logger log = LoggerFactory.getLogger(RouteOptimizer.class);

    /** 地球半径（km）。 */
    private static final double EARTH_RADIUS_KM = 6371.0;

    /** 默认无人机飞行速度（m/s）。 */
    private static final double DEFAULT_SPEED_MPS = 15.0;

    /**
     * 优化配送路线（贪心最近邻算法）。
     * <p>
     * 从第一个任务的发送点出发，依次选择最近未访问的目标点，
     * 生成包含 PICKUP 和 DELIVER 航点的有序路线。
     *
     * @param tasks 配送任务列表
     * @return 优化后的路线
     */
    public OptimizedRoute optimizeRoute(List<DeliveryTask2> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return new OptimizedRoute(null, new ArrayList<>(), 0, 0, 0);
        }

        log.debug("优化配送路线：任务数={}", tasks.size());

        List<RouteWaypoint> waypoints = new ArrayList<>();
        double totalDistanceKm = 0;
        int seq = 0;

        // 按优先级排序：HIGH > NORMAL > LOW
        List<DeliveryTask2> sortedTasks = new ArrayList<>(tasks);
        sortedTasks.sort(Comparator.comparingInt(t -> {
            DeliveryTask2.Priority p = t.getPriority();
            if (p == null) {
                return 1;
            }
            return switch (p) {
                case HIGH -> 0;
                case NORMAL -> 1;
                case LOW -> 2;
            };
        }));

        // 贪心最近邻：从第一个任务的发送点出发
        double currentLat = sortedTasks.get(0).getSenderLat();
        double currentLon = sortedTasks.get(0).getSenderLon();

        boolean[] visited = new boolean[sortedTasks.size()];

        for (int round = 0; round < sortedTasks.size(); round++) {
            int nearestIdx = -1;
            double nearestDist = Double.MAX_VALUE;

            for (int i = 0; i < sortedTasks.size(); i++) {
                if (visited[i]) {
                    continue;
                }
                DeliveryTask2 task = sortedTasks.get(i);
                double dist = estimateDistance(currentLat, currentLon,
                        task.getSenderLat(), task.getSenderLon());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestIdx = i;
                }
            }

            if (nearestIdx == -1) {
                break;
            }

            visited[nearestIdx] = true;
            DeliveryTask2 task = sortedTasks.get(nearestIdx);
            String taskId = task.getId();

            // 飞行到发送点
            if (nearestDist > 0.001) {
                totalDistanceKm += nearestDist;
                waypoints.add(new RouteWaypoint(seq++, currentLat, currentLon,
                        RouteWaypoint.Action.FLY, 0, taskId));
            }

            // PICKUP 航点
            waypoints.add(new RouteWaypoint(seq++, task.getSenderLat(), task.getSenderLon(),
                    RouteWaypoint.Action.PICKUP, 30, taskId));
            currentLat = task.getSenderLat();
            currentLon = task.getSenderLon();

            // 飞行到接收点
            double deliverDist = estimateDistance(currentLat, currentLon,
                    task.getReceiverLat(), task.getReceiverLon());
            totalDistanceKm += deliverDist;
            waypoints.add(new RouteWaypoint(seq++, currentLat, currentLon,
                    RouteWaypoint.Action.FLY, 0, taskId));

            // DELIVER 航点
            waypoints.add(new RouteWaypoint(seq++, task.getReceiverLat(), task.getReceiverLon(),
                    RouteWaypoint.Action.DELIVER, 60, taskId));
            currentLat = task.getReceiverLat();
            currentLon = task.getReceiverLon();
        }

        double estimatedTimeMin = estimateTime(totalDistanceKm, DEFAULT_SPEED_MPS) / 60.0;

        log.info("路线优化完成：航点数={} 总距离={}km 预估时间={}min",
                waypoints.size(), String.format("%.2f", totalDistanceKm),
                String.format("%.1f", estimatedTimeMin));

        return new OptimizedRoute(null, waypoints, totalDistanceKm, estimatedTimeMin, 0);
    }

    /**
     * 使用 Haversine 公式估算两点间距离。
     *
     * @param lat1 纬度1
     * @param lon1 经度1
     * @param lat2 纬度2
     * @param lon2 经度2
     * @return 距离（km）
     */
    public double estimateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /**
     * 估算飞行时间。
     *
     * @param distanceKm 距离（km）
     * @param speedMps   速度（m/s）
     * @return 时间（秒）
     */
    public double estimateTime(double distanceKm, double speedMps) {
        if (speedMps <= 0) {
            return 0;
        }
        double distanceM = distanceKm * 1000;
        return distanceM / speedMps;
    }
}