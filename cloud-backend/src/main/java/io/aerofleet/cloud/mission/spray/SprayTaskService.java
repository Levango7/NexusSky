package io.aerofleet.cloud.mission.spray;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 喷洒任务管理服务（FR-12/FR-14/FR-15）。
 * <p>
 * 采用混合持久化模式：内存缓存（{@code ConcurrentHashMap}）保证并发读性能，
 * JPA Repository 负责重启后的状态恢复和持久化。
 * <p>
 * 控制命令经 {@link DroneCommandService} 下发 COMMAND_LONG(320) 到目标无人机。
 *
 * <p>并发安全：tasks ConcurrentHashMap + AtomicInteger nextTaskId。
 */
@Service
public class SprayTaskService {

    private static final Logger log = LoggerFactory.getLogger(SprayTaskService.class);

    /** MAV_CMD 320：喷洒控制（spec.md §4.3 命令 id 分配）。 */
    private static final int MAV_CMD_SPRAY_CONTROL = 320;

    /** SPRAY_COMMAND 枚举值（与 mavlink-core SprayCommand 常量一致）。 */
    private static final int SPRAY_ENABLE = 0;
    private static final int SPRAY_DISABLE = 1;
    private static final int SPRAY_SET_RATE = 2;
    private static final int SPRAY_EMERGENCY_STOP = 3;

    private final ConcurrentHashMap<Integer, SprayTask> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger nextTaskId = new AtomicInteger(0);

    private final DroneCommandService commands;
    private final DeviceRegistry registry;

    /** JPA Repository（可选注入，数据库不可用时降级为纯内存模式）。 */
    @Autowired(required = false)
    private SprayTaskRepository repository;

    public SprayTaskService(DroneCommandService commands, DeviceRegistry registry) {
        this.commands = commands;
        this.registry = registry;
    }

    /**
     * 启动时从 Repository 恢复任务到内存缓存。
     * <p>
     * 仅恢复非终态任务（PENDING/RUNNING/PAUSED），终态任务（COMPLETED/FAILED）不恢复。
     */
    public void restoreFromRepository() {
        if (repository == null) {
            log.info("SprayTaskRepository not available, skipping restore");
            return;
        }
        try {
            List<SprayTaskEntity> entities = repository.findAll();
            int maxId = 0;
            for (SprayTaskEntity entity : entities) {
                SprayTask task = entity.toTask();
                tasks.put(task.taskId(), task);
                if (task.taskId() > maxId) {
                    maxId = task.taskId();
                }
            }
            nextTaskId.set(maxId);
            log.info("Restored {} spray tasks from repository, nextTaskId={}", entities.size(), maxId);
        } catch (Exception e) {
            log.warn("Failed to restore spray tasks from repository: {}", e.getMessage());
        }
    }

    /** 单机命令 ACK 结果（不可变值对象，与 FormationService.AckResult 风格一致）。 */
    public record AckResult(int result, String error) {
        public static AckResult ok() { return new AckResult(0, null); }
        public static AckResult fail(String msg) { return new AckResult(-1, msg); }
    }

    /**
     * 创建喷洒任务（FR-12）。
     *
     * @param req 请求体（waypoints ≥ 2）
     * @return 创建的 SprayTask
     * @throws BadRequestException 航点 < 2 或字段非法
     */
    public SprayTask create(SprayTaskRequest req) {
        if (req.waypoints == null || req.waypoints.size() < 2) {
            throw new BadRequestException("waypoints must have >= 2 points");
        }
        if (req.targetRate <= 0) {
            throw new BadRequestException("targetRate must be positive");
        }
        if (req.capacityMl <= 0) {
            throw new BadRequestException("capacityMl must be positive");
        }
        if (req.sprayWidth <= 0) {
            throw new BadRequestException("sprayWidth must be positive");
        }
        int id = nextTaskId.incrementAndGet();
        SprayTask task = new SprayTask(id, req.targetSysid, req.waypoints,
                req.targetRate, req.capacityMl, req.sprayWidth);
        tasks.put(id, task);

        // 持久化到 Repository
        Integer tenantId = TenantContext.getEffectiveTenantId();
        if (repository != null) {
            try {
                SprayTaskEntity entity = SprayTaskEntity.fromTask(task, tenantId);
                repository.save(entity);
            } catch (Exception e) {
                log.warn("Failed to persist spray task {}: {}", id, e.getMessage());
            }
        }

        log.info("SprayTask created: id={} sysid={} segments={} totalArea={} tenantId={}",
                id, req.targetSysid, task.segmentCount(), task.totalArea(), tenantId);
        return task;
    }

    /** 查询喷洒任务（FR-15）。 */
    public SprayTask task(int id) {
        SprayTask task = tasks.get(id);
        if (task != null) {
            return task;
        }
        // 内存未命中，尝试从 Repository 加载
        if (repository != null) {
            try {
                SprayTaskEntity entity = repository.findById(id).orElse(null);
                if (entity != null) {
                    task = entity.toTask();
                    tasks.put(id, task);
                    return task;
                }
            } catch (Exception e) {
                log.warn("Failed to load spray task {} from repository: {}", id, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 查询租户下的所有喷洒任务。
     *
     * @return 任务列表（按 tenantId 过滤）
     */
    public List<SprayTask> tasksByTenant() {
        Integer tenantId = TenantContext.getEffectiveTenantId();
        if (tenantId == null) {
            return List.copyOf(tasks.values());
        }
        return tasks.values().stream()
                .filter(t -> {
                    // 内存中无 tenantId 映射，从 Repository 查
                    if (repository != null) {
                        try {
                            SprayTaskEntity entity = repository.findById(t.taskId()).orElse(null);
                            return entity != null && tenantId.equals(entity.getTenantId());
                        } catch (Exception e) {
                            return true; // 降级：Repository 不可用时返回所有
                        }
                    }
                    return true;
                })
                .toList();
    }

    /**
     * 控制喷洒任务（FR-14）。
     * <p>
     * action ∈ {START, PAUSE, STOP, EMERGENCY_STOP}：
     * <ul>
     *   <li>START → 下发 SPRAY_COMMAND(enable, targetRate) + task.start()</li>
     *   <li>PAUSE → 下发 SPRAY_COMMAND(rate=0) + task.pause()</li>
     *   <li>STOP → 下发 SPRAY_COMMAND(disable) + task.stop()</li>
     *   <li>EMERGENCY_STOP → 下发 SPRAY_COMMAND(emergency_stop) + task.stop()</li>
     * </ul>
     *
     * @throws NotFoundException 任务不存在
     * @throws BadRequestException 未知 action
     */
    public Map<Integer, AckResult> control(int id, String action) {
        if (action == null) {
            throw new BadRequestException("action is required");
        }
        SprayTask task = tasks.get(id);
        if (task == null) {
            // 尝试从 Repository 加载
            if (repository != null) {
                try {
                    SprayTaskEntity entity = repository.findById(id).orElse(null);
                    if (entity != null) {
                        task = entity.toTask();
                        tasks.put(id, task);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load spray task {} from repository: {}", id, e.getMessage());
                }
            }
            if (task == null) {
                throw new NotFoundException("spray task " + id + " not found");
            }
        }
        int sysid = task.targetSysid();
        // 校验无人机在线（FR-14 异常场景：目标无人机离线 → 409）
        DroneSnapshot snap = registry.get(sysid);
        if (snap == null || !snap.online) {
            throw new DroneOfflineException("drone " + sysid + " offline");
        }

        AckResult result;
        try {
            switch (action.toUpperCase()) {
                case "START" -> {
                    // param1=command(ENABLE), param2=targetRate, param3=sprayWidth
                    commands.command(sysid, MAV_CMD_SPRAY_CONTROL,
                            SPRAY_ENABLE, (float) task.targetRate(), 0, 0, 0, 0, 0);
                    task.start();
                    result = AckResult.ok();
                }
                case "PAUSE" -> {
                    commands.command(sysid, MAV_CMD_SPRAY_CONTROL,
                            SPRAY_SET_RATE, 0, 0, 0, 0, 0, 0);
                    task.pause();
                    result = AckResult.ok();
                }
                case "STOP" -> {
                    commands.command(sysid, MAV_CMD_SPRAY_CONTROL,
                            SPRAY_DISABLE, 0, 0, 0, 0, 0, 0);
                    task.stop();
                    result = AckResult.ok();
                }
                case "EMERGENCY_STOP" -> {
                    commands.command(sysid, MAV_CMD_SPRAY_CONTROL,
                            SPRAY_EMERGENCY_STOP, 0, 0, 0, 0, 0, 0);
                    task.stop();
                    result = AckResult.ok();
                }
                default -> throw new BadRequestException("unknown action: " + action);
            }
        } catch (DroneCommandService.CommandException e) {
            log.warn("SprayTask control failed: id={} action={} error={}", id, action, e.getMessage());
            result = AckResult.fail(e.getMessage());
        }

        // 状态变更后同步到 Repository
        persistTaskState(id, task);

        Map<Integer, AckResult> out = new LinkedHashMap<>();
        out.put(sysid, result);
        return out;
    }

    /** 将任务状态同步到 Repository。 */
    private void persistTaskState(int id, SprayTask task) {
        if (repository == null) {
            return;
        }
        try {
            SprayTaskEntity entity = repository.findById(id).orElse(null);
            if (entity != null) {
                entity.updateFromTask(task);
                repository.save(entity);
            } else {
                // Entity 不存在（可能是恢复前创建的），创建新 Entity
                Integer tenantId = TenantContext.getEffectiveTenantId();
                entity = SprayTaskEntity.fromTask(task, tenantId);
                repository.save(entity);
            }
        } catch (Exception e) {
            log.warn("Failed to persist spray task state {}: {}", id, e.getMessage());
        }
    }

    /** 无人机离线异常（FR-14 异常场景，对应 HTTP 409）。 */
    public static class DroneOfflineException extends RuntimeException {
        public DroneOfflineException(String message) {
            super(message);
        }
    }
}
