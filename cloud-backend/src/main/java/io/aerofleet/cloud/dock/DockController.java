package io.aerofleet.cloud.dock;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 机巢管控 REST（F2，spec R1-R5）。
 * <p>
 * 权限：注册/定时任务管理=ADMIN，动作命令与 OSD 摄取=OPERATOR，读=OBSERVER。
 * 异常映射：未知机巢 404；非法状态迁移与重复 SN 409；通道故障 504。
 */
@RestController
@RequestMapping("/api/v1/docks")
public class DockController {

    private static final Logger log = LoggerFactory.getLogger(DockController.class);

    private final DockService docks;
    private final DockMetricsService metrics;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public DockController(DockService docks, DockMetricsService metrics,
                          com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.docks = docks;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // 机巢注册与查询
    // ------------------------------------------------------------------

    @PostMapping
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        String name = str(body, "name");
        String sn = str(body, "sn");
        String model = str(body, "model");
        Double lat = num(body, "lat");
        Double lon = num(body, "lon");
        Integer droneSysid = body.get("droneSysid") instanceof Number n ? n.intValue() : null;
        try {
            DockEntity d = docks.register(name, model, sn, lat, lon, droneSysid, null);
            return ResponseEntity.status(201).body(view(d));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @GetMapping
    @RequireRole(Role.OBSERVER)
    public List<Map<String, Object>> list() {
        return docks.list().stream().map(DockController::view).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        try {
            DockEntity d = docks.get(id);
            Map<String, Object> body = view(d);
            body.put("recentTransitions", docks.recentLog(id, 20).stream()
                    .map(e -> Map.of(
                            "from", e.fromState == null ? "" : e.fromState.name(),
                            "to", e.toState.name(),
                            "reason", e.reason == null ? "" : e.reason,
                            "ts", e.ts))
                    .collect(Collectors.toList()));
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 命令下发
    // ------------------------------------------------------------------

    @PostMapping("/{id}/commands")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> command(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        String method = str(body, "method");
        if (method == null) {
            return ResponseEntity.badRequest().body(error("missing field 'method'"));
        }
        DockCommand cmd;
        try {
            cmd = DockCommand.fromMethod(method);
        } catch (IllegalArgumentException e) {
            // 未知命令名是请求侧问题（400），与"机巢不存在"（404）分开
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
        try {
            return ResponseEntity.ok(docks.sendCommand(id, cmd));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        } catch (IllegalDockTransitionException e) {
            return ResponseEntity.status(409).body(error(e.getMessage()));
        } catch (DockGateway.DockGatewayException e) {
            return ResponseEntity.status(504).body(error(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // OSD 摄取（机巢 → 云）
    // ------------------------------------------------------------------

    @PostMapping("/osd")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> osd(@RequestBody Map<String, Object> body) {
        String sn = str(body, "sn");
        if (sn == null) {
            return ResponseEntity.badRequest().body(error("missing field 'sn'"));
        }
        try {
            String rawState = str(body, "state");
            DockState reported = null;
            if (rawState != null && !rawState.isBlank()) {
                try {
                    reported = DockState.valueOf(rawState);
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(error("unknown state: " + rawState));
                }
            }
            Double temperature = num(body, "temperature");
            Integer batteryPct = body.get("batteryPct") instanceof Number n ? n.intValue() : null;
            Integer dockedDrone = body.get("droneSysid") instanceof Number n ? n.intValue() : null;
            DockEntity d = docks.ingestOsd(sn, reported, temperature, batteryPct, dockedDrone);
            return ResponseEntity.ok(view(d));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 度量
    // ------------------------------------------------------------------

    @GetMapping("/{id}/metrics")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> metrics(@PathVariable Long id,
                                                       @RequestParam(value = "days", defaultValue = "7") int days) {
        try {
            return ResponseEntity.ok(metrics.metrics(id, days));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 定时任务（spec R4）
    // ------------------------------------------------------------------

    @PostMapping("/{id}/schedules")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> createSchedule(@PathVariable Long id,
                                                              @RequestBody Map<String, Object> body) {
        String name = str(body, "name");
        String cron = str(body, "cron");
        if (name == null || cron == null) {
            return ResponseEntity.badRequest().body(error("missing field 'name' or 'cron'"));
        }
        if (!org.springframework.scheduling.support.CronExpression.isValidExpression(cron)) {
            // cron 语法错误是请求侧问题（400）；机巢不存在才是 404——两者不能混
            return ResponseEntity.badRequest().body(error("invalid cron expression: " + cron));
        }
        Object wp = body.get("waypoints");
        String wpJson = null;
        if (wp != null) {
            try {
                wpJson = mapper.writeValueAsString(wp);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(error("waypoints not serializable"));
            }
        }
        boolean enabled = !(body.get("enabled") instanceof Boolean b) || b;
        try {
            DockScheduleEntity s = docks.createSchedule(id, name, cron, wpJson, enabled);
            return ResponseEntity.status(201).body(scheduleView(s));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/schedules")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<List<Map<String, Object>>> listSchedules(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(docks.listSchedules(id).stream()
                    .map(DockController::scheduleView).collect(Collectors.toList()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).build();
        }
    }

    @PostMapping("/{id}/schedules/{sid}/enable")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> enableSchedule(@PathVariable Long id,
                                                              @PathVariable Long sid,
                                                              @RequestBody Map<String, Object> body) {
        boolean enabled = !(body.get("enabled") instanceof Boolean b) || b;
        try {
            return ResponseEntity.ok(scheduleView(docks.setScheduleEnabled(id, sid, enabled)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/schedules/{sid}")
    @RequireRole(Role.ADMIN)
    public ResponseEntity<Map<String, Object>> deleteSchedule(@PathVariable Long id,
                                                              @PathVariable Long sid) {
        try {
            docks.deleteSchedule(id, sid);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------

    static Map<String, Object> view(DockEntity d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("name", d.name);
        m.put("sn", d.sn);
        m.put("model", d.model);
        m.put("state", d.state.name());
        m.put("doorOpen", d.state.doorOpen());
        m.put("temperatureC", d.temperatureC);
        m.put("batteryPct", d.batteryPct);
        m.put("droneSysid", d.droneSysid);
        m.put("lastHeartbeatMs", d.lastHeartbeatMs);
        m.put("tempWarnC", d.tempWarnC);
        m.put("tempCritC", d.tempCritC);
        m.put("rebootPending", d.rebootUntilMs > System.currentTimeMillis());
        return m;
    }

    private static Map<String, Object> error(String message) {
        return Map.of("status", "error", "result", message == null ? "error" : message);
    }

    static Map<String, Object> scheduleView(DockScheduleEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id);
        m.put("dockId", s.dockId);
        m.put("name", s.name);
        m.put("cron", s.cronExpr);
        m.put("waypoints", s.waypoints);
        m.put("enabled", s.enabled);
        m.put("lastRunAt", s.lastRunAt);
        m.put("lastResult", s.lastResult);
        m.put("nextDueMs", s.nextDueMs);
        return m;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static Double num(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
