package io.aerofleet.cloud.inspection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 持久化（V40）：任务/报告/航线快照写 {@code inspection_tasks} / {@code inspection_reports}，
 * 构造时从库还原进内存（新实例即"重启后"的诚实还原）。照片/异常明细列表不落库——
 * 生产路径（{@link #completeFromRun}）只传计数，重启后 {@code getPhotos/getAnomalies}
 * 对老任务返回空列表，这是已声明的取舍而非缺陷。
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
    private final InspectionTaskRepository taskRepo;
    private final InspectionReportRepository reportRepo;
    private final ObjectMapper mapper;

    public InspectionTaskService(RoutePlannerService routePlanner,
                                 AnomalyDetectionService anomalyDetector,
                                 InspectionPresetFactory presetFactory,
                                 InspectionTaskRepository taskRepo,
                                 InspectionReportRepository reportRepo,
                                 ObjectMapper mapper) {
        this.routePlanner = routePlanner;
        this.anomalyDetector = anomalyDetector;
        this.presetFactory = presetFactory;
        this.taskRepo = taskRepo;
        this.reportRepo = reportRepo;
        this.mapper = mapper;
        hydrateFromDb();
    }

    /** 启动还原：把库里的任务与报告事实装回内存，不重放任何迁移路径。 */
    private void hydrateFromDb() {
        for (InspectionTaskEntity e : taskRepo.findAll()) {
            List<Waypoint> wps = waypointsFromJson(e.waypoints);
            List<InspectionTask.Wp> wpSummary = new ArrayList<>(wps.size());
            for (Waypoint wp : wps) {
                wpSummary.add(new InspectionTask.Wp(wp.seq, wp.lat, wp.lon, wp.alt, wp.action));
            }
            InspectionTask task = new InspectionTask(e.taskId, e.templateId, e.sysid, wpSummary);
            task.restore(
                    InspectionTask.Status.valueOf(e.status),
                    e.startTime == null ? null : Instant.ofEpochMilli(e.startTime),
                    e.endTime == null ? null : Instant.ofEpochMilli(e.endTime),
                    e.progressPct == null ? 0.0 : e.progressPct,
                    e.photosCaptured == null ? 0 : e.photosCaptured,
                    e.anomaliesFound == null ? 0 : e.anomaliesFound);
            tasks.put(task.id(), task);
            taskWaypoints.put(task.id(), wps);
            taskPhotos.put(task.id(), new ArrayList<>());
            taskAnomalies.put(task.id(), new ArrayList<>());
        }
        for (InspectionReportEntity e : reportRepo.findAll()) {
            taskReports.put(e.taskId, reportFromEntity(e));
        }
        if (!tasks.isEmpty() || !taskReports.isEmpty()) {
            log.info("Inspection state restored from DB: tasks={} reports={}",
                    tasks.size(), taskReports.size());
        }
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
        persistTask(task, waypoints);

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
        persistTask(task, taskWaypoints.get(taskId));
        log.info("Inspection task started: id={}", taskId);
        return task;
    }

    /** 中止巡检任务。 */
    public InspectionTask abortTask(String taskId) {
        InspectionTask task = getTask(taskId);
        task.abort();
        persistTask(task, taskWaypoints.get(taskId));
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
     * <b>仅用于演示/测试。</b> 生产路径（机巢无人值守任务）走
     * {@link #completeFromRun(String, double, int, int, String)}，不生成任何模拟照片/异常。
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
        persistReport(report);
        task.complete();
        persistTask(task, waypoints);

        log.info("Inspection task completed: id={} photos={} anomalies={}",
                taskId, photos.size(), anomalies.size());
        return report;
    }

    /**
     * 真实执行完成后生成报告。
     * <p>
     * 照片数/异常数由调用方按实际采集结果传入，本方法不做任何模拟补充；
     * Phase 1 相机未接入时传 0/0，并在 {@code note} 里写明原因。
     */
    public InspectionReport completeFromRun(String taskId, double actualDurationMin,
                                            int photosCaptured, int anomaliesFound, String note) {
        InspectionTask task = getTask(taskId);
        if (task.status() == InspectionTask.Status.PENDING) {
            task.start();
        }
        task.addPhotos(photosCaptured);
        task.setAnomaliesFound(anomaliesFound);

        Map<Anomaly.Severity, Integer> bySeverity = new LinkedHashMap<>();
        bySeverity.put(Anomaly.Severity.HIGH, 0);
        bySeverity.put(Anomaly.Severity.MEDIUM, 0);
        bySeverity.put(Anomaly.Severity.LOW, 0);

        String summary = String.format(
                "巡检结束：实际飞行 %.1f 分钟，照片 %d 张，异常 %d 处（未分离严重度统计；距离未采集）。%s",
                actualDurationMin, photosCaptured, anomaliesFound,
                note == null ? "" : note);
        List<String> recommendations = new ArrayList<>();
        if (note != null && !note.isBlank()) {
            recommendations.add(note);
        }
        recommendations.add("建议下次巡检间隔不超过 30 天");

        InspectionReport report = new InspectionReport(
                UUID.randomUUID().toString(), task.id(), task.templateId(),
                Instant.now(), photosCaptured, anomaliesFound, bySeverity,
                100.0, actualDurationMin, 0.0, summary, recommendations);
        taskReports.put(taskId, report);
        persistReport(report);
        task.complete();
        persistTask(task, taskWaypoints.get(taskId));
        log.info("Inspection task completed from run: id={} flightMin={} photos={} anomalies={}",
                taskId, actualDurationMin, photosCaptured, anomaliesFound);
        return report;
    }

    /** 执行失败时把任务置为 FAILED；失败原因由调用方写 run log。 */
    public InspectionTask failTask(String taskId, String reason) {
        InspectionTask task = getTask(taskId);
        task.fail();
        persistTask(task, taskWaypoints.get(taskId));
        log.info("Inspection task failed: id={} reason={}", taskId, reason);
        return task;
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

    /** 任务行写透：create/start/abort/fail/complete 五个稳定迁移点各写一次。 */
    private void persistTask(InspectionTask task, List<Waypoint> waypoints) {
        InspectionTaskEntity e = taskRepo.findById(task.id()).orElse(new InspectionTaskEntity());
        e.taskId = task.id();
        e.templateId = task.templateId();
        e.sysid = task.assignedSysid();
        e.status = task.status().name();
        e.startTime = task.startTime() == null ? null : task.startTime().toEpochMilli();
        e.endTime = task.endTime() == null ? null : task.endTime().toEpochMilli();
        e.progressPct = task.progressPct();
        e.photosCaptured = task.photosCaptured();
        e.anomaliesFound = task.anomaliesFound();
        e.waypoints = waypointsToJson(waypoints);
        taskRepo.save(e);
    }

    /** 报告行写透：报告一旦生成即落库，重启后 getReport 不丢。 */
    private void persistReport(InspectionReport report) {
        InspectionReportEntity e = new InspectionReportEntity();
        e.reportId = report.id();
        e.taskId = report.taskId();
        e.templateId = report.templateId();
        e.generatedAtMs = report.generatedAt().toEpochMilli();
        e.totalPhotos = report.totalPhotos();
        e.totalAnomalies = report.totalAnomalies();
        e.bySeverityJson = severityToJson(report.anomaliesBySeverity());
        e.routeCoveragePct = report.routeCoveragePct();
        e.actualDurationMin = report.actualDurationMin();
        e.distanceFlownKm = report.distanceFlownKm();
        e.summary = report.summary();
        e.recommendations = stringListToJson(report.recommendations());
        reportRepo.save(e);
    }

    private InspectionReport reportFromEntity(InspectionReportEntity e) {
        return new InspectionReport(
                e.reportId, e.taskId, e.templateId,
                Instant.ofEpochMilli(e.generatedAtMs),
                e.totalPhotos, e.totalAnomalies,
                severityFromJson(e.bySeverityJson),
                e.routeCoveragePct, e.actualDurationMin, e.distanceFlownKm,
                e.summary, stringListFromJson(e.recommendations));
    }

    /**
     * 航线快照序列化：数组的数组 [seq,lat,lon,alt,heading,speed,action,hold]。
     * <p>
     * 不做 Jackson 实体自省——Waypoint 是领域对象不是 DTO，手写 8 字段 positional 格式，
     * 字段顺序即契约，改 Waypoint 时这里必须同步。
     */
    private String waypointsToJson(List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
            return "[]";
        }
        List<List<Object>> rows = new ArrayList<>(waypoints.size());
        for (Waypoint wp : waypoints) {
            rows.add(List.of(wp.seq, wp.lat, wp.lon, wp.alt,
                    wp.headingDeg, wp.speedMps, wp.action.name(), wp.holdTimeSec));
        }
        try {
            return mapper.writeValueAsString(rows);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("waypoints 序列化失败", ex);
        }
    }

    private List<Waypoint> waypointsFromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<?> rows = mapper.readValue(json, List.class);
            List<Waypoint> wps = new ArrayList<>(rows.size());
            for (Object rowObj : rows) {
                List<?> row = (List<?>) rowObj;
                wps.add(new Waypoint(
                        ((Number) row.get(0)).intValue(),
                        ((Number) row.get(1)).doubleValue(),
                        ((Number) row.get(2)).doubleValue(),
                        ((Number) row.get(3)).doubleValue(),
                        ((Number) row.get(4)).doubleValue(),
                        ((Number) row.get(5)).doubleValue(),
                        Waypoint.Action.valueOf((String) row.get(6)),
                        ((Number) row.get(7)).doubleValue()));
            }
            return wps;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("waypoints 反序列化失败", ex);
        }
    }

    private String severityToJson(Map<Anomaly.Severity, Integer> bySeverity) {
        Map<String, Integer> flat = new LinkedHashMap<>();
        for (Map.Entry<Anomaly.Severity, Integer> entry : bySeverity.entrySet()) {
            flat.put(entry.getKey().name(), entry.getValue());
        }
        try {
            return mapper.writeValueAsString(flat);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("by_severity 序列化失败", ex);
        }
    }

    private Map<Anomaly.Severity, Integer> severityFromJson(String json) {
        Map<Anomaly.Severity, Integer> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            Map<String, Integer> flat = mapper.readValue(json, new TypeReference<>() {});
            for (Map.Entry<String, Integer> entry : flat.entrySet()) {
                result.put(Anomaly.Severity.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("by_severity 反序列化失败", ex);
        }
    }

    private String stringListToJson(List<String> values) {
        try {
            return mapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("recommendations 序列化失败", ex);
        }
    }

    private List<String> stringListFromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> values = mapper.readValue(json, new TypeReference<>() {});
            return new ArrayList<>(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("recommendations 反序列化失败", ex);
        }
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
