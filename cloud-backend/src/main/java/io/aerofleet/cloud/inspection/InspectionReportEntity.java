package io.aerofleet.cloud.inspection;

import jakarta.persistence.*;

/** 巡检报告落库行（V40）。by_severity/recommendations 存 JSON 文本，是快照不拆子表。 */
@Entity
@Table(name = "inspection_reports")
public class InspectionReportEntity {
    @Id
    @Column(name = "report_id", length = 64)
    public String reportId;

    @Column(name = "task_id", nullable = false, unique = true, length = 64)
    public String taskId;

    @Column(name = "template_id", length = 64)
    public String templateId;

    @Column(name = "generated_at_ms", nullable = false)
    public long generatedAtMs;

    @Column(name = "total_photos", nullable = false)
    public int totalPhotos;

    @Column(name = "total_anomalies", nullable = false)
    public int totalAnomalies;

    @Column(name = "by_severity_json", nullable = false, columnDefinition = "TEXT")
    public String bySeverityJson;

    @Column(name = "route_coverage_pct", nullable = false)
    public double routeCoveragePct;

    @Column(name = "actual_duration_min", nullable = false)
    public double actualDurationMin;

    @Column(name = "distance_flown_km", nullable = false)
    public double distanceFlownKm;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String summary;

    @Column(columnDefinition = "TEXT")
    public String recommendations;
}
