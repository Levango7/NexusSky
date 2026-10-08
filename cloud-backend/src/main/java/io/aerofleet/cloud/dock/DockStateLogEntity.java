package io.aerofleet.cloud.dock;

import jakarta.persistence.*;
import java.time.Instant;

/** 状态迁移日志（spec R1：所有迁移必写，供审计与度量）。 */
@Entity
@Table(name = "dock_state_log")
public class DockStateLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "dock_id", nullable = false)
    public Long dockId;

    @Column(name = "from_state", length = 20)
    public DockState fromState;

    @Column(name = "to_state", nullable = false, length = 20)
    public DockState toState;

    public String reason;

    @Column(nullable = false)
    public long ts = System.currentTimeMillis();

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
