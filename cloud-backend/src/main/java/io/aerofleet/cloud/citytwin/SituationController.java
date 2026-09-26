package io.aerofleet.cloud.citytwin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 实时态势叠加 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code GET /api/city-twin/situation/current} — 获取当前态势</li>
 *   <li>{@code GET /api/city-twin/situation/history} — 获取历史态势（参数：from, to）</li>
 *   <li>{@code GET /api/city-twin/situation/drones} — 获取所有无人机位置</li>
 *   <li>{@code GET /api/city-twin/situation/alerts} — 获取所有告警标记</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/city-twin/situation")
@Tag(name = "CityTwin-Situation", description = "实时态势叠加：当前态势、历史态势、无人机位置、告警标记")
public class SituationController {

    private static final Logger log = LoggerFactory.getLogger(SituationController.class);

    private final SituationOverlayService situationOverlayService;

    public SituationController(SituationOverlayService situationOverlayService) {
        this.situationOverlayService = situationOverlayService;
    }

    @GetMapping("/current")
    @Operation(summary = "获取当前态势", description = "返回当前时刻的完整态势快照，包含无人机、车辆、人员、告警")
    public RealtimeSituation getCurrentSituation() {
        log.debug("Getting current situation");
        return situationOverlayService.getCurrentSituation();
    }

    @GetMapping("/history")
    @Operation(summary = "获取历史态势", description = "按时间范围 [from, to] 查询历史态势快照")
    public List<RealtimeSituation> getHistory(
            @RequestParam(value = "from", required = false, defaultValue = "0") long from,
            @RequestParam(value = "to", required = false, defaultValue = "0") long to) {
        long end = to == 0 ? System.currentTimeMillis() : to;
        log.debug("Getting situation history: from={} to={}", from, end);
        return situationOverlayService.getSituationsBetween(from, end);
    }

    @GetMapping("/drones")
    @Operation(summary = "获取所有无人机位置", description = "返回所有已注册无人机的实时位置信息")
    public List<DronePosition> getDronePositions() {
        log.debug("Getting drone positions");
        return situationOverlayService.getDronePositions();
    }

    @GetMapping("/alerts")
    @Operation(summary = "获取所有告警标记", description = "返回当前所有告警标记列表")
    public List<AlertMarker> getAlerts() {
        log.debug("Getting alert markers");
        return situationOverlayService.getAlertMarkers();
    }
}