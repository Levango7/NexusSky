package io.aerofleet.cloud.roc;

import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ROC 一控多机席位 REST（E1）：席位 CRUD、批量指令（逐机结果）、警情驱动建议与确认派飞。
 * <p>
 * 权限：席位管理/警情登记/派飞=OPERATOR，读=OBSERVER。
 * 批量指令**逐机结果**如实返回（部分失败不掩盖，spec R2）。
 */
@RestController
@RequestMapping("/api/v1/roc")
public class RocController {

    private final RocSeatService seats;
    private final IncidentDispatchService incidents;
    private final DroneCommandService commands;

    public RocController(RocSeatService seats, IncidentDispatchService incidents,
                         DroneCommandService commands) {
        this.seats = seats;
        this.incidents = incidents;
        this.commands = commands;
    }

    // ------------------------------------------------------------------
    // R1 席位
    // ------------------------------------------------------------------

    @PostMapping("/seats")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> createSeat(@RequestBody Map<String, Object> body) {
        Object name = body.get("operatorName");
        if (!(name instanceof String s) || s.isBlank()) {
            return ResponseEntity.badRequest().body(error("missing field 'operatorName'"));
        }
        try {
            return ResponseEntity.status(201).body(seats.create(s));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(error(e.getMessage()));
        }
    }

    @GetMapping("/seats")
    @RequireRole(Role.OBSERVER)
    public List<Map<String, Object>> listSeats() {
        return seats.listSeats();
    }

    @GetMapping("/seats/{id}")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> seatView(@PathVariable long id) {
        try {
            return ResponseEntity.ok(seats.view(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    @PutMapping("/seats/{id}/fleet")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> bindFleet(@PathVariable long id,
                                                         @RequestBody Map<String, Object> body) {
        List<Integer> sysids = new ArrayList<>();
        if (body.get("sysids") instanceof List<?> raw) {
            for (Object o : raw) {
                if (!(o instanceof Number n)) {
                    return ResponseEntity.badRequest().body(error("sysids must be numbers"));
                }
                sysids.add(n.intValue());
            }
        }
        try {
            seats.bindFleet(id, sysids);
            return ResponseEntity.ok(seats.view(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // R2 批量指令（逐机结果）
    // ------------------------------------------------------------------

    @PostMapping("/seats/{id}/commands")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> batchCommand(@PathVariable long id,
                                                            @RequestBody Map<String, Object> body) {
        Object actionRaw = body.get("action");
        if (!(actionRaw instanceof String action)) {
            return ResponseEntity.badRequest().body(error("missing field 'action'"));
        }
        List<Integer> sysids = new ArrayList<>();
        if (body.get("sysids") instanceof List<?> raw) {
            for (Object o : raw) {
                if (!(o instanceof Number n)) {
                    return ResponseEntity.badRequest().body(error("sysids must be numbers"));
                }
                sysids.add(n.intValue());
            }
        }
        try {
            List<Integer> targets = seats.targetsOf(id, sysids);
            List<Map<String, Object>> results = new ArrayList<>();
            for (int sysid : targets) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("sysid", sysid);
                try {
                    switch (action) {
                        case "arm" -> commands.arm(sysid);
                        // 批量 takeoff 固定 50m 安全高度：席位级批量操作取保守统一值
                        // （多机同升同一净空层）；单机精调高度走单机指令面板。
                        case "takeoff" -> commands.takeoff(sysid, 50);
                        case "rtl" -> commands.rtl(sysid);
                        case "land" -> commands.command(sysid, 21, 0, 0, 0, 0, 0, 0, 0);
                        default -> throw new IllegalArgumentException(
                                "unknown action: " + action + " (arm/takeoff/rtl/land)");
                    }
                    r.put("result", "ok");
                } catch (Exception e) {
                    // 逐机结果：部分失败如实呈现（指令彼此独立，不整体回滚）
                    r.put("result", "failed");
                    r.put("reason", e.getMessage());
                }
                results.add(r);
            }
            return ResponseEntity.ok(Map.of(
                    "seatId", id, "action", action, "targets", results));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // R3 警情
    // ------------------------------------------------------------------

    @PostMapping("/seats/{id}/incidents")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> reportIncident(@PathVariable long id,
                                                               @RequestBody Map<String, Object> body) {
        Object lat = body.get("lat");
        Object lon = body.get("lon");
        Object priority = body.get("priority");
        if (!(lat instanceof Number la) || !(lon instanceof Number lo)
                || !(priority instanceof String p)) {
            return ResponseEntity.badRequest().body(error("missing fields: lat, lon, priority"));
        }
        String desc = body.get("description") instanceof String d ? d : "";
        try {
            return ResponseEntity.status(201).body(
                    incidents.report(id, la.doubleValue(), lo.doubleValue(), p, desc));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @GetMapping("/seats/{id}/incidents")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<?> listIncidents(@PathVariable long id) {
        try {
            return ResponseEntity.ok(incidents.listIncidents(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    @PostMapping("/seats/{id}/incidents/{incidentId}/dispatch")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> dispatch(@PathVariable long id,
                                                        @PathVariable long incidentId,
                                                        @RequestBody Map<String, Object> body) {
        double holdSec = body.get("holdSec") instanceof Number n ? n.doubleValue() : 30;
        try {
            return ResponseEntity.ok(incidents.dispatch(id, incidentId, holdSec));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(error(e.getMessage()));
        }
    }

    private static Map<String, Object> error(String message) {
        return Map.of("status", "error", "result", message);
    }
}
