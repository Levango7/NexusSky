package io.aerofleet.cloud.health;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 维护记录持久化实体（V30__maintenance_records.sql；审计 §2.1 第 3 条）。
 *
 * <p>与 API 层 DTO {@link MaintenanceRecord} 一对一：域对象保持纯 POJO（无 JPA 注解），
 * 本实体只做落库映射——两个方向的转换集中在 {@link #toRecord()} /
 * {@link #fromRecord(MaintenanceRecord)}，避免"字段加了一半"的双向漂移。
 *
 * <p>枚举以 {@link EnumType#STRING} 存名字：改枚举名即数据迁移，这是显式成本；
 * 不存 ordinal（ordinal 是那种"加一个枚举值就静默改语义"的经典坑）。
 */
@Entity
@Table(name = "maintenance_records")
public class MaintenanceRecordEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "sysid", nullable = false)
    private int sysid;

    @Enumerated(EnumType.STRING)
    @Column(name = "component_type", length = 32)
    private ComponentType componentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "maintenance_type", length = 32)
    private MaintenanceRecord.MaintenanceType maintenanceType;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "completed_date")
    private LocalDate completedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private MaintenanceRecord.Status status;

    @Column(name = "technician", length = 128)
    private String technician;

    @Column(name = "notes", length = 1024)
    private String notes;

    @Column(name = "cost", nullable = false)
    private double cost;

    @Column(name = "created_at")
    private Instant createdAt;

    protected MaintenanceRecordEntity() {
        // JPA 用
    }

    public static MaintenanceRecordEntity fromRecord(MaintenanceRecord r) {
        MaintenanceRecordEntity e = new MaintenanceRecordEntity();
        e.id = r.getId();
        e.sysid = r.getSysid();
        e.componentType = r.getComponentType();
        e.maintenanceType = r.getMaintenanceType();
        e.scheduledDate = r.getScheduledDate();
        e.completedDate = r.getCompletedDate();
        e.status = r.getStatus();
        e.technician = r.getTechnician();
        e.notes = r.getNotes();
        e.cost = r.getCost();
        e.createdAt = Instant.now();
        return e;
    }

    /** 把（可能有更新的）域对象字段覆盖进本实体——PUT 部分更新路径用。 */
    public void applyFrom(MaintenanceRecord r) {
        this.componentType = r.getComponentType();
        this.maintenanceType = r.getMaintenanceType();
        this.scheduledDate = r.getScheduledDate();
        this.completedDate = r.getCompletedDate();
        this.status = r.getStatus();
        this.technician = r.getTechnician();
        this.notes = r.getNotes();
        this.cost = r.getCost();
    }

    public MaintenanceRecord toRecord() {
        MaintenanceRecord r = new MaintenanceRecord(id, sysid, componentType, maintenanceType,
                scheduledDate, status);
        r.setCompletedDate(completedDate);
        r.setTechnician(technician);
        r.setNotes(notes);
        r.setCost(cost);
        return r;
    }

    public String getId() {
        return id;
    }

    public int getSysid() {
        return sysid;
    }

    public MaintenanceRecord.Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
