package io.aerofleet.cloud.health;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * 无人机健康管理 REST API（P1-2 健康管理与预测性维护）。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code GET /api/health/{sysid}} — 获取单机健康评分</li>
 *   <li>{@code GET /api/health/fleet} — 获取机队健康总览</li>
 *   <li>{@code GET /api/health/{sysid}/history} — 获取健康评分历史</li>
 *   <li>{@code GET /api/health/{sysid}/components/{component}} — 获取单部件详情</li>
 *   <li>{@code GET /api/health/warnings} — 获取所有健康告警</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/health")
@Tag(name = "Health", description = "无人机健康管理 REST API：健康评分查询、机队总览、历史趋势、部件详情、告警")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final HealthMonitorService monitorService;
    private final DeviceRegistry registry;

    public HealthController(HealthMonitorService monitorService, DeviceRegistry registry) {
        this.monitorService = monitorService;
        this.registry = registry;
    }

    /**
     * 获取单机最新健康评分。
     *
     * @param sysid 无人机 systemId
     * @return 健康评分（含各部件评分与总体分数/等级）
     */
    @Operation(summary = "获取单机健康评分", description = "返回指定无人机的最新健康评分，包含总体分数、等级与各部件评分。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "健康评分"),
        @ApiResponse(responseCode = "404", description = "无人机未注册或尚无评分")
    })
    @GetMapping("/{sysid}")
    public HealthScore getHealth(@PathVariable("sysid") int sysid) {
        requireRegistered(sysid);
        HealthScore score = monitorService.getLatest(sysid);
        if (score == null) {
            throw new NotFoundException("no health score for sysid " + sysid);
        }
        return score;
    }

    /**
     * 获取机队健康总览（所有已评分无人机的最新评分列表）。
     */
    @Operation(summary = "获取机队健康总览", description = "返回所有已评分无人机的最新健康评分列表。")
    @GetMapping("/fleet")
    public List<HealthScore> getFleetHealth() {
        List<HealthScore> fleet = monitorService.getFleetScores();
        log.debug("Fleet health: {} drones", fleet.size());
        return fleet;
    }

    /**
     * 获取单机健康评分历史（按时间升序）。
     *
     * @param sysid 无人机 systemId
     * @return 健康评分历史列表
     */
    @Operation(summary = "获取健康评分历史", description = "返回指定无人机的健康评分历史，按时间升序排列。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "历史评分列表"),
        @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @GetMapping("/{sysid}/history")
    public List<HealthScore> getHistory(@PathVariable("sysid") int sysid) {
        requireRegistered(sysid);
        return monitorService.getHistory(sysid);
    }

    /**
     * 获取单部件最新评分详情。
     *
     * @param sysid     无人机 systemId
     * @param component 部件类型名称（BATTERY/MOTOR/VIBRATION/TEMPERATURE/COMMUNICATION/IMU/GPS）
     * @return 部件评分详情
     */
    @Operation(summary = "获取单部件详情", description = "返回指定无人机某部件的最新评分、状态、原始指标与维护建议。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "部件评分详情"),
        @ApiResponse(responseCode = "404", description = "无人机未注册或部件无评分")
    })
    @GetMapping("/{sysid}/components/{component}")
    public ComponentScore getComponent(@PathVariable("sysid") int sysid,
                                        @PathVariable("component") String component) {
        requireRegistered(sysid);
        ComponentType type;
        try {
            type = ComponentType.valueOf(component.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("unknown component type: " + component);
        }
        ComponentScore cs = monitorService.getComponent(sysid, type);
        if (cs == null) {
            throw new NotFoundException("no score for sysid " + sysid + " component " + type);
        }
        return cs;
    }

    /**
     * 获取所有健康告警（状态为 WARNING 或 CRITICAL 的部件）。
     */
    @Operation(summary = "获取所有健康告警", description = "返回机队中所有状态为 WARNING 或 CRITICAL 的部件评分列表。")
    @GetMapping("/warnings")
    public List<ComponentScore> getWarnings() {
        return monitorService.getWarnings();
    }

    private void requireRegistered(int sysid) {
        if (registry.get(sysid) == null) {
            throw new NotFoundException("unknown drone sysid " + sysid);
        }
    }
}