package io.aerofleet.cloud.report;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 运营报表 REST（E6）：架次/时长/里程/能耗/成本核算——物流与植保结算刚需。
 * <p>
 * 读权限 OBSERVER；窗口上限 31 天（聚合在内存，防全表扫描）。
 */
@RestController
@RequestMapping("/api/v1/operations")
public class OperationsReportController {

    private static final Duration MAX_WINDOW = Duration.ofDays(31);

    private final OperationsReportService service;

    public OperationsReportController(OperationsReportService service) {
        this.service = service;
    }

    @GetMapping("/report")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> report(
            @RequestParam(value = "from", required = false) String fromIso,
            @RequestParam(value = "to", required = false) String toIso,
            @RequestParam(value = "groupBy", defaultValue = "day") String groupBy) {
        Instant to = toIso == null ? Instant.now() : Instant.parse(toIso);
        Instant from = fromIso == null ? to.minus(Duration.ofDays(7)) : Instant.parse(fromIso);
        if (!from.isBefore(to)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "result", "'from' must be before 'to'"));
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "result", "window exceeds 31 days (in-memory aggregation)"));
        }
        if (!groupBy.equals("day") && !groupBy.equals("drone") && !groupBy.equals("tenant")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "result", "groupBy must be day|drone|tenant"));
        }
        try {
            return ResponseEntity.ok(service.report(from, to, groupBy));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "error", "result", "report failed: " + e.getMessage()));
        }
    }
}
