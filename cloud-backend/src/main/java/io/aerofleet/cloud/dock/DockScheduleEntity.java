package io.aerofleet.cloud.dock;

import jakarta.persistence.*;
import java.time.Instant;

/** 无人值守定时任务（spec R4）。cron 用 Spring {@link org.springframework.scheduling.support.CronExpression} 口径。 */
@Entity
@Table(name = "dock_schedules")
public class DockScheduleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "dock_id", nullable = false)
    public Long dockId;

    @Column(nullable = false)
    public String name;

    @Column(name = "cron_expr", nullable = false, length = 60)
    public String cronExpr;

    /** 航点 JSON：[[lat,lon,alt],...]（PoC 用显式航点，模板库属 F3）。 */
    @Column(name = "waypoints", columnDefinition = "TEXT")
    public String waypoints;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "last_run_at")
    public Long lastRunAt;

    /** 最近一次触发结果：OK / SKIPPED:reason。 */
    @Column(name = "last_result", length = 30)
    public String lastResult;

    @Column(name = "next_due_ms")
    public Long nextDueMs;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
