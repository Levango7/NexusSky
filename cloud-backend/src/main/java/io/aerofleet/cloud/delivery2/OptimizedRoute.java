package io.aerofleet.cloud.delivery2;

import java.util.ArrayList;
import java.util.List;

/**
 * 优化路线模型。
 * <p>
 * 包含任务 ID、航点列表、总距离、预估时间和配送序列。
 */
public class OptimizedRoute {

    private String taskId;
    private List<RouteWaypoint> waypoints;
    private double totalDistanceKm;
    private double estimatedTimeMin;
    private int sequence;

    public OptimizedRoute() {
        this.waypoints = new ArrayList<>();
    }

    public OptimizedRoute(String taskId, List<RouteWaypoint> waypoints,
                          double totalDistanceKm, double estimatedTimeMin, int sequence) {
        this.taskId = taskId;
        this.waypoints = waypoints;
        this.totalDistanceKm = totalDistanceKm;
        this.estimatedTimeMin = estimatedTimeMin;
        this.sequence = sequence;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public List<RouteWaypoint> getWaypoints() {
        return waypoints;
    }

    public void setWaypoints(List<RouteWaypoint> waypoints) {
        this.waypoints = waypoints;
    }

    public double getTotalDistanceKm() {
        return totalDistanceKm;
    }

    public void setTotalDistanceKm(double totalDistanceKm) {
        this.totalDistanceKm = totalDistanceKm;
    }

    public double getEstimatedTimeMin() {
        return estimatedTimeMin;
    }

    public void setEstimatedTimeMin(double estimatedTimeMin) {
        this.estimatedTimeMin = estimatedTimeMin;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }
}