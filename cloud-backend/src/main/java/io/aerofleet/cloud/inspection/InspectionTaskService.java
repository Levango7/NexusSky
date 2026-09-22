package io.aerofleet.cloud.inspection;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 巡检任务管理服务。
 * <p>
 * 持有 {@code ConcurrentHashMap<taskId, InspectionTask>}，承载任务创建 / 查询 / 启动 / 中止 / 进度查询；
 * 航线规划委托 {@link RoutePlannerService}，异常检测委托 {@link AnomalyDetectionService}。
 * <p>
 * 并发安全：tasks ConcurrentHashMap + UUID 任务 ID。
 */
@Service
public class InspectionTaskService {

    private static final Logger log = LoggerFactory.getLogger(InspectionTaskService.class);

    private final Map<String, InspectionTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<Waypoint>> taskWaypoints = new ConcurrentHashMap<>();
    private final Map<String, List<InspectionPhoto>> taskPhotos = new ConcurrentHashMap<>();
    private final Map<String, List<Anomaly>> taskAnomalies = new ConcurrentHashMap<>();
    private final Map<String, InspectionReport> taskReports = new ConcurrentHashMap<>();

    private final RoutePlannerService routePlanner;
    private final AnomalyDetectionService anomalyDetector;
    private final InspectionPresetFactory presetFactory;

    public InspectionTaskService(RoutePlannerService routePlanner,
                                 AnomalyDetectionService anomalyDetector,
                                 InspectionPresetFactory presetFactory) {
        this.routePlanner = routePlanner;
        this.anomalyDetector = anomalyDetector;
        this.presetFactory = presetFactory;
    }

    /**
     * 创建巡检任务。
     *
     * @param templateId 巡检模板 ID
     * @param sysid      目标无人机 sysid
     * @param startLat   起始纬度
     * @param startLon   起始经度
     * @param area       巡检区域（null 时使用默认区域）
     * @return 创建的巡检任务
     * @throws BadRequestException 模板不存在
     */
    public InspectionTask createTask(String templateId, int sysid,
                                     double startLat, double startLon,
                                     InspectionArea area) {
        InspectionTemplate template = presetFactory.findById(templateId);
        if (template == null) {
            throw new BadRequestException("template not found: " + templateId);
        }
        List<Waypoint> waypoints = routePlanner.planRoute(template, startLat, startLon, area);
        String taskId = UUID.randomUUID().toString();

        List<InspectionTask.Wp> wpSummary = new ArrayList<>();
        for (Waypoint wp : waypoints) {
            wpSummary.add(new InspectionTask.Wp(wp.seq, wp.lat, wp.lon, wp.alt, wp.action));
        }
        InspectionTask task = new InspectionTask(taskId, templateId, sysid, wpSummary);
        tasks.put(taskId, task);
        taskWaypoints.put(taskId, waypoints);
        taskPhotos.put(taskId, new ArrayList<>());
        taskAnomalies.put(taskId, new ArrayList<>());

        log.info("Inspection task created: id={} template={} sysid={} waypoints={}",
                taskId, templateId, sysid, waypoints.size());
        return task;
    }

    /** 列出所有巡检任务（可按状态筛选）。 */
    public List<InspectionTask> listTasks(InspectionTask.Status status) {
        if (status == null) {
            return new ArrayList<>(tasks.values());
        }
        return tasks.values().stream()
                .filter(t -> t.status() == status)
                .toList();
    }

    /** 获取任务详情。 */
    public InspectionTask getTask(String taskId) {
        InspectionTask task = tasks.get(taskId);
        if (task == null) {
            throw new NotFoundException("inspection task not found: " + taskId);
        }
        return task;
    }

    /** 获取任务航点。 */
    public List<Waypoint> getWaypoints(String taskId) {
        getTask(taskId); // 校验存在
        return taskWaypoints.getOrDefault(taskId, List.of());
    }

    /** 启动巡检任务。 */
    public InspectionTask startTask(String taskId) {
        InspectionTask task = getTask(taskId);
        task.start();
        log.info("Inspection task started: id={}", taskId);
        return task;
    }

    /** 中止巡检任务。 */
    public InspectionTask abortTask(String taskId) {
        InspectionTask task = getTask(taskId);
        task.abort();
        log.info("Inspection task aborted: id={}", taskId);
        return task;
    }

    /** 查询任务进度。 */
    public Map<String, Object> getProgress(String taskId) {
        InspectionTask task = getTask(taskId);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("taskId", task.id());
        progress.put("status", task.status().name());
        progress.put("progressPct", task.progressPct());
        progress.put("photosCaptured", task.photosCaptured());
        progress.put("anomaliesFound", task.anomaliesFound());
        progress.put("waypointCount", task.waypointsGenerated().size());
        return progress;
    }

    /**
     * 模拟任务执行：生成照片并检测异常，生成报告。
     * <p>
     * 实际场景由无人机执行航点并回传照片，此处为模拟完成流程。
     */
    public InspectionReport simulateCompletion(String taskId) {
        InspectionTask task = getTask(taskId);
        List<Waypoint> waypoints = taskWaypoints.getOrDefault(taskId, List.of());
        InspectionTemplate template = presetFactory.findById(task.templateId());

        // 生成照片（每个 PHOTO/SCAN 航点一张）
        List<InspectionPhoto> photos = new ArrayList<>();
        for (Waypoint wp : waypoints) {
            if (wp.action == Waypoint.Action.PHOTO || wp.action == Waypoint.Action.SCAN) {
                String photoId = UUID.randomUUID().toString();
                InspectionPhoto photo = new InspectionPhoto(
                        photoId, taskId, wp.seq, wp.lat, wp.lon, wp.alt,
                        wp.headingDeg,
                        template != null ? template.cameraAngleDeg() : 90.0,
                        Instant.now(), null,
                        template != null ? template.industryType() : IndustryType.POWER_LINE);
                photos.add(photo);
            }
        }
        taskPhotos.put(taskId, photos);
        task.addPhotos(photos.size());

        // 检测异常
        List<Anomaly> anomalies = anomalyDetector.detectAll(photos);
        taskAnomalies.put(taskId, anomalies);
        task.setAnomaliesFound(anomalies.size());

        // 生成报告
        InspectionReport report = buildReport(task, template, photos, anomalies, waypoints);
        taskReports.put(taskId, report);
        task.complete();

        log.info("Inspection task completed: id={} photos={} anomalies={}",
                taskId, photos.size(), anomalies.size());
        return report;
    }

    /** 获取任务报告。 */
    public InspectionReport getReport(String taskId) {
        getTask(taskId);
        InspectionReport report = taskReports.get(taskId);
        if (report == null) {
            throw new NotFoundException("report not generated for task: " + taskId);
        }
        return report;
    }

    /** 获取任务异常列表。 */
    public List<Anomaly> getAnomalies(String taskId) {
        getTask(taskId);
        return taskAnomalies.getOrDefault(taskId, List.of());
    }

    /** 获取任务照片列表。 */
    public List<InspectionPhoto> getPhotos(String taskId) {
        getTask(taskId);
        return taskPhotos.getOrDefault(taskId, List.of());
    }

    private InspectionReport buildReport(InspectionTask task,
                                          InspectionTemplate template,
                                          List<InspectionPhoto> photos,
                                          List<Anomaly> anomalies,
                                          List<Waypoint> waypoints) {
        Map<Anomaly.Severity, Integer> bySeverity = new LinkedHashMap<>();
        bySeverity.put(Anomaly.Severity.HIGH, 0);
        bySeverity.put(Anomaly.Severity.MEDIUM, 0);
        bySeverity.put(Anomaly.Severity.LOW, 0);
        for (Anomaly a : anomalies) {
            bySeverity.merge(a.severity(), 1, Integer::sum);
        }

        double distanceKm = template != null ? template.totalDistanceKm() : 0;
        double durationMin = template != null ? template.estimatedDurationMin() : 0;
        IndustryType industry = template != null ? template.industryType() : IndustryType.POWER_LINE;

        List<String> recommendations = new ArrayList<>();
        if (!anomalies.isEmpty()) {
            recommendations.add(AnomalyDetectionService.recommendationForIndustry(industry));
        }
        recommendations.add("建议下次巡检间隔不超过 30 天");

        String summary = String.format(
                "巡检完成：共拍摄 %d 张照片，发现 %d 处异常（高 %d / 中 %d / 低 %d），航线覆盖率 %.1f%%",
                photos.size(), anomalies.size(),
                bySeverity.get(Anomaly.Severity.HIGH),
                bySeverity.get(Anomaly.Severity.MEDIUM),
                bySeverity.get(Anomaly.Severity.LOW),
                95.0);

        return new InspectionReport(
                UUID.randomUUID().toString(),
                task.id(), task.templateId(),
                Instant.now(),
                photos.size(), anomalies.size(),
                bySeverity,
                95.0, durationMin, distanceKm,
                summary, recommendations);
    }
}