package io.aerofleet.cloud.dock;

import jakarta.persistence.*;

/** 无人值守任务运行记录（spec R5：架次与飞行分钟的数据源）。 */
@Entity
@Table(name = "dock_run_log")
public class DockRunLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "schedule_id", nullable = false)
    public Long scheduleId;

    @Column(name = "dock_id", nullable = false)
    public Long dockId;

    @Column(name = "started_at", nullable = false)
    public long startedAt = System.currentTimeMillis();

    @Column(name = "finished_at")
    public Long finishedAt;

    /** RUNNING / OK / SKIPPED / FAILED。 */
    @Column(nullable = false, length = 20)
    public String result = "RUNNING";

    public String reason;

    @Column(name = "flight_minutes")
    public Double flightMinutes;

    @Column(name = "created_at", nullable = false)
    public long createdAt = System.currentTimeMillis();
}
