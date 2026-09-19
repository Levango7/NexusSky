package io.aerofleet.cloud.tracking;

import io.aerofleet.cloud.gateway.DeviceRegistry;
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
 * 无人机遗失辅助查找 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code GET /api/tracking/{sysid}/track?limit=N} — 获取飞行轨迹（最近 N 条）</li>
 *   <li>{@code GET /api/tracking/{sysid}/last-known} — 获取最后已知位置</li>
 *   <li>{@code GET /api/tracking/lost} — 获取失联无人机列表</li>
 *   <li>{@code GET /api/tracking/{sysid}/search-guide} — 获取辅助查找信息</li>
 * </ul>
 * <p>
 * 认证由 {@link io.aerofleet.cloud.security.SecurityConfig} 统一处理。
 */
@RestController
@RequestMapping("/api/tracking")
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