package io.aerofleet.cloud.dock;

import jakarta.persistence.*;

/** 状态迁移日志（spec R1：所有迁移必写，供审计与度量）。 */
@Entity
@Table(name = "dock_state_log")
public class DockStateLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "dock_id", nullable = false)
    public Long dockId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 20)
    public DockState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 20)
    public DockState toState;

    public String reason;

    @Column(nullable = false)
    public long ts = System.currentTimeMillis();

    @Column(name = "created_at_ms", nullable = false)
    public long createdAtMs = System.currentTimeMillis();
}
