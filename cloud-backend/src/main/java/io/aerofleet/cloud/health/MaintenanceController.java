package io.aerofleet.cloud.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * 维护管理 REST API（P1-2 维护管理）。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code GET /api/maintenance/records} — 查询维护记录（支持按 sysid/status 筛选）</li>
 *   <li>{@code POST /api/maintenance/records} — 创建维护记录</li>
 *   <li>{@code PUT /api/maintenance/records/{id}} — 更新维护记录</li>
 *   <li>{@code GET /api/maintenance/predictions} — 获取所有预测性维护建议</li>
 *   <li>{@code GET /api/maintenance/predictions/{sysid}} — 获取单机预测性维护建议</li>
 *   <li>{@code GET /api/maintenance/schedule} — 获取维护计划（按时间排序）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/maintenance")
@Tag(name = "Maintenance", description = "维护管理 REST API：维护记录 CRUD、预测性维护建议、维护计划")
public class MaintenanceController {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceController.class);

    private final PredictiveMaintenanceService predictionService;

    /** 维护记录存储：id -> record（内存实现，后续可替换为 JPA）。 */
    private final Map<String, MaintenanceRecord> records = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    public MaintenanceController(PredictiveMaintenanceService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * 查询维护记录（支持按 sysid / status 筛选）。
     *
     * @param sysid  无人机 systemId 筛选（可选）
     * @param status 维护状态筛选（可选）
     * @return 维护记录列表
     */
    @Operation(summary = "查询维护记录", description = "支持按 sysid 和 status 筛选，不传参数返回全部记录。")
    @GetMapping("/records")
    public List<MaintenanceRecord> getRecords(
            @RequestParam(value = "sysid", required = false) Integer sysid,
            @RequestParam(value = "status", required = false) String status) {
        return records.values().stream()
                .filter(r -> sysid == null || r.getSysid() == sysid)
                .filter(r -> status == null || r.getStatus().name().equalsIgnoreCase(status))
                .sorted(Comparator.comparing(MaintenanceRecord::getId))
                .collect(Collectors.toList());
    }

    /**
     * 创建维护记录。
     *
     * @param body 维护记录（id 可空，自动生成）
     * @return 创建后的维护记录
     */
    @Operation(summary = "创建维护记录", description = "创建一条维护记录，id 自动生成。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "创建成功"),
        @ApiResponse(responseCode = "400", description = "请求体无效")
    })
    @PostMapping("/records")
    public MaintenanceRecord createRecord(@RequestBody MaintenanceRecord body) {
        if (body == null) {
            throw new BadRequestException("maintenance record body is required");
        }
        if (body.getSysid() <= 0) {
            throw new BadRequestException("sysid must be positive");
        }
        if (body.getComponentType() == null) {
            throw new BadRequestException("componentType is required");
        }
        if (body.getMaintenanceType() == null) {
            throw new BadRequestException("maintenanceType is required");
        }
        if (body.getStatus() == null) {
            body.setStatus(MaintenanceRecord.Status.SCHEDULED);
        }
        String id = body.getId() != null ? body.getId() : nextId();
        body.setId(id);
        if (body.getScheduledDate() == null) {
            body.setScheduledDate(LocalDate.now().plusDays(1));
        }
        records.put(id, body);
        log.info("Maintenance record created: id={} sysid={} component={} type={}",
                id, body.getSysid(), body.getComponentType(), body.getMaintenanceType());
        return body;
    }

    /**
     * 更新维护记录。
     *
     * @param id  记录 ID
     * @param body 更新内容
     * @return 更新后的维护记录
     */
    @Operation(summary = "更新维护记录", description = "更新指定 ID 的维护记录，可修改状态、技术人员、备注、费用等。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新成功"),
        @ApiResponse(responseCode = "404", description = "记录不存在")
    })
    @PutMapping("/records/{id}")
    public MaintenanceRecord updateRecord(@PathVariable("id") String id,
                                           @RequestBody MaintenanceRecord body) {
        MaintenanceRecord existing = records.get(id);
        if (existing == null) {
            throw new NotFoundException("maintenance record not found: " + id);
        }
        if (body == null) {
            throw new BadRequestException("update body is required");
        }
        // 部分更新：仅覆盖非空字段
        if (body.getMaintenanceType() != null) {
            existing.setMaintenanceType(body.getMaintenanceType());
        }
        if (body.getStatus() != null) {
            existing.setStatus(body.getStatus());
        }
        if (body.getScheduledDate() != null) {
            existing.setScheduledDate(body.getScheduledDate());
        }
        if (body.getCompletedDate() != null) {
            existing.setCompletedDate(body.getCompletedDate());
        }
        if (body.getTechnician() != null) {
            existing.setTechnician(body.getTechnician());
        }
        if (body.getNotes() != null) {
            existing.setNotes(body.getNotes());
        }
        existing.setCost(body.getCost());
        log.info("Maintenance record updated: id={} status={}", id, existing.getStatus());
        return existing;
    }

    /**
     * 获取所有预测性维护建议（机队级）。
     */
    @Operation(summary = "获取所有预测性维护建议", description = "返回机队中所有无人机的预测性维护建议列表。")
    @GetMapping("/predictions")
    public List<MaintenancePrediction> getPredictions() {
        return predictionService.predictAll();
    }

    /**
     * 获取单机预测性维护建议。
     *
     * @param sysid 无人机 systemId
     * @return 维护预测建议列表（按紧迫度排序）
     */
    @Operation(summary = "获取单机预测性维护建议", description = "返回指定无人机的预测性维护建议，按紧迫度排序。")
    @GetMapping("/predictions/{sysid}")
    public List<MaintenancePrediction> getPredictions(@PathVariable("sysid") int sysid) {
        return predictionService.predict(sysid);
    }

    /**
     * 获取维护计划：SCHEDULED 与 IN_PROGRESS 状态的记录，按计划时间升序。
     */
    @Operation(summary = "获取维护计划", description = "返回 SCHEDULED 与 IN_PROGRESS 状态的维护记录，按计划时间升序排列。")
    @GetMapping("/schedule")
    public List<MaintenanceRecord> getSchedule() {
        return records.values().stream()
                .filter(r -> r.getStatus() == MaintenanceRecord.Status.SCHEDULED
                        || r.getStatus() == MaintenanceRecord.Status.IN_PROGRESS)
                .sorted(Comparator.comparing(
                        r -> r.getScheduledDate() == null ? LocalDate.MAX : r.getScheduledDate()))
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private String nextId() {
        return "m-" + idSeq.getAndIncrement();
    }

    /** 测试用：直接注入记录（绕过 POST）。 */
    void putRecord(MaintenanceRecord record) {
        if (record.getId() == null) {
            record.setId(nextId());
        }
        records.put(record.getId(), record);
    }

    /** 测试用：获取存储 Map（不可修改视图）。 */
    Map<String, MaintenanceRecord> allRecords() {
        return new ArrayList<>(records.values()).stream()
                .collect(Collectors.toMap(MaintenanceRecord::getId, r -> r));
    }
}