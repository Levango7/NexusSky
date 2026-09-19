package io.aerofleet.cloud.tracking;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * 无人机追踪与遗失辅助查找 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code GET /api/tracking/{sysid}/track?limit=N} — 获取飞行轨迹（最近 N 条）</li>
 *   <li>{@code GET /api/tracking/{sysid}/replay?from=&to=&limit=N} — 历史轨迹回放（按时间范围查询）</li>
 *   <li>{@code GET /api/tracking/{sysid}/last-known} — 获取最后已知位置</li>
 *   <li>{@code GET /api/tracking/lost} — 获取失联无人机列表</li>
 *   <li>{@code GET /api/tracking/{sysid}/search-guide} — 获取辅助查找信息</li>
 * </ul>
 * <p>
 * 认证由 {@link io.aerofleet.cloud.security.SecurityConfig} 统一处理。
 */
@RestController
@RequestMapping("/api/tracking")
@Tag(name = "Tracking", description = "无人机追踪 REST API：飞行轨迹查询、历史轨迹回放、遗失辅助查找")
public class TrackingController {

    private static final Logger log = LoggerFactory.getLogger(TrackingController.class);

    private final FlightTrackStore trackStore;
    private final LostDroneAlertService alertService;
    private final DeviceRegistry registry;

    public TrackingController(FlightTrackStore trackStore,
                              LostDroneAlertService alertService,
                              DeviceRegistry registry) {
        this.trackStore = trackStore;
        this.alertService = alertService;
        this.registry = registry;
    }

    /**
     * 获取飞行轨迹（按时间升序，最新在末尾）。
     *
     * @param sysid 无人机 systemId
     * @param limit 最多返回 N 条（<=0 表示不限制）
     */
    @GetMapping("/{sysid}/track")
    public List<FlightTrackStore.TrackPoint> getTrack(@PathVariable("sysid") int sysid,
                                                       @RequestParam(value = "limit", required = false) Integer limit) {
        requireRegistered(sysid);
        int n = limit == null ? 0 : limit;
        return trackStore.getTrack(sysid, n);
    }

    /**
     * 历史轨迹回放：按时间范围查询轨迹点（按时间升序返回）。
     * <p>
     * 用于回放指定时间段的飞行轨迹，例如事故复盘、航线核查。
     *
     * @param sysid 无人机 systemId
     * @param from  起始时间戳（epoch ms），0 或不传表示不限起始
     * @param to    结束时间戳（epoch ms），0 或不传表示不限结束
     * @param limit 最多返回 N 条（<=0 表示不限制，默认 1000）
     * @return 轨迹点列表（按时间升序）
     */
    @Operation(summary = "历史轨迹回放", description = "按时间范围 [from, to] 查询轨迹点，按时间升序返回。from=0 表示不限起始，to=0 表示不限结束，limit<=0 表示不限制数量。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "轨迹点列表"),
        @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @GetMapping("/{sysid}/replay")
    public List<FlightTrackStore.TrackPoint> replayTrack(
            @PathVariable("sysid") int sysid,
            @RequestParam(value = "from", required = false, defaultValue = "0") long from,
            @RequestParam(value = "to", required = false, defaultValue = "0") long to,
            @RequestParam(value = "limit", required = false, defaultValue = "1000") int limit) {
        requireRegistered(sysid);
        List<FlightTrackStore.TrackPoint> replay = trackStore.getTrack(sysid, from, to, limit);
        log.debug("Replay track: sysid={} from={} to={} limit={} result={}",
                sysid, from, to, limit, replay.size());
        return replay;
    }

    /**
     * 获取最后已知位置（最新轨迹点）。
     * <p>
     * 返回 null（HTTP 200 + 空 body）当无人机有注册但尚无轨迹。
     */
    @GetMapping("/{sysid}/last-known")
    public FlightTrackStore.TrackPoint getLastKnown(@PathVariable("sysid") int sysid) {
        requireRegistered(sysid);
        return trackStore.getLastKnown(sysid);
    }

    /**
     * 获取失联无人机列表（含告警时间、最后位置、电量等）。
     */
    @GetMapping("/lost")
    public List<LostDroneAlertService.LostAlert> getLostAlerts() {
        return alertService.getLostAlerts();
    }

    /**
     * 获取辅助查找信息：最后位置 + 轨迹方向 + 电量 + 预计坠落范围。
     * <p>
     * 无人机未注册时返回 404。
     */
    @GetMapping("/{sysid}/search-guide")
    public LostDroneAlertService.SearchGuide getSearchGuide(@PathVariable("sysid") int sysid) {
        requireRegistered(sysid);
        LostDroneAlertService.SearchGuide guide = alertService.getSearchGuide(sysid);
        if (guide == null) {
            throw new NotFoundException("no search guide for sysid " + sysid);
        }
        return guide;
    }

    /**
     * 触发一次失联检测扫描（管理端点）。
     * <p>
     * 返回本次扫描新失联的 sysid 列表。
     */
    @GetMapping("/scan")
    public Map<String, Object> scanLostDrones() {
        List<Integer> newlyLost = alertService.checkLostDrones();
        log.info("Lost drone scan: newlyLost={} total={}", newlyLost, alertService.getLostAlerts().size());
        return Map.of(
                "newlyLost", newlyLost,
                "totalLost", alertService.getLostAlerts().size());
    }

    private void requireRegistered(int sysid) {
        if (registry.get(sysid) == null) {
            throw new NotFoundException("unknown drone sysid " + sysid);
        }
    }
}