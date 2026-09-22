package io.aerofleet.cloud.citytwin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 历史回放 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code GET /api/city-twin/playback/drones/{sysid}} — 无人机轨迹回放</li>
 *   <li>{@code GET /api/city-twin/playback/alerts} — 告警事件回放</li>
 *   <li>{@code GET /api/city-twin/playback/situation} — 综合态势回放</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/city-twin/playback")
@Tag(name = "CityTwin-Playback", description = "历史回放：无人机轨迹回放、告警事件回放、综合态势回放")
public class PlaybackController {

    private static final Logger log = LoggerFactory.getLogger(PlaybackController.class);

    private final SituationOverlayService situationOverlayService;

    public PlaybackController(SituationOverlayService situationOverlayService) {
        this.situationOverlayService = situationOverlayService;
    }

    @GetMapping("/drones/{sysid}")
    @Operation(summary = "无人机轨迹回放", description = "按时间范围 [from, to] 回放指定无人机的飞行轨迹")
    public List<DronePosition> playbackDroneTrack(
            @PathVariable("sysid") int sysid,
            @RequestParam(value = "from", required = false, defaultValue = "0") long from,
            @RequestParam(value = "to", required = false, defaultValue = "0") long to) {
        long end = to == 0 ? System.currentTimeMillis() : to;
        log.debug("Playback drone track: sysid={} from={} to={}", sysid, from, end);
        // 从历史态势中提取该无人机的轨迹
        List<RealtimeSituation> situations = situationOverlayService.getSituationsBetween(from, end);
        List<DronePosition> track = new java.util.ArrayList<>();
        for (RealtimeSituation sit : situations) {
            if (sit.getDrones() != null) {
                for (DronePosition drone : sit.getDrones()) {
                    if (drone.getSysid() == sysid) {
                        track.add(drone);
                    }
                }
            }
        }
        return track;
    }

    @GetMapping("/alerts")
    @Operation(summary = "告警事件回放", description = "按时间范围 [from, to] 回放告警事件")
    public List<AlertMarker> playbackAlerts(
            @RequestParam(value = "from", required = false, defaultValue = "0") long from,
            @RequestParam(value = "to", required = false, defaultValue = "0") long to) {
        long end = to == 0 ? System.currentTimeMillis() : to;
        log.debug("Playback alerts: from={} to={}", from, end);
        // 从历史态势中提取告警标记
        List<RealtimeSituation> situations = situationOverlayService.getSituationsBetween(from, end);
        List<AlertMarker> alerts = new java.util.ArrayList<>();
        for (RealtimeSituation sit : situations) {
            if (sit.getAlerts() != null) {
                alerts.addAll(sit.getAlerts());
            }
        }
        return alerts;
    }

    @GetMapping("/situation")
    @Operation(summary = "综合态势回放", description = "按时间范围 [from, to] 回放综合态势快照序列")
    public List<RealtimeSituation> playbackSituation(
            @RequestParam(value = "from", required = false, defaultValue = "0") long from,
            @RequestParam(value = "to", required = false, defaultValue = "0") long to) {
        long end = to == 0 ? System.currentTimeMillis() : to;
        log.debug("Playback situation: from={} to={}", from, end);
        return situationOverlayService.getSituationsBetween(from, end);
    }
}