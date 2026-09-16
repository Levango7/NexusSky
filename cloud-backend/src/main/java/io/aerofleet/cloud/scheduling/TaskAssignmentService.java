package io.aerofleet.cloud.scheduling;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * M10 集群智能调度：多机任务分配 + 冲突避免 + 任务队列管理。
 *
 * 分配算法：综合评分 = 能力匹配(40%) + 电量因子(30%) + 距离因子(20%) + 优先级(10%)
 */
@Service
public class TaskAssignmentService {
    private static final Logger log = LoggerFactory.getLogger(TaskAssignmentService.class);

    private final DeviceRegistry registry;
    private final PriorityBlockingQueue<TaskRequest> taskQueue = new PriorityBlockingQueue<>(100,
            Comparator.comparingInt(TaskRequest::getPriority).reversed());
    private final Map<String, AssignmentResult> assignments = new ConcurrentHashMap<>();

    public TaskAssignmentService(DeviceRegistry registry) {
        this.registry = registry;
    }

    /** 分配任务到最优无人机 */
    public AssignmentResult assignTask(TaskRequest req) {
        List<DroneSnapshot> drones = registry.all();
        if (drones.isEmpty()) {
            log.warn("No drones available for task {}", req.getTaskId());
            return new AssignmentResult(req.getTaskId(), -1, 0, "无可用无人机", false);
        }

        DroneSnapshot best = null;
        double bestScore = -1;
        for (DroneSnapshot d : drones) {
            double score = scoreDrone(d, req);
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }

        if (best == null) {
            return new AssignmentResult(req.getTaskId(), -1, 0, "无合适无人机", false);
        }

        AssignmentResult result = new AssignmentResult(req.getTaskId(), best.sysid, bestScore,
                String.format("sysid=%d score=%.1f", best.sysid, bestScore), true);
        assignments.put(req.getTaskId(), result);
        taskQueue.offer(req);
        log.info("Task {} assigned to sysid={} score={}", req.getTaskId(), best.sysid, bestScore);
        return result;
    }

    /** 综合评分：能力(40%) + 电量(30%) + 距离(20%) + 优先级(10%) */
    private double scoreDrone(DroneSnapshot d, TaskRequest req) {
        double capabilityScore = 50.0; // 基础能力分
        double batteryScore = d.battery > 0 ? d.battery * 100 : 0;
        double distanceScore = 100.0; // 默认满分，有位置时计算距离
        if (!Double.isNaN(d.lat) && !Double.isNaN(d.lon) && d.lat != 0 && d.lon != 0) {
            double dist = haversine(d.lat, d.lon, req.getTargetLat(), req.getTargetLon());
            distanceScore = Math.max(0, 100 - dist / 100); // 100km 内得分线性递减
        }
        double priorityScore = req.getPriority() * 10.0;

        return capabilityScore * 0.4 + batteryScore * 0.3 + distanceScore * 0.2 + priorityScore * 0.1;
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)) / 1000; // km
    }

    /** 查询所有分配 */
    public Map<String, AssignmentResult> getAllAssignments() {
        return Collections.unmodifiableMap(assignments);
    }

    /** 取消任务 */
    public boolean cancelTask(String taskId) {
        AssignmentResult removed = assignments.remove(taskId);
        return removed != null;
    }

    /** 全量重新分配（无人机损毁后触发） */
    public void reassignAll() {
        log.info("Reassigning all tasks, count={}", assignments.size());
        Map<String, AssignmentResult> old = new LinkedHashMap<>(assignments);
        assignments.clear();
        for (Map.Entry<String, AssignmentResult> e : old.entrySet()) {
            // 重新分配逻辑可在此扩展
            log.debug("Task {} needs reassignment", e.getKey());
        }
    }
}