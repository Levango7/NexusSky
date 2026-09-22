package io.aerofleet.cloud.mission.delivery;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.mission.spray.SprayTaskService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 配送任务管理服务（FR-23/FR-24/FR-25）。
 * <p>
 * 持有 {@code ConcurrentHashMap<deliveryId, DeliverySequence>}，承载任务创建 / 查询 / 控制；
 * 控制命令经 {@link DroneCommandService} 下发 COMMAND_LONG(321) 到目标无人机。
 *
 * <p>复用（只读/逐机，不修改既有服务）：
 * <ul>
 *   <li>{@link DroneCommandService}：command 下发（逐机）</li>
 *   <li>{@link DeviceRegistry}：all/get（只读，校验无人机在线）</li>
 * </ul>
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

    public DeliveryService(DroneCommandService commands, DeviceRegistry registry) {
        this.commands = commands;
        this.registry = registry;
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
        log.info("DeliverySequence created: id={} sysid={} sites={}",
                id, req.targetSysid, sites.size());
        return seq;
    }

    /** 查询配送任务（FR-25）。 */
    public DeliverySequence sequence(int id) {
        return sequences.get(id);
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
            throw new NotFoundException("delivery " + id + " not found");
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
        Map<Integer, AckResult> out = new LinkedHashMap<>();
        out.put(sysid, result);
        return out;
    }

    /** 位置更新回调（由遥测回传驱动，FR-24）。 */
    public void onPositionUpdate(int sysid, double lat, double lon) {
        for (DeliverySequence seq : sequences.values()) {
            if (seq.targetSysid() == sysid) {
                seq.onPositionUpdate(lat, lon);
            }
        }
    }
}