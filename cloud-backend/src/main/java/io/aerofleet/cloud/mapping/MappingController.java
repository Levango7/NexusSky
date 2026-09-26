package io.aerofleet.cloud.mapping;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import static io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;

/**
 * 无人机航拍测绘 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code POST /api/mapping/tasks} — 创建测绘任务</li>
 *   <li>{@code GET /api/mapping/tasks} — 列出测绘任务</li>
 *   <li>{@code GET /api/mapping/tasks/{id}} — 获取任务详情</li>
 *   <li>{@code POST /api/mapping/tasks/{id}/start} — 启动测绘</li>
 *   <li>{@code POST /api/mapping/tasks/{id}/abort} — 中止测绘</li>
 *   <li>{@code GET /api/mapping/tasks/{id}/waypoints} — 获取航线规划</li>
 *   <li>{@code GET /api/mapping/tasks/{id}/photos} — 获取采集照片</li>
 *   <li>{@code GET /api/mapping/tasks/{id}/result} — 获取测绘成果</li>
 *   <li>{@code POST /api/mapping/tasks/{id}/process} — 触发成果生成</li>
 *   <li>{@code GET /api/mapping/results} — 列出所有测绘成果</li>
 *   <li>{@code GET /api/mapping/results/{id}/download} — 下载测绘成果</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/mapping")
@Tag(name = "Mapping", description = "无人机航拍测绘 REST API：灾害区域快速测绘，生成正射影像/三维模型/DEM")
public class MappingController {

    private static final Logger log = LoggerFactory.getLogger(MappingController.class);

    private final MappingRoutePlanner routePlanner;
    private final PhotoCaptureService photoCaptureService;
    private final MappingResultService resultService;
    private final DeviceRegistry registry;
    private final MappingTaskRepository taskRepository;

    /** 按任务 ID 存储的航线航点。 */
    private final ConcurrentHashMap<String, List<MappingWaypoint>> waypointsByTask = new ConcurrentHashMap<>();

    @Autowired
    public MappingController(MappingRoutePlanner routePlanner,
                             PhotoCaptureService photoCaptureService,
                             MappingResultService resultService,
                             DeviceRegistry registry,
                             MappingTaskRepository taskRepository) {
        this.routePlanner = routePlanner;
        this.photoCaptureService = photoCaptureService;
        this.resultService = resultService;
        this.registry = registry;
        this.taskRepository = taskRepository;
    }

    // ========== 请求体定义 ==========

    /** 创建测绘任务请求体。 */
    public static class CreateTaskRequest {
        /** 任务名称。 */
        public String name;
        /** 测绘类型：ORTHO_PHOTO / DEM / THREE_D_MODEL / MIXED。 */
        public String type;
        /** 分配执行的无人机 systemId。 */
        public Integer sysid;
        /** 飞行高度（m）。 */
        public Double altitudeM;
        /** 航向重叠率（%）。 */
        public Double overlapPct;
        /** 侧向重叠率（%）。 */
        public Double sidelapPct;
        /** 相机俯仰角（度）。 */
        public Double cameraAngleDeg;
        /** 测绘区域。 */
        public AreaBody area;
    }

    /** 区域定义请求体。 */
    public static class AreaBody {
        /** 区域类型：polygon / circle。 */
        public String type;
        /** 多边形顶点列表（polygon 模式），每个元素为 {lat, lon}。 */
        public List<double[]> points;
        /** 圆心纬度（circle 模式）。 */
        public Double centerLat;
        /** 圆心经度（circle 模式）。 */
        public Double centerLon;
        /** 圆半径 m（circle 模式）。 */
        public Double radiusM;
    }

    // ========== 端点实现 ==========

    /**
     * 创建测绘任务。
     */
    @Operation(summary = "创建测绘任务", description = "根据测绘类型、区域、航高与重叠率参数创建测绘任务")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "任务创建成功"),
        @ApiResponse(responseCode = "400", description = "参数错误")
    })
    @PostMapping("/tasks")
    @Transactional
    public Map<String, Object> createTask(@RequestBody CreateTaskRequest req) {
        validateCreateRequest(req);
        MappingType type = parseType(req.type);
        MappingArea area = parseArea(req.area);
        double altitudeM = req.altitudeM != null ? req.altitudeM : 100.0;
        double overlapPct = req.overlapPct != null ? req.overlapPct : 80.0;
        double sidelapPct = req.sidelapPct != null ? req.sidelapPct : 60.0;
        double cameraAngleDeg = req.cameraAngleDeg != null ? req.cameraAngleDeg : 0.0;
        double gsdCm = routePlanner.computeGsdCm(altitudeM);

        String taskId = UUID.randomUUID().toString();
        MappingTask task = new MappingTask(
                taskId,
                req.name,
                type,
                MappingTask.Status.PENDING,
                area,
                altitudeM,
                overlapPct,
                sidelapPct,
                cameraAngleDeg,
                gsdCm,
                req.sysid,
                null,
                null,
                0,
                0.0
        );
        taskRepository.save(task);

        // 规划航线
        List<MappingWaypoint> waypoints = planRouteForTask(task);
        waypointsByTask.put(taskId, waypoints);
        task.setStatus(MappingTask.Status.PLANNING);
        taskRepository.save(task);

        log.info("Mapping task created: id={} type={} waypoints={}", taskId, type, waypoints.size());

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", taskId);
        response.put("name", req.name);
        response.put("type", type.name());
        response.put("status", task.getStatus().name());
        response.put("assignedSysid", req.sysid);
        response.put("altitudeM", altitudeM);
        response.put("overlapPct", overlapPct);
        response.put("sidelapPct", sidelapPct);
        response.put("gsdCm", gsdCm);
        response.put("waypointCount", waypoints.size());
        return response;
    }

    /**
     * 列出测绘任务。
     */
    @Operation(summary = "列出测绘任务", description = "可选按状态筛选")
    @GetMapping("/tasks")
    public List<Map<String, Object>> listTasks(
            @RequestParam(value = "status", required = false) String statusFilter) {
        MappingTask.Status filter = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            filter = parseStatus(statusFilter);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (MappingTask task : taskRepository.findAll()) {
            if (filter != null && task.getStatus() != filter) {
                continue;
            }
            result.add(taskSummary(task));
        }
        return result;
    }

    /**
     * 获取任务详情。
     */
    @Operation(summary = "获取测绘任务详情")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "任务详情"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @GetMapping("/tasks/{id}")
    public Map<String, Object> getTask(@PathVariable("id") String id) {
        MappingTask task = requireTask(id);
        Map<String, Object> detail = taskSummary(task);
        detail.put("area", areaToMap(task.getArea()));
        detail.put("waypoints", waypointsByTask.getOrDefault(id, new ArrayList<>()));
        return detail;
    }

    /**
     * 启动测绘任务。
     */
    @Operation(summary = "启动测绘任务")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "任务已启动"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @PostMapping("/tasks/{id}/start")
    @Transactional
    public Map<String, Object> startTask(@PathVariable("id") String id) {
        MappingTask task = requireTask(id);
        // P1-fix: 添加状态校验，只允许从 PENDING 或 PLANNING 状态启动
        MappingTask.Status currentStatus = task.getStatus();
        if (currentStatus != MappingTask.Status.PENDING
                && currentStatus != MappingTask.Status.PLANNING) {
            throw new BadRequestException(
                    "cannot start task in status: " + currentStatus
                            + ", only PENDING or PLANNING allowed");
        }
        task.setStatus(MappingTask.Status.IN_PROGRESS);
        task.setStartTime(Instant.now());
        taskRepository.save(task);
        log.info("Mapping task started: id={}", id);
        return Map.of(
                "id", id,
                "status", task.getStatus().name(),
                "startTime", task.getStartTime().toString()
        );
    }

    /**
     * 中止测绘任务。
     */
    @Operation(summary = "中止测绘任务")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "任务已中止"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @PostMapping("/tasks/{id}/abort")
    @Transactional
    public Map<String, Object> abortTask(@PathVariable("id") String id) {
        MappingTask task = requireTask(id);
        // P1-fix: 添加状态校验，只允许从 PLANNING 或 IN_PROGRESS 状态中止
        MappingTask.Status currentStatus = task.getStatus();
        if (currentStatus != MappingTask.Status.PLANNING
                && currentStatus != MappingTask.Status.IN_PROGRESS) {
            throw new BadRequestException(
                    "cannot abort task in status: " + currentStatus
                            + ", only PLANNING or IN_PROGRESS allowed");
        }
        task.setStatus(MappingTask.Status.FAILED);
        task.setEndTime(Instant.now());
        taskRepository.save(task);
        log.info("Mapping task aborted: id={}", id);
        return Map.of(
                "id", id,
                "status", task.getStatus().name(),
                "endTime", task.getEndTime().toString()
        );
    }

    /**
     * 获取航线规划。
     */
    @Operation(summary = "获取航线规划航点列表")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "航点列表"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @GetMapping("/tasks/{id}/waypoints")
    public List<MappingWaypoint> getWaypoints(@PathVariable("id") String id) {
        requireTask(id);
        return waypointsByTask.getOrDefault(id, new ArrayList<>());
    }

    /**
     * 获取采集照片。
     */
    @Operation(summary = "获取任务采集的照片列表")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "照片列表"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @GetMapping("/tasks/{id}/photos")
    public List<CapturedPhoto> getPhotos(@PathVariable("id") String id) {
        requireTask(id);
        return photoCaptureService.getPhotos(id);
    }

    /**
     * 获取测绘成果。
     */
    @Operation(summary = "获取任务的测绘成果")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "成果列表"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @GetMapping("/tasks/{id}/result")
    public List<MappingResult> getTaskResult(@PathVariable("id") String id) {
        requireTask(id);
        return resultService.getResultsByTask(id);
    }

    /**
     * 触发成果生成。
     */
    @Operation(summary = "触发测绘成果生成", description = "根据任务类型生成正射影像/DEM/三维模型")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "成果生成完成"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @PostMapping("/tasks/{id}/process")
    @Transactional
    public Map<String, Object> processTask(@PathVariable("id") String id) {
        MappingTask task = requireTask(id);
        // P1-fix: 添加状态校验，只允许 IN_PROGRESS 状态触发成果生成
        MappingTask.Status currentStatus = task.getStatus();
        if (currentStatus != MappingTask.Status.IN_PROGRESS) {
            throw new BadRequestException(
                    "cannot process task in status: " + currentStatus
                            + ", only IN_PROGRESS allowed");
        }
        List<CapturedPhoto> photos = photoCaptureService.getPhotos(id);

        // 如果没有照片，模拟采集
        if (photos.isEmpty()) {
            List<MappingWaypoint> waypoints = waypointsByTask.getOrDefault(id, new ArrayList<>());
            int sysid = task.getAssignedSysid() != null ? task.getAssignedSysid() : 1;
            photos = photoCaptureService.simulateCapture(id, sysid, waypoints);
            task.setPhotosCaptured(photos.size());
        }

        List<MappingResult> results = new ArrayList<>();
        switch (task.getType()) {
            case ORTHO_PHOTO:
                results.add(resultService.generateOrthophoto(id, photos));
                break;
            case DEM:
                results.add(resultService.generateDem(id, photos));
                break;
            case THREE_D_MODEL:
                results.add(resultService.generate3DModel(id, photos));
                break;
            case MIXED:
                results.add(resultService.generateOrthophoto(id, photos));
                results.add(resultService.generateDem(id, photos));
                results.add(resultService.generate3DModel(id, photos));
                break;
        }

        task.setStatus(MappingTask.Status.COMPLETED);
        task.setEndTime(Instant.now());
        task.setProgressPct(100.0);
        taskRepository.save(task);

        log.info("Mapping task processed: id={} results={}", id, results.size());

        return Map.of(
                "id", id,
                "status", task.getStatus().name(),
                "photosProcessed", photos.size(),
                "resultsCount", results.size()
        );
    }

    /**
     * 列出所有测绘成果。
     */
    @Operation(summary = "列出所有测绘成果")
    @GetMapping("/results")
    public List<MappingResult> listResults() {
        return resultService.getAllResults();
    }

    /**
     * 下载测绘成果。
     */
    @Operation(summary = "下载测绘成果", description = "返回成果的下载链接")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "下载链接"),
        @ApiResponse(responseCode = "404", description = "成果不存在")
    })
    @GetMapping("/results/{id}/download")
    public Map<String, Object> downloadResult(@PathVariable("id") String id) {
        MappingResult result = resultService.getResult(id);
        if (result == null) {
            throw new NotFoundException("mapping result not found: " + id);
        }

        String downloadUrl = null;
        if (result.getOrthophotoUrl() != null) {
            downloadUrl = result.getOrthophotoUrl();
        } else if (result.getDemUrl() != null) {
            downloadUrl = result.getDemUrl();
        } else if (result.getModelUrl() != null) {
            downloadUrl = result.getModelUrl();
        }

        return Map.of(
                "id", id,
                "type", result.getType().name(),
                "status", result.getStatus().name(),
                "downloadUrl", downloadUrl != null ? downloadUrl : "",
                "fileSizeMB", result.getFileSizeMB()
        );
    }

    // ========== 内部方法 ==========

    private MappingTask requireTask(String id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("mapping task not found: " + id));
    }

    private void validateCreateRequest(CreateTaskRequest req) {
        if (req == null) {
            throw new BadRequestException("request body is required");
        }
        if (req.name == null || req.name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (req.type == null || req.type.isBlank()) {
            throw new BadRequestException("type is required");
        }
    }

    private MappingType parseType(String type) {
        try {
            return MappingType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid mapping type: " + type);
        }
    }

    private MappingTask.Status parseStatus(String status) {
        try {
            return MappingTask.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid status: " + status);
        }
    }

    private MappingArea parseArea(AreaBody body) {
        if (body == null) {
            // 默认以北京为中心 500m × 500m 方形区域
            double centerLat = 39.90;
            double centerLon = 116.40;
            double half = 250.0 / 111320.0;
            return MappingArea.polygon(List.of(
                    new double[]{centerLat - half, centerLon - half},
                    new double[]{centerLat - half, centerLon + half},
                    new double[]{centerLat + half, centerLon + half},
                    new double[]{centerLat + half, centerLon - half}));
        }
        if ("polygon".equalsIgnoreCase(body.type)) {
            if (body.points == null || body.points.size() < 3) {
                throw new BadRequestException("polygon needs >= 3 points");
            }
            return MappingArea.polygon(body.points);
        }
        if ("circle".equalsIgnoreCase(body.type)) {
            if (body.centerLat == null || body.centerLon == null || body.radiusM == null) {
                throw new BadRequestException("circle needs centerLat, centerLon, radiusM");
            }
            return MappingArea.circle(body.centerLat, body.centerLon, body.radiusM);
        }
        throw new BadRequestException("invalid area type: " + body.type);
    }

    private List<MappingWaypoint> planRouteForTask(MappingTask task) {
        MappingArea area = task.getArea();
        double alt = task.getAltitudeM();
        switch (task.getType()) {
            case ORTHO_PHOTO:
                return routePlanner.planOrthophotoRoute(area, alt,
                        task.getOverlapPct(), task.getSidelapPct());
            case DEM:
                return routePlanner.planDemRoute(area, alt);
            case THREE_D_MODEL:
                return routePlanner.plan3DModelRoute(area, alt);
            case MIXED:
                List<MappingWaypoint> all = new ArrayList<>();
                all.addAll(routePlanner.planOrthophotoRoute(area, alt,
                        task.getOverlapPct(), task.getSidelapPct()));
                all.addAll(routePlanner.plan3DModelRoute(area, alt));
                // 重新编号
                List<MappingWaypoint> renumbered = new ArrayList<>();
                int seq = 0;
                int photoId = 0;
                for (MappingWaypoint wp : all) {
                    if (wp.action == MappingWaypoint.Action.PHOTO) {
                        photoId++;
                        renumbered.add(new MappingWaypoint(seq++, wp.lat, wp.lon, wp.alt,
                                wp.headingDeg, wp.cameraAngleDeg,
                                MappingWaypoint.Action.PHOTO, photoId));
                    } else {
                        renumbered.add(new MappingWaypoint(seq++, wp.lat, wp.lon, wp.alt,
                                wp.headingDeg, wp.cameraAngleDeg,
                                wp.action, 0));
                    }
                }
                return renumbered;
            default:
                throw new BadRequestException("unsupported mapping type: " + task.getType());
        }
    }

    private Map<String, Object> taskSummary(MappingTask task) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("id", task.getId());
        summary.put("name", task.getName());
        summary.put("type", task.getType().name());
        summary.put("status", task.getStatus().name());
        summary.put("assignedSysid", task.getAssignedSysid());
        summary.put("altitudeM", task.getAltitudeM());
        summary.put("overlapPct", task.getOverlapPct());
        summary.put("sidelapPct", task.getSidelapPct());
        summary.put("gsdCm", task.getGsdCm());
        summary.put("photosCaptured", task.getPhotosCaptured());
        summary.put("progressPct", task.getProgressPct());
        return summary;
    }

    private Map<String, Object> areaToMap(MappingArea area) {
        if (area.kind() == MappingArea.Kind.CIRCLE) {
            return Map.of(
                    "type", "circle",
                    "centerLat", area.centerLat(),
                    "centerLon", area.centerLon(),
                    "radiusM", area.radiusM(),
                    "areaKm2", area.areaKm2()
            );
        }
        return Map.of(
                "type", "polygon",
                "points", area.points(),
                "areaKm2", area.areaKm2()
        );
    }
}