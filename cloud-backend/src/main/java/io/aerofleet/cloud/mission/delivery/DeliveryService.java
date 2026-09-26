package io.aerofleet.cloud.mission.delivery;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.mission.spray.SprayTaskService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 配送任务管理服务（FR-23/FR-24/FR-25）。
 * <p>
 * 采用混合持久化模式：内存缓存（{@code ConcurrentHashMap}）保证并发读性能，
 * JPA Repository 负责重启后的状态恢复和持久化。
 * <p>
 * 控制命令经 {@link DroneCommandService} 下发 COMMAND_LONG(321) 到目标无人机。
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    /** MAV_CMD 321：抛投控制（spec.md §4.3 命令 id 分配）。 */
    private static final int MAV_CMD_GRIPPER_CONTROL = 321;

    /** GRIPPER_COMMAND 枚举值（与 mavlink-core GripperCommand 常量一致）。 */
    private static final int GRIPPER_GRAB = 0;
    private static final int GRIPPER_RELEASE = 1;
    private static final int GRIPPER_RESET = 2;

    private final ConcurrentHashMap<Integer, DeliverySequence> sequences = new ConcurrentHashMap<>();
    private final AtomicInteger nextDeliveryId = new AtomicInteger(0);

    private final DroneCommandService commands;
    private final DeviceRegistry registry;

    /** JPA Repository（可选注入，数据库不可用时降级为纯内存模式）。 */
    @Autowired(required = false)
    private DeliverySequenceRepository repository;

    public DeliveryService(DroneCommandService commands, DeviceRegistry registry) {
        this.commands = commands;
        this.registry = registry;
    }

    /**
     * 启动时从 Repository 恢复任务到内存缓存。
     * <p>
     * 仅恢复非终态任务（PENDING/RUNNING），终态任务不恢复。
     */
    public void restoreFromRepository() {
        if (repository == null) {
            log.info("DeliverySequenceRepository not available, skipping restore");
            return;
        }
        try {
            List<DeliverySequenceEntity> entities = repository.findAll();
            int maxId = 0;
            for (DeliverySequenceEntity entity : entities) {
                DeliverySequence seq = entity.toSequence();
                sequences.put(seq.deliveryId(), seq);
                if (seq.deliveryId() > maxId) {
                    maxId = seq.deliveryId();
                }
            }
            nextDeliveryId.set(maxId);
            log.info("Restored {} delivery sequences from repository, nextDeliveryId={}", entities.size(), maxId);
        } catch (Exception e) {
            log.warn("Failed to restore delivery sequences from repository: {}", e.getMessage());
        }
    }

    /** 单机命令 ACK 结果（不可变值对象）。 */
    public record AckResult(int result, String error) {
        public static AckResult ok() { return new AckResult(0, null); }
        public static AckResult fail(String msg) { return new AckResult(-1, msg); }
    }

    /**
     * 创建配送任务（FR-23）。
     *
     * @param req 请求体（sites 非空）
     * @return 创建的 DeliverySequence
     * @throws BadRequestException 空站点列表或字段非法
     */
    public DeliverySequence create(DeliveryRequest req) {
        if (req.sites == null || req.sites.isEmpty()) {
            throw new BadRequestException("sites must not be empty");
        }
        int id = nextDeliveryId.incrementAndGet();
        List<DeliverySite> sites = new ArrayList<>();
        for (int i = 0; i < req.sites.size(); i++) {
            DeliveryRequest.SiteDto s = req.sites.get(i);
            sites.add(new DeliverySite(i, s.lat, s.lon, s.alt,
                    s.payloadId, s.payloadWeightKg, s.payloadVolumeL,
                    s.dropAccuracyM, DeliverySiteState.PENDING, 0, 0));
        }
        DeliverySequence seq = new DeliverySequence(id, req.targetSysid, sites);
        sequences.put(id, seq);

        // 持久化到 Repository
        Integer tenantId = TenantContext.getEffectiveTenantId();
        if (repository != null) {
            try {
                DeliverySequenceEntity entity = DeliverySequenceEntity.fromSequence(seq, tenantId);
                repository.save(entity);
            } catch (Exception e) {
                log.warn("Failed to persist delivery sequence {}: {}", id, e.getMessage());
            }
        }

        log.info("DeliverySequence created: id={} sysid={} sites={} tenantId={}",
                id, req.targetSysid, sites.size(), tenantId);
        return seq;
    }

    /** 查询配送任务（FR-25）。 */
    public DeliverySequence sequence(int id) {
        DeliverySequence seq = sequences.get(id);
        if (seq != null) {
            return seq;
        }
        // 内存未命中，尝试从 Repository 加载
        if (repository != null) {
            try {
                Optional<DeliverySequenceEntity> opt = repository.findById(id);
                if (opt.isPresent()) {
                    DeliverySequenceEntity entity = opt.get();
                    seq = entity.toSequence();
                    sequences.put(id, seq);
                    return seq;
                }
            } catch (Exception e) {
                log.warn("Failed to load delivery sequence {} from repository: {}", id, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 查询租户下的所有配送任务。
     *
     * @return 任务列表（按 tenantId 过滤）
     */
    public List<DeliverySequence> sequencesByTenant() {
        Integer tenantId = TenantContext.getEffectiveTenantId();
        if (tenantId == null) {
            return List.copyOf(sequences.values());
        }
        return sequences.values().stream()
                .filter(seq -> {
                    if (repository != null) {
                        try {
                            return repository.findById(seq.deliveryId())
                                    .map(entity -> tenantId.equals(entity.getTenantId()))
                                    .orElse(false);
                        } catch (Exception e) {
                            return true; // 降级：Repository 不可用时返回所有
                        }
                    }
                    return true;
                })
                .toList();
    }

    /**
     * 控制配送任务（FR-24，推进/跳过站点）。
     * <p>
     * action ∈ {START, DROP, SKIP, RESET, FINISH}：
     * <ul>
     *   <li>START → seq.start() + 下发 GRAB 命令</li>
     *   <li>DROP → 下发 RELEASE + seq.markDropped(currentIndex)</li>
     *   <li>SKIP → seq.markSkipped(currentIndex)</li>
     *   <li>RESET → 下发 RESET 命令</li>
     *   <li>FINISH → seq.skipRemaining()</li>
     * </ul>
     *
     * @throws NotFoundException 任务不存在
     * @throws BadRequestException 未知 action
     */
    public Map<Integer, AckResult> control(int id, String action) {
        if (action == null) {
            throw new BadRequestException("action is required");
        }
        DeliverySequence seq = sequences.get(id);
        if (seq == null) {
            // 尝试从 Repository 加载
            if (repository != null) {
                try {
                    Optional<DeliverySequenceEntity> opt = repository.findById(id);
                    if (opt.isPresent()) {
                        DeliverySequenceEntity entity = opt.get();
                        seq = entity.toSequence();
                        sequences.put(id, seq);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load delivery sequence {} from repository: {}", id, e.getMessage());
                }
            }
            if (seq == null) {
                throw new NotFoundException("delivery " + id + " not found");
            }
        }
        int sysid = seq.targetSysid();
        // 校验无人机在线（除 FINISH 外，FINISH 是本地状态收尾不需要无人机）
        if (!"FINISH".equalsIgnoreCase(action)) {
            DroneSnapshot snap = registry.get(sysid);
            if (snap == null || !snap.online) {
                throw new SprayTaskService.DroneOfflineException("drone " + sysid + " offline");
            }
        }

        AckResult result;
        try {
            switch (action.toUpperCase()) {
                case "START" -> {
                    commands.command(sysid, MAV_CMD_GRIPPER_CONTROL,
                            GRIPPER_GRAB, 0, 0, 0, 0, 0, 0);
                    seq.start();
                    result = AckResult.ok();
                }
                case "DROP" -> {
                    commands.command(sysid, MAV_CMD_GRIPPER_CONTROL,
                            GRIPPER_RELEASE, 0, 0, 0, 0, 0, 0);
                    seq.markDropped(seq.currentIndex());
                    result = AckResult.ok();
                }
                case "SKIP" -> {
                    seq.markSkipped(seq.currentIndex());
                    result = AckResult.ok();
                }
                case "RESET" -> {
                    commands.command(sysid, MAV_CMD_GRIPPER_CONTROL,
                            GRIPPER_RESET, 0, 0, 0, 0, 0, 0);
                    result = AckResult.ok();
                }
                case "FINISH" -> {
                    seq.skipRemaining();
                    result = AckResult.ok();
                }
                default -> throw new BadRequestException("unknown action: " + action);
            }
        } catch (DroneCommandService.CommandException e) {
            log.warn("Delivery control failed: id={} action={} error={}", id, action, e.getMessage());
            result = AckResult.fail(e.getMessage());
        }

        // 状态变更后同步到 Repository
        persistSequenceState(id, seq);

        Map<Integer, AckResult> out = new LinkedHashMap<>();
        out.put(sysid, result);
        return out;
    }

    /** 位置更新回调（由遥测回传驱动，FR-24）。 */
    public void onPositionUpdate(int sysid, double lat, double lon) {
        for (DeliverySequence seq : sequences.values()) {
            if (seq.targetSysid() == sysid) {
                seq.onPositionUpdate(lat, lon);
                // 位置更新可能改变站点状态，同步到 Repository
                persistSequenceState(seq.deliveryId(), seq);
            }
        }
    }

    /** 将配送任务状态同步到 Repository。 */
    private void persistSequenceState(int id, DeliverySequence seq) {
        if (repository == null) {
            return;
        }
        try {
            Optional<DeliverySequenceEntity> opt = repository.findById(id);
            if (opt.isPresent()) {
                DeliverySequenceEntity entity = opt.get();
                entity.updateFromSequence(seq);
                repository.save(entity);
            } else {
                Integer tenantId = TenantContext.getEffectiveTenantId();
                DeliverySequenceEntity entity = DeliverySequenceEntity.fromSequence(seq, tenantId);
                repository.save(entity);
            }
        } catch (Exception e) {
            log.warn("Failed to persist delivery sequence state {}: {}", id, e.getMessage());
        }
    }
}
