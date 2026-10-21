package io.aerofleet.cloud.mission.delivery;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.mission.formation.FormationController;
import io.aerofleet.cloud.mission.spray.SprayTaskService;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import io.aerofleet.mavlink.enums.MavEnums;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配送/物流 REST 端点（FR-33/FR-34，路径前缀 /api/v1/delivery）。
 * <p>
 * 复用 {@link FormationController} 风格，独立路径前缀，不修改现有端点（DFX 4.5）。
 * <p>
 * 端点清单：
 * <pre>
 *   POST   /api/v1/delivery                    创建配送任务（FR-23）
 *   GET    /api/v1/delivery/{id}               查询配送任务状态（FR-25）
 *   POST   /api/v1/delivery/{id}/control       控制配送任务（推进/跳过站点）
 *   GET    /api/v1/delivery/{id}/payload       查询当前负载清单（FR-34）
 *   POST   /api/v1/delivery/{id}/payload/query 按需触发机载负载上报（MAV_CMD 30085）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/delivery")
@RequireRole(Role.OBSERVER)
public class DeliveryController {
    private static final Logger log = LoggerFactory.getLogger(DeliveryController.class);

    private final DeliveryService deliveryService;
    private final DroneCommandService commands;

    public DeliveryController(DeliveryService deliveryService, DroneCommandService commands) {
        this.deliveryService = deliveryService;
        this.commands = commands;
    }

    /** 创建配送任务（FR-23）。 */
    @PostMapping
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> create(@RequestBody @Valid DeliveryRequest req) {
        if (req.sites == null || req.sites.isEmpty()) {
            throw new BadRequestException("sites must not be empty");
        }
        DeliverySequence seq = deliveryService.create(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", seq.deliveryId());
        out.put("sites", seq.siteCount());
        out.put("state", seq.state().name());
        return out;
    }

    /** 查询配送任务状态（FR-25）。 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") int id) {
        DeliverySequence seq = deliveryService.sequence(id);
        if (seq == null) {
            throw new NotFoundException("delivery " + id + " not found");
        }
        return view(seq);
    }

    /**
     * 按 sysid 查该机**最近一次**配送序列。
     *
     * <p><b>为什么要有这个口径</b>：前端按"当前选中的无人机"轮询
     * （{@code SprayPanel} 每秒一次），而原端点只按**序列 id** 查 ——
     * 前端手里没有序列 id，于是去调并不存在的 {@code /delivery/sequence/{sysid}}，
     * 404 又被 {@code .catch(() => null)} 静默吞掉（2026-10-21 实测：
     * 前端 5 个面板共 12 处端点对不上后端，这是其中之一）。
     */
    @GetMapping("/by-sysid/{sysid}")
    public ResponseEntity<Map<String, Object>> bySysid(@PathVariable("sysid") int sysid) {
        DeliverySequence seq = latestFor(sysid);
        if (seq == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "no delivery sequence for sysid " + sysid));
        }
        return ResponseEntity.ok(view(seq));
    }

    /**
     * 按 sysid 控制该机最近一次配送序列。
     *
     * <p>前端要的是"夹爪开/关"（{@code SprayPanel} 的夹爪按钮）——
     * 后端的夹爪命令本来就有（{@code DeliveryService} 里的
     * {@code MAV_CMD_NEXUS_GRIPPER_CONTROL}：START=抓取、DROP=释放），
     * 但只挂在**按序列 id** 的 {@code /delivery/{id}/control} 上，
     * 而前端手里只有 sysid ⇒ 这一层"按 sysid 找到最新序列再控制"由服务端做。
     */
    @PostMapping("/by-sysid/{sysid}/control")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> controlBySysid(@PathVariable("sysid") int sysid,
                                                               @RequestBody ControlRequest body) {
        DeliverySequence seq = latestFor(sysid);
        if (seq == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "no delivery sequence for sysid " + sysid));
        }
        Map<Integer, DeliveryService.AckResult> results =
                deliveryService.control(seq.deliveryId(), body.action);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", seq.deliveryId());
        out.put("results", results);
        return ResponseEntity.ok(out);
    }

    /** 该机最近一次配送序列（id 最大 = 最新）。 */
    private DeliverySequence latestFor(int sysid) {
        return deliveryService.sequencesByTenant().stream()
                .filter(s -> s.targetSysid() == sysid)
                .max(Comparator.comparingInt(DeliverySequence::deliveryId))
                .orElse(null);
    }

    /** 配送序列视图（按 id 查与按 sysid 查共用，避免两处口径漂移）。 */
    private Map<String, Object> view(DeliverySequence seq) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", seq.deliveryId());
        out.put("state", seq.state().name());
        out.put("progress", seq.progress());
        out.put("currentIndex", seq.currentIndex());
        out.put("remainingSites", seq.remainingSites());
        // 站点列表（含每站状态/负载/到达时间/投放时间）
        List<Map<String, Object>> sites = new ArrayList<>();
        for (DeliverySite s : seq.sites()) {
            Map<String, Object> site = new LinkedHashMap<>();
            site.put("index", s.index());
            site.put("lat", s.lat());
            site.put("lon", s.lon());
            site.put("alt", s.alt());
            site.put("payloadId", s.payloadId());
            site.put("payloadWeightKg", s.payloadWeightKg());
            site.put("payloadVolumeL", s.payloadVolumeL());
            site.put("dropAccuracyM", s.dropAccuracyM());
            site.put("state", s.state().name());
            site.put("arriveTimeMs", s.arriveTimeMs());
            site.put("dropTimeMs", s.dropTimeMs());
            sites.add(site);
        }
        out.put("sites", sites);
        return out;
    }

    /** 控制配送任务（推进/跳过站点）。 */
    @PostMapping("/{id}/control")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> control(@PathVariable("id") int id,
                                       @RequestBody ControlRequest body) {
        Map<Integer, DeliveryService.AckResult> results =
                deliveryService.control(id, body.action);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", id);
        out.put("results", results);
        return out;
    }

    /** 查询当前负载清单（FR-34）。 */
    @GetMapping("/{id}/payload")
    public Map<String, Object> payload(@PathVariable("id") int id) {
        DeliverySequence seq = deliveryService.sequence(id);
        if (seq == null) {
            throw new NotFoundException("delivery " + id + " not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", id);
        // 当前站点负载（currentIndex 指向的站点）
        List<Map<String, Object>> payloads = new ArrayList<>();
        double totalWeight = 0;
        double totalVolume = 0;
        double weightedCog = 0;
        for (DeliverySite s : seq.sites()) {
            if (s.state() == DeliverySiteState.EN_ROUTE) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("payloadId", s.payloadId());
                p.put("weightKg", s.payloadWeightKg());
                p.put("volumeL", s.payloadVolumeL());
                payloads.add(p);
                totalWeight += s.payloadWeightKg();
                totalVolume += s.payloadVolumeL();
            }
        }
        out.put("payloads", payloads);
        out.put("totalWeight", totalWeight);
        out.put("totalVolume", totalVolume);
        // TODO: combinedCenterOfGravity 需要 payload 在机舱中的位置数据才能计算加权重心
        out.put("combinedCenterOfGravity", 0);
        return out;
    }

    /**
     * 按需触发机载负载上报（MAV_CMD 30085 生产者，边界清零 2026-10-05）。
     * <p>
     * 向配送任务的目标机下发 MAV_CMD_NEXUS_PAYLOAD_QUERY(30085)；机载
     * （drone-sim {@code handlePayloadQuery}）立即回传一帧 PAYLOAD_STATUS(30006)，
     * 作为 1Hz 周期遥测之外的按需冗余触发。云端命令结果同步返回。
     */
    @PostMapping("/{id}/payload/query")
    @RequireRole(Role.OPERATOR)
    public Map<String, Object> queryPayload(@PathVariable("id") int id) {
        DeliverySequence seq = deliveryService.sequence(id);
        if (seq == null) {
            throw new NotFoundException("delivery " + id + " not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", id);
        out.put("sysid", seq.targetSysid());
        try {
            int result = commands.command(seq.targetSysid(),
                    MavEnums.MAV_CMD_NEXUS_PAYLOAD_QUERY, 0, 0, 0, 0, 0, 0, 0);
            out.put("status", "ok");
            out.put("result", result);
        } catch (DroneCommandService.CommandException e) {
            log.warn("payload query failed for delivery={} sysid={}: {}",
                    id, seq.targetSysid(), e.getMessage());
            out.put("status", "error");
            out.put("result", e.getMessage());
        }
        return out;
    }

    /** 无人机离线 → HTTP 409。 */
    @ExceptionHandler(SprayTaskService.DroneOfflineException.class)
    public ResponseEntity<Object> droneOffline(SprayTaskService.DroneOfflineException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
    }

    /** 控制请求体 DTO。 */
    public static final class ControlRequest {
        public String action;  // START / DROP / SKIP / RESET / FINISH
    }
}