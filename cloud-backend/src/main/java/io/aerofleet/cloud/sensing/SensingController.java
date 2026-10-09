package io.aerofleet.cloud.sensing;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 侦测态势 REST（E2/E4）：非合作目标航迹查询与来源清单。
 * <p>
 * 权限：读 OBSERVER。**无任何反制控制端点**——只做侦测态势（spec §4 立场）。
 */
@RestController
@RequestMapping("/api/v1/sensing")
public class SensingController {

    private final SensingTrackService service;

    public SensingController(SensingTrackService service) {
        this.service = service;
    }

    /** 航迹列表：?source=COUNTER_DRONE_RADAR|FIVE_G_SENSING、?alertOnly=true。 */
    @GetMapping("/tracks")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> tracks(
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "alertOnly", defaultValue = "false") boolean alertOnly) {
        List<Map<String, Object>> list = service.tracks(source, alertOnly);
        return ResponseEntity.ok(Map.of(
                "count", list.size(),
                "alerts", service.alertCount(),
                "tracks", list));
    }

    /** 来源清单。 */
    @GetMapping("/sources")
    @RequireRole(Role.OBSERVER)
    public List<Map<String, Object>> sources() {
        return service.sources();
    }
}
