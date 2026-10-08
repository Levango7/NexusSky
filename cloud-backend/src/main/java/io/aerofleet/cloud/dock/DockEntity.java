package io.aerofleet.cloud.dock;

import jakarta.persistence.*;

/** 机巢（F2，spec R1）。主数据落 PG（C4 口径），状态机真值在 state 字段。 */
@Entity
@Table(name = "docks")
public class DockEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "tenant_id")
    public String tenantId;

    @Column(nullable = false)
    public String name;

    public String model;

    @Column(nullable = false, unique = true)
    public String sn;

    public Double lat;
    public Double lon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public DockState state = DockState.OFFLINE;

    @Column(name = "temperature_c")
    public Double temperatureC;

    @Column(name = "battery_pct")
    public Integer batteryPct;

    /** 托管机的 sysid（无人值守任务的目标机）。 */
    @Column(name = "drone_sysid")
    public Integer droneSysid;

    @Column(name = "last_heartbeat_ms", nullable = false)
    public long lastHeartbeatMs;

    @Column(name = "temp_warn_c", nullable = false)
    public double tempWarnC = 55;

    @Column(name = "temp_crit_c", nullable = false)
    public double tempCritC = 70;

    /** reboot 进行中标记（该时刻前动作命令全部拒绝）。 */
    @Column(name = "reboot_until_ms", nullable = false)
    public long rebootUntilMs;

    @Column(name = "created_at_ms", nullable = false)
    public long createdAtMs = System.currentTimeMillis();

    @Column(name = "updated_at_ms", nullable = false)
    public long updatedAtMs = System.currentTimeMillis();
}
