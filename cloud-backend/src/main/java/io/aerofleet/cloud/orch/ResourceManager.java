package io.aerofleet.cloud.orch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资源池管理器，负责无人机资源的分配与释放。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>管理无人机资源池的分配与释放</li>
 *   <li>检测资源冲突（同一无人机不能同时被两个执行中的步骤占用）</li>
 *   <li>跟踪每架无人机当前被哪个计划占用</li>
 * </ul>
 * <p>
 * 线程安全：使用 {@link ConcurrentHashMap} 存储映射关系，
 * 复合操作（如 allocate 的检查-then-分配）通过 synchronized 保护原子性。
 *
 * @see PlanStatus
 * @see StepStatus
 */
@Component
public class ResourceManager {

    private static final Logger log = LoggerFactory.getLogger(ResourceManager.class);

    /** 无人机 → 占用该无人机的计划ID（null 表示未被占用） */
    private final ConcurrentHashMap<Integer, Long> droneOwnership = new ConcurrentHashMap<>();

    /** 计划ID → 该计划已分配的无人机集合 */
    private final ConcurrentHashMap<Long, Set<Integer>> planAllocations = new ConcurrentHashMap<>();

    /**
     * 分配无人机资源给指定计划。
     * <p>
     * 若所需无人机中任意一架已被其他计划占用，则分配失败，返回 false。
     * 分配成功时更新占用关系与计划分配记录。
     *
     * @param planId          计划ID
     * @param requiredDrones  所需无人机ID列表
     * @return true 表示分配成功；false 表示存在资源冲突
     */
    public synchronized boolean allocate(Long planId, List<Integer> requiredDrones) {
        if (planId == null || requiredDrones == null || requiredDrones.isEmpty()) {
            return false;
        }

        // 检查所有所需无人机是否可用
        for (Integer droneId : requiredDrones) {
            Long owner = droneOwnership.get(droneId);
            if (owner != null && !owner.equals(planId)) {
                log.warn("allocate failed: drone {} already owned by plan {}, requested by plan {}",
                        droneId, owner, planId);
                return false;
            }
        }

        // 全部可用，执行分配
        for (Integer droneId : requiredDrones) {
            droneOwnership.put(droneId, planId);
        }

        Set<Integer> allocated = planAllocations.computeIfAbsent(planId, k -> ConcurrentHashMap.newKeySet());
        allocated.addAll(requiredDrones);

        log.info("allocate success: plan {} allocated drones {}", planId, requiredDrones);
        return true;
    }

    /**
     * 释放指定计划的无人机资源。
     * <p>
     * 从占用映射和计划分配记录中移除对应的无人机。
     * 仅释放属于该计划的无人机，避免误释放其他计划的资源。
     *
     * @param planId  计划ID
     * @param drones  要释放的无人机ID列表
     */
    public synchronized void release(Long planId, List<Integer> drones) {
        if (planId == null || drones == null || drones.isEmpty()) {
            return;
        }

        for (Integer droneId : drones) {
            Long owner = droneOwnership.get(droneId);
            if (owner != null && owner.equals(planId)) {
                droneOwnership.remove(droneId);
            }
        }

        Set<Integer> allocated = planAllocations.get(planId);
        if (allocated != null) {
            for (Integer droneId : drones) {
                allocated.remove(droneId);
            }
            if (allocated.isEmpty()) {
                planAllocations.remove(planId);
            }
        }

        log.info("release success: plan {} released drones {}", planId, drones);
    }

    /**
     * 检查指定无人机是否可用（未被任何计划占用）。
     *
     * @param droneId 无人机ID
     * @return true 表示可用；false 表示已被占用
     */
    public boolean isAvailable(Integer droneId) {
        return !droneOwnership.containsKey(droneId);
    }

    /**
     * 从资源池中返回当前可用的无人机列表。
     *
     * @param pool 候选无人机ID列表
     * @return 可用无人机ID列表
     */
    public List<Integer> getAvailableDrones(List<Integer> pool) {
        List<Integer> available = new ArrayList<>();
        if (pool == null || pool.isEmpty()) {
            return available;
        }
        for (Integer droneId : pool) {
            if (!droneOwnership.containsKey(droneId)) {
                available.add(droneId);
            }
        }
        return available;
    }

    /**
     * 获取指定计划已分配的无人机列表。
     *
     * @param planId 计划ID
     * @return 已分配无人机ID列表；计划不存在时返回空列表
     */
    public List<Integer> getPlanAllocations(Long planId) {
        Set<Integer> allocated = planAllocations.get(planId);
        if (allocated == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(allocated);
    }
}