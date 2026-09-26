package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.flightlog.FlightLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Flight-log query API.
 *
 *   GET /api/v1/flightlog?day=2026-09-13&type=alert&sysid=1&limit=100
 *
 * All parameters optional: day defaults to today, type/sysid filter, limit
 * keeps the most recent N entries. Types: telemetry | alert | mission |
 * connectivity.
 */
@RestController
@RequestMapping("/api/v1/flightlog")
public class FlightLogController {

    private final FlightLogService flightLog;

    public FlightLogController(FlightLogService flightLog) {
        this.flightLog = flightLog;
    }

    @GetMapping
    public List<Map<String, Object>> query(
            @RequestParam(value = "day", required = false) String day,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "sysid", required = false) Integer sysid,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        LocalDate d = day != null ? LocalDate.parse(day) : LocalDate.now();
        int bounded = Math.max(1, Math.min(5000, limit));
        return flightLog.query(d, type, sysid, bounded);
    }

    /** Persisted track of one drone for one day (from telemetry lines). */
    @GetMapping("/track")
    public List<Map<String, Object>> track(
            @RequestParam(value = "day", required = false) String day,
            @RequestParam("sysid") int sysid) {
        LocalDate d = day != null ? LocalDate.parse(day) : LocalDate.now();
        return flightLog.trackFor(d, sysid).stream()
                .map(p -> Map.<String, Object>of("lat", p.lat, "lon", p.lon,
                        "alt", p.alt, "ts", p.ts))
                .toList();
    }
}
