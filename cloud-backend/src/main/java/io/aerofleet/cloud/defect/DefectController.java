package io.aerofleet.cloud.defect;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 缺陷报告与工单闭环 REST（F4）。
 * <p>
 * 权限：人工立案/状态裁决=OPERATOR，工单流转/复检=OPERATOR，读/报告=OBSERVER。
 * 异常映射：未知实体 404；非法状态迁移 409；参数问题 400。
 */
@RestController
@RequestMapping("/api/v1/defects")
public class DefectController {

    private final DefectService defects;
    private final WorkOrderService workOrders;
    private final DefectReportService reports;

    public DefectController(DefectService defects, WorkOrderService workOrders,
                            DefectReportService reports) {
        this.defects = defects;
        this.workOrders = workOrders;
        this.reports = reports;
    }

    // ------------------------------------------------------------------
    // 缺陷
    // ------------------------------------------------------------------

    @PostMapping
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> createDefect(@RequestBody Map<String, Object> body) {
        String kind = str(body, "kind");
        Object latO = body.get("lat");
        Object lonO = body.get("lon");
        if (kind == null || !(latO instanceof Number lat) || !(lonO instanceof Number lon)) {
            return ResponseEntity.badRequest().body(error("missing/bad fields: kind, lat, lon"));
        }
        double confidence = body.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
        try {
            DefectEntity d = defects.createManual(kind, lat.doubleValue(), lon.doubleValue(),
                    confidence, str(body, "note"), null);
            return ResponseEntity.status(201).body(defectView(d));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @GetMapping
    @RequireRole(Role.OBSERVER)
    public List<Map<String, Object>> listDefects(@RequestParam(value = "status", required = false) String status) {
        return defects.list(status).stream().map(DefectController::defectView).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> getDefect(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(defectView(defects.get(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    /** 缺陷状态裁决：OPEN/CONFIRMED/DISMISSED。 */
    @PostMapping("/{id}/status")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> setDefectStatus(@PathVariable Long id,
                                                               @RequestBody Map<String, Object> body) {
        String status = str(body, "status");
        if (status == null) {
            return ResponseEntity.badRequest().body(error("missing field 'status'"));
        }
        try {
            return ResponseEntity.ok(defectView(defects.setStatus(id, status, str(body, "note"))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 工单
    // ------------------------------------------------------------------

    @PostMapping("/work-orders")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> createWorkOrder(@RequestBody Map<String, Object> body) {
        Object idsRaw = body.get("defectIds");
        if (!(idsRaw instanceof List<?> rawIds) || rawIds.isEmpty()) {
            return ResponseEntity.badRequest().body(error("missing field 'defectIds' (non-empty array)"));
        }
        List<Long> defectIds;
        try {
            defectIds = rawIds.stream().map(v -> ((Number) v).longValue()).toList();
        } catch (ClassCastException e) {
            return ResponseEntity.badRequest().body(error("defectIds must be numbers"));
        }
        try {
            WorkOrderEntity wo = workOrders.create(str(body, "title"), str(body, "note"), defectIds, null);
            return ResponseEntity.status(201).body(workOrderView(wo, defectIds));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        }
    }

    @GetMapping("/work-orders")
    @RequireRole(Role.OBSERVER)
    public List<Map<String, Object>> listWorkOrders() {
        return workOrders.list().stream()
                .map(w -> workOrderView(w, workOrders.defectIdsOf(w.id)))
                .collect(Collectors.toList());
    }

    /** 工单流转：action = dispatch/start/resolve/cancel。 */
    @PostMapping("/work-orders/{id}/transition")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> transition(@PathVariable Long id,
                                                           @RequestBody Map<String, Object> body) {
        String action = str(body, "action");
        if (action == null) {
            return ResponseEntity.badRequest().body(error("missing field 'action'"));
        }
        WorkOrderStateMachine.Transition t;
        try {
            t = WorkOrderStateMachine.Transition.valueOf(action.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("unknown action: " + action
                    + " (dispatch/start/resolve/cancel)"));
        }
        try {
            WorkOrderEntity wo = workOrders.transition(id, t, str(body, "note"));
            return ResponseEntity.ok(workOrderView(wo, workOrders.defectIdsOf(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        } catch (IllegalWorkOrderTransitionException e) {
            return ResponseEntity.status(409).body(error(e.getMessage()));
        }
    }

    /** 复检（智能诊断闭环）：真拍一张 + 逐缺陷比对。 */
    @PostMapping("/work-orders/{id}/verify")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> verify(@PathVariable Long id,
                                                      @RequestBody Map<String, Object> body) {
        if (!(body.get("sysid") instanceof Number n)) {
            return ResponseEntity.badRequest().body(error("missing field 'sysid'"));
        }
        try {
            return ResponseEntity.ok(workOrders.verify(id, n.intValue()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(error(e.getMessage()));
        } catch (IllegalWorkOrderTransitionException e) {
            return ResponseEntity.status(409).body(error(e.getMessage()));
        } catch (Exception e) {
            // captureAndLocate 自身把飞控异常包成 Map.of(status=error)（F1 语义），
            // 只有非预期异常会到这里
            return ResponseEntity.status(502).body(error("verify capture failed: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 报告导出
    // ------------------------------------------------------------------

    @GetMapping("/report")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<?> report(
            @RequestParam(value = "from", defaultValue = "0") long from,
            @RequestParam(value = "to", defaultValue = "9223372036854775807") long to,
            @RequestParam(value = "format", defaultValue = "json") String format) {
        return switch (format) {
            case "csv" -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                    .header("Content-Disposition", "attachment; filename=defects.csv")
                    .body(reports.toCsv(from, to));
            case "md" -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/markdown; charset=utf-8"))
                    .header("Content-Disposition", "attachment; filename=defects.md")
                    .body(reports.toMarkdown(from, to));
            case "json" -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(reports.build(from, to));
            default -> ResponseEntity.badRequest().body(error("unknown format: " + format
                    + " (json/csv/md)"));
        };
    }

    // ------------------------------------------------------------------

    static Map<String, Object> defectView(DefectEntity d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("kind", d.kind);
        m.put("severity", d.severity.name());
        m.put("status", d.status);
        m.put("confidence", d.confidence);
        m.put("lat", d.lat);
        m.put("lon", d.lon);
        m.put("source", d.source);
        m.put("note", d.note);
        m.put("lastSeenAt", d.lastSeenAt);
        m.put("createdAt", d.createdAt);
        return m;
    }

    static Map<String, Object> workOrderView(WorkOrderEntity w, List<Long> defectIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id);
        m.put("title", w.title);
        m.put("status", w.status);
        m.put("defectIds", defectIds);
        m.put("note", w.note);
        m.put("cancelReason", w.cancelReason);
        m.put("verifiedAt", w.verifiedAt);
        m.put("verifiedDetail", w.verifiedDetail);
        m.put("createdAt", w.createdAt);
        m.put("updatedAt", w.updatedAt);
        return m;
    }

    private static Map<String, Object> error(String message) {
        return Map.of("status", "error", "result", message);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
