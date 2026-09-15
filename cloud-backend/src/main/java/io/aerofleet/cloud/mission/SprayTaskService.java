package io.aerofleet.cloud.mission;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 喷洒任务管理服务（FR-12/FR-14/FR-15）。
 * <p>
 * 持有 {@code ConcurrentHashMap<taskId, SprayTask>}，承载任务创建 / 查询 / 控制；
 * 控制命令经 {@link DroneCommandService} 下发 COMMAND_LONG(320) 到目标无人机。
 *
 * <p>复用（只读/逐机，不修改既有服务）：
 * <ul>
 *   <li>{@link DroneCommandService}：command 下发（逐机）</li>
 *   <li>{@link DeviceRegistry}：all/get（只读，校验无人机在线）</li>
 * </ul>
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

    public SprayTaskService(DroneCommandService commands, DeviceRegistry registry) {
        this.commands = commands;
        this.registry = registry;
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
        log.info("SprayTask created: id={} sysid={} segments={} totalArea={}",
                id, req.targetSysid, task.segmentCount(), task.totalArea());
        return task;
    }

    /** 查询喷洒任务（FR-15）。 */
    public SprayTask task(int id) {
        return tasks.get(id);
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
        SprayTask task = tasks.get(id);
        if (task == null) {
            throw new NotFoundException("spray task " + id + " not found");
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
        Map<Integer, AckResult> out = new LinkedHashMap<>();
        out.put(sysid, result);
        return out;
    }

    /** 无人机离线异常（FR-14 异常场景，对应 HTTP 409）。 */
    public static class DroneOfflineException extends RuntimeException {
        public DroneOfflineException(String message) {
            super(message);
        }
    }
}