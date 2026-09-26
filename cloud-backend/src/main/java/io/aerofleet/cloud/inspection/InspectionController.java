package io.aerofleet.cloud.inspection;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无人机集群智能巡检 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code POST /api/inspection/tasks} — 创建巡检任务</li>
 *   <li>{@code GET  /api/inspection/tasks} — 列出巡检任务（支持状态筛选）</li>
 *   <li>{@code GET  /api/inspection/tasks/{id}} — 获取任务详情</li>
 *   <li>{@code POST /api/inspection/tasks/{id}/start} — 启动巡检</li>
 *   <li>{@code POST /api/inspection/tasks/{id}/abort} — 中止巡检</li>
 *   <li>{@code GET  /api/inspection/tasks/{id}/progress} — 查询进度</li>
 *   <li>{@code GET  /api/inspection/templates} — 列出巡检模板预设</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/inspection")
@Tag(name = "Inspection", description = "无人机集群智能巡检 REST API：任务管理、航线规划、异常检测、报告生成")
public class InspectionController {

    private static final Logger log = LoggerFactory.getLogger(InspectionController.class);

    private final InspectionTaskService taskService;
    private final InspectionPresetFactory presetFactory;

    public InspectionController(InspectionTaskService taskService,
                                InspectionPresetFactory presetFactory) {
        this.taskService = taskService;
        this.presetFactory = presetFactory;
    }

    /** 创建巡检任务请求体。 */
    public static final class CreateTaskRequest {
        public String templateId;
        public int sysid;
        public double startLat;
        public double startLon;
        public AreaBody area;
    }

    /** 区域请求体。 */
    public static final class AreaBody {
        public String type; // "polygon" or "circle"
        public List<double[]> points; // polygon: [{lat, lon}, ...]
        public Double centerLat;      // circle
        public Double centerLon;
        public Double radiusM;
    }

    /**
     * 创建巡检任务。
     * <p>
     * 请求体：{@code {templateId, sysid, startLat, startLon, area?}}
     */
    @Operation(summary = "创建巡检任务", description = "基于巡检模板创建任务，自动规划航线")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "任务创建成功"),
        @ApiResponse(responseCode = "400", description = "模板不存在或参数非法")
    })
    @PostMapping("/tasks")
    public Map<String, Object> createTask(@RequestBody CreateTaskRequest req) {
        if (req.templateId == null || req.templateId.isBlank()) {
            throw new BadRequestException("templateId is required");
        }
        InspectionArea area = parseArea(req.area);
        InspectionTask task = taskService.createTask(
                req.templateId, req.sysid, req.startLat, req.startLon, area);
        log.info("Create inspection task: id={} template={} sysid={}",
                task.id(), req.templateId, req.sysid);
        return taskSummary(task);
    }

    /**
     * 列出巡检任务（支持状态筛选）。
     *
     * @param status 状态筛选（PENDING/IN_PROGRESS/COMPLETED/FAILED/ABORTED），不传则返回全部
     */
    @Operation(summary = "列出巡检任务", description = "可按状态筛选")
    @GetMapping("/tasks")
    public List<Map<String, Object>> listTasks(
            @RequestParam(value = "status", required = false) String status) {
        InspectionTask.Status filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = InspectionTask.Status.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("invalid status: " + status);
            }
        }
        List<InspectionTask> tasks = taskService.listTasks(filter);
        List<Map<String, Object>> out = new ArrayList<>();
        for (InspectionTask t : tasks) {
            out.add(taskSummary(t));
        }
        return out;
    }

    /** 获取任务详情。 */
    @Operation(summary = "获取任务详情")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "任务详情"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @GetMapping("/tasks/{id}")
    public Map<String, Object> getTask(@PathVariable("id") String id) {
        InspectionTask task = taskService.getTask(id);
        return taskDetail(task);
    }

    /** 启动巡检。 */
    @Operation(summary = "启动巡检任务")
    @PostMapping("/tasks/{id}/start")
    public Map<String, Object> startTask(@PathVariable("id") String id) {
        InspectionTask task = taskService.startTask(id);
        return taskSummary(task);
    }

    /** 中止巡检。 */
    @Operation(summary = "中止巡检任务")
    @PostMapping("/tasks/{id}/abort")
    public Map<String, Object> abortTask(@PathVariable("id") String id) {
        InspectionTask task = taskService.abortTask(id);
        return taskSummary(task);
    }

    /** 查询进度。 */
    @Operation(summary = "查询任务进度")
    @GetMapping("/tasks/{id}/progress")
    public Map<String, Object> getProgress(@PathVariable("id") String id) {
        return taskService.getProgress(id);
    }

    /** 列出巡检模板预设。 */
    @Operation(summary = "列出巡检模板预设")
    @GetMapping("/templates")
    public List<Map<String, Object>> listTemplates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InspectionTemplate t : presetFactory.allPresets()) {
            out.add(templateSummary(t));
        }
        return out;
    }

    // ---- 辅助方法 ----

    private InspectionArea parseArea(AreaBody body) {
        if (body == null) return null;
        if ("circle".equalsIgnoreCase(body.type)) {
            if (body.centerLat == null || body.centerLon == null || body.radiusM == null) {
                throw new BadRequestException("circle area needs centerLat, centerLon, radiusM");
            }
            return InspectionArea.circle(body.centerLat, body.centerLon, body.radiusM);
        }
        if ("polygon".equalsIgnoreCase(body.type)) {
            if (body.points == null || body.points.size() < 3) {
                throw new BadRequestException("polygon area needs >= 3 points");
            }
            return InspectionArea.polygon(body.points);
        }
        throw new BadRequestException("unknown area type: " + body.type);
    }

    private Map<String, Object> taskSummary(InspectionTask task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", task.id());
        out.put("templateId", task.templateId());
        out.put("status", task.status().name());
        out.put("assignedSysid", task.assignedSysid());
        out.put("progressPct", task.progressPct());
        out.put("waypointCount", task.waypointsGenerated().size());
        out.put("photosCaptured", task.photosCaptured());
        out.put("anomaliesFound", task.anomaliesFound());
        return out;
    }

    private Map<String, Object> taskDetail(InspectionTask task) {
        Map<String, Object> out = taskSummary(task);
        out.put("startTime", task.startTime());
        out.put("endTime", task.endTime());
        List<Map<String, Object>> wps = new ArrayList<>();
        for (InspectionTask.Wp wp : task.waypointsGenerated()) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("seq", wp.seq);
            w.put("lat", wp.lat);
            w.put("lon", wp.lon);
            w.put("alt", wp.alt);
            w.put("action", wp.action.name());
            wps.add(w);
        }
        out.put("waypoints", wps);
        return out;
    }

    private Map<String, Object> templateSummary(InspectionTemplate t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.id());
        out.put("name", t.name());
        out.put("industryType", t.industryType().name());
        out.put("routeType", t.routeType().name());
        out.put("altitudeM", t.altitudeM());
        out.put("speedMps", t.speedMps());
        out.put("overlapPct", t.overlapPct());
        out.put("cameraAngleDeg", t.cameraAngleDeg());
        out.put("description", t.description());
        out.put("totalDistanceKm", t.totalDistanceKm());
        out.put("estimatedDurationMin", t.estimatedDurationMin());
        return out;
    }
}