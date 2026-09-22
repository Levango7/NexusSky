package io.aerofleet.cloud.inspection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检报告 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code GET  /api/inspection/reports/{taskId}} — 获取巡检报告</li>
 *   <li>{@code GET  /api/inspection/reports/{taskId}/anomalies} — 获取异常清单</li>
 *   <li>{@code GET  /api/inspection/reports/{taskId}/photos} — 获取照片列表（带 GPS 标注）</li>
 *   <li>{@code POST /api/inspection/reports/{taskId}/export} — 导出报告（JSON/CSV）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/inspection/reports")
@Tag(name = "InspectionReport", description = "巡检报告 REST API：报告查询、异常清单、照片列表、导出")
public class InspectionReportController {

    private static final Logger log = LoggerFactory.getLogger(InspectionReportController.class);

    private final InspectionTaskService taskService;

    public InspectionReportController(InspectionTaskService taskService) {
        this.taskService = taskService;
    }

    /** 导出请求体。 */
    public static final class ExportRequest {
        public String format; // "json" or "csv"
    }

    /** 获取巡检报告。 */
    @Operation(summary = "获取巡检报告")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "报告详情"),
        @ApiResponse(responseCode = "404", description = "任务不存在或报告未生成")
    })
    @GetMapping("/{taskId}")
    public Map<String, Object> getReport(@PathVariable("taskId") String taskId) {
        InspectionReport report = taskService.getReport(taskId);
        return reportToMap(report);
    }

    /** 获取异常清单。 */
    @Operation(summary = "获取异常清单")
    @GetMapping("/{taskId}/anomalies")
    public List<Map<String, Object>> getAnomalies(@PathVariable("taskId") String taskId) {
        List<Anomaly> anomalies = taskService.getAnomalies(taskId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Anomaly a : anomalies) {
            out.add(anomalyToMap(a));
        }
        return out;
    }

    /** 获取照片列表（带 GPS 标注）。 */
    @Operation(summary = "获取照片列表", description = "带 GPS 标注的巡检照片元数据")
    @GetMapping("/{taskId}/photos")
    public List<Map<String, Object>> getPhotos(@PathVariable("taskId") String taskId) {
        List<InspectionPhoto> photos = taskService.getPhotos(taskId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (InspectionPhoto p : photos) {
            out.add(photoToMap(p));
        }
        return out;
    }

    /** 导出报告（JSON/CSV）。 */
    @Operation(summary = "导出报告", description = "支持 JSON 与 CSV 格式导出")
    @PostMapping("/{taskId}/export")
    public ResponseEntity<String> exportReport(@PathVariable("taskId") String taskId,
                                                @RequestBody ExportRequest req) {
        String format = req.format == null ? "json" : req.format.toLowerCase();
        InspectionReport report = taskService.getReport(taskId);
        String content;
        MediaType mediaType;
        if ("csv".equals(format)) {
            content = toCsv(report);
            mediaType = MediaType.parseMediaType("text/csv");
        } else {
            content = toJson(report);
            mediaType = MediaType.APPLICATION_JSON;
        }
        log.info("Export report: taskId={} format={} size={}", taskId, format, content.length());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(content);
    }

    // ---- 辅助方法 ----

    private Map<String, Object> reportToMap(InspectionReport r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.id());
        out.put("taskId", r.taskId());
        out.put("templateId", r.templateId());
        out.put("generatedAt", r.generatedAt());
        out.put("totalPhotos", r.totalPhotos());
        out.put("totalAnomalies", r.totalAnomalies());
        Map<String, Integer> bySev = new LinkedHashMap<>();
        for (Map.Entry<Anomaly.Severity, Integer> e : r.anomaliesBySeverity().entrySet()) {
            bySev.put(e.getKey().name(), e.getValue());
        }
        out.put("anomaliesBySeverity", bySev);
        out.put("routeCoveragePct", r.routeCoveragePct());
        out.put("actualDurationMin", r.actualDurationMin());
        out.put("distanceFlownKm", r.distanceFlownKm());
        out.put("summary", r.summary());
        out.put("recommendations", r.recommendations());
        return out;
    }

    private Map<String, Object> anomalyToMap(Anomaly a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.id());
        out.put("type", a.type());
        out.put("severity", a.severity().name());
        out.put("lat", a.lat());
        out.put("lon", a.lon());
        out.put("photoId", a.photoId());
        out.put("description", a.description());
        out.put("detectedAt", a.detectedAt());
        out.put("confidencePct", a.confidencePct());
        return out;
    }

    private Map<String, Object> photoToMap(InspectionPhoto p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.id());
        out.put("taskId", p.taskId());
        out.put("waypointSeq", p.waypointSeq());
        out.put("lat", p.lat());
        out.put("lon", p.lon());
        out.put("altM", p.altM());
        out.put("headingDeg", p.headingDeg());
        out.put("cameraAngleDeg", p.cameraAngleDeg());
        out.put("capturedAt", p.capturedAt());
        out.put("industryType", p.industryType().name());
        return out;
    }

    private String toJson(InspectionReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(r.id()).append("\",");
        sb.append("\"taskId\":\"").append(r.taskId()).append("\",");
        sb.append("\"templateId\":\"").append(r.templateId()).append("\",");
        sb.append("\"totalPhotos\":").append(r.totalPhotos()).append(",");
        sb.append("\"totalAnomalies\":").append(r.totalAnomalies()).append(",");
        sb.append("\"routeCoveragePct\":").append(r.routeCoveragePct()).append(",");
        sb.append("\"actualDurationMin\":").append(r.actualDurationMin()).append(",");
        sb.append("\"distanceFlownKm\":").append(r.distanceFlownKm()).append(",");
        sb.append("\"summary\":\"").append(escape(r.summary())).append("\",");
        sb.append("\"recommendations\":[");
        for (int i = 0; i < r.recommendations().size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(r.recommendations().get(i))).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String toCsv(InspectionReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("field,value\n");
        sb.append("id,").append(r.id()).append("\n");
        sb.append("taskId,").append(r.taskId()).append("\n");
        sb.append("templateId,").append(r.templateId()).append("\n");
        sb.append("totalPhotos,").append(r.totalPhotos()).append("\n");
        sb.append("totalAnomalies,").append(r.totalAnomalies()).append("\n");
        sb.append("routeCoveragePct,").append(r.routeCoveragePct()).append("\n");
        sb.append("actualDurationMin,").append(r.actualDurationMin()).append("\n");
        sb.append("distanceFlownKm,").append(r.distanceFlownKm()).append("\n");
        sb.append("summary,").append(escape(r.summary())).append("\n");
        for (String rec : r.recommendations()) {
            sb.append("recommendation,").append(escape(rec)).append("\n");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\n", " ");
    }
}