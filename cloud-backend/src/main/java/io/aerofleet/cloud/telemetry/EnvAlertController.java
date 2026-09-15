package io.aerofleet.cloud.telemetry;

import io.aerofleet.cloud.flightlog.FlightLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 环境告警查询 API（M0b，FR-23 后端接入）。
 * <p>
 * 复用 {@link FlightLogService} 持久化的告警历史（JSON Lines，type=alert），
 * 提供环境告警专属查询端点。环境告警由 {@link io.aerofleet.cloud.gateway.TelemetryIngestService#onEnvironmentAlert}
 * 接入 AlertBus → FlightLogService.alert 持久化，本端点只读查询。
 * <p>
 * 端点：
 * <pre>
 * GET /api/v1/env-alerts?day=2026-09-16&sysid=1&limit=100
 * </pre>
 * 返回最近 N 条告警（含环境告警 + STATUSTEXT 映射告警，按时间倒序）。
 */
@RestController
@RequestMapping("/api/v1/env-alerts")
public class EnvAlertController {

    private final FlightLogService flightLog;

    public EnvAlertController(FlightLogService flightLog) {
        this.flightLog = flightLog;
    }

    /**
     * 查询告警历史（type=alert）。
     * <p>
     * 所有参数可选：day 默认今天，sysid 过滤特定无人机，limit 限制最近 N 条。
     *
     * @param day   日期（YYYY-MM-DD，默认今天）
     * @param sysid 系统 ID（可选过滤）
     * @param limit 返回条数上限（默认 100，上限 5000）
     * @return 告警列表（每条含 t/type/sysid/severity/text）
     */
    @GetMapping
    public List<Map<String, Object>> query(
            @RequestParam(value = "day", required = false) String day,
            @RequestParam(value = "sysid", required = false) Integer sysid,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        LocalDate d = day != null ? LocalDate.parse(day) : LocalDate.now();
        int bounded = Math.max(1, Math.min(5000, limit));
        return flightLog.query(d, "alert", sysid, bounded);
    }
}