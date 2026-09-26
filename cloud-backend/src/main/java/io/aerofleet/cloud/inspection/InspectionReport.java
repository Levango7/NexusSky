package io.aerofleet.cloud.inspection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检报告（任务完成后的汇总报告）。
 * <p>
 * 包含照片统计、异常统计（按严重度分组）、航线覆盖率、实际飞行时长/距离、
 * 文字摘要与维护建议列表。
 */
public final class InspectionReport {

    private final String id;
    private final String taskId;
    private final String templateId;
    private final Instant generatedAt;
    private final int totalPhotos;
    private final int totalAnomalies;
    private final Map<Anomaly.Severity, Integer> anomaliesBySeverity;
    private final double routeCoveragePct;
    private final double actualDurationMin;
    private final double distanceFlownKm;
    private final String summary;
    private final List<String> recommendations;

    public InspectionReport(String id, String taskId, String templateId,
                            Instant generatedAt, int totalPhotos, int totalAnomalies,
                            Map<Anomaly.Severity, Integer> anomaliesBySeverity,
                            double routeCoveragePct, double actualDurationMin,
                            double distanceFlownKm, String summary,
                            List<String> recommendations) {
        this.id = id;
        this.taskId = taskId;
        this.templateId = templateId;
        this.generatedAt = generatedAt;
        this.totalPhotos = totalPhotos;
        this.totalAnomalies = totalAnomalies;
        this.anomaliesBySeverity = anomaliesBySeverity == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(anomaliesBySeverity);
        this.routeCoveragePct = routeCoveragePct;
        this.actualDurationMin = actualDurationMin;
        this.distanceFlownKm = distanceFlownKm;
        this.summary = summary;
        this.recommendations = recommendations == null
                ? Collections.emptyList()
                : new ArrayList<>(recommendations);
    }

    public String id() { return id; }
    public String taskId() { return taskId; }
    public String templateId() { return templateId; }
    public Instant generatedAt() { return generatedAt; }
    public int totalPhotos() { return totalPhotos; }
    public int totalAnomalies() { return totalAnomalies; }
    public Map<Anomaly.Severity, Integer> anomaliesBySeverity() {
        return Collections.unmodifiableMap(anomaliesBySeverity);
    }
    public double routeCoveragePct() { return routeCoveragePct; }
    public double actualDurationMin() { return actualDurationMin; }
    public double distanceFlownKm() { return distanceFlownKm; }
    public String summary() { return summary; }
    public List<String> recommendations() { return Collections.unmodifiableList(recommendations); }
}