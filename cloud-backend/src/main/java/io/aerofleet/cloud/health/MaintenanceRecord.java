package io.aerofleet.cloud.health;

import java.time.LocalDate;

/**
 * 维护记录（P1-2 维护管理）。
 * <p>
 * 描述一次维护活动的完整生命周期：计划 → 执行 → 完成/取消。
 */
public class MaintenanceRecord {

    /** 维护类型。 */
    public enum MaintenanceType {
        /** 更换部件。 */
        REPLACE,
        /** 修复。 */
        REPAIR,
        /** 校准。 */
        CALIBRATE,
        /** 检查。 */
        INSPECT
    }

    /** 维护状态。 */
    public enum Status {
        /** 已计划。 */
        SCHEDULED,
        /** 执行中。 */
        IN_PROGRESS,
        /** 已完成。 */
        COMPLETED,
        /** 已取消。 */
        CANCELLED
    }

    private String id;
    private int sysid;
    private ComponentType componentType;
    private MaintenanceType maintenanceType;
    private LocalDate scheduledDate;
    private LocalDate completedDate;
    private Status status;
    private String technician;
    private String notes;
    private double cost;

    public MaintenanceRecord() {
        // JSON 反序列化用
    }

    public MaintenanceRecord(String id,
                              int sysid,
                              ComponentType componentType,
                              MaintenanceType maintenanceType,
                              LocalDate scheduledDate,
                              Status status) {
        this.id = id;
        this.sysid = sysid;
        this.componentType = componentType;
        this.maintenanceType = maintenanceType;
        this.scheduledDate = scheduledDate;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getSysid() {
        return sysid;
    }

    public void setSysid(int sysid) {
        this.sysid = sysid;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public void setComponentType(ComponentType componentType) {
        this.componentType = componentType;
    }

    public MaintenanceType getMaintenanceType() {
        return maintenanceType;
    }

    public void setMaintenanceType(MaintenanceType maintenanceType) {
        this.maintenanceType = maintenanceType;
    }

    public LocalDate getScheduledDate() {
        return scheduledDate;
    }

    public void setScheduledDate(LocalDate scheduledDate) {
        this.scheduledDate = scheduledDate;
    }

    public LocalDate getCompletedDate() {
        return completedDate;
    }

    public void setCompletedDate(LocalDate completedDate) {
        this.completedDate = completedDate;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getTechnician() {
        return technician;
    }

    public void setTechnician(String technician) {
        this.technician = technician;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public double getCost() {
        return cost;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }
}