package io.aerofleet.cloud.inspection;

import jakarta.persistence.*;

/** 巡检任务落库行（V40）。waypoints 存 JSON 数组文本，是航线快照不是可聚合实体。 */
@Entity
@Table(name = "inspection_tasks")
public class InspectionTaskEntity {
    @Id
    @Column(name = "task_id", length = 64)
    public String taskId;

    @Column(name = "template_id", nullable = false, length = 64)
    public String templateId;

    @Column(nullable = false)
    public int sysid;

    /** PENDING / IN_PROGRESS / COMPLETED / FAILED / ABORTED（存枚举名，同 V30 口径）。 */
    @Column(nullable = false, length = 32)
    public String status;

    @Column(name = "start_time")
    public Long startTime;

    @Column(name = "end_time")
    public Long endTime;

    @Column(name = "progress_pct")
    public Double progressPct;

    @Column(name = "photos_captured")
    public Integer photosCaptured;

    @Column(name = "anomalies_found")
    public Integer anomaliesFound;

    @Column(columnDefinition = "TEXT")
    public String waypoints;

    @Column(name = "created_at_ms", nullable = false)
    public long createdAtMs = System.currentTimeMillis();
}
