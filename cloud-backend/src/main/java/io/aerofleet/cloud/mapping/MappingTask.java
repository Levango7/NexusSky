package io.aerofleet.cloud.mapping;

import java.time.Instant;

/**
 * 测绘任务（无人机航拍测绘的任务实体）。
 * <p>
 * 包含任务基本信息、测绘参数、区域定义、执行状态与进度。
 * 由 {@link MappingController} 创建，经航线规划、影像采集、成果生成等阶段完成。
 */
public final class MappingTask {

    /** 任务状态枚举。 */
    public enum Status {
        /** 待启动。 */
        PENDING,
        /** 正在规划航线。 */
        PLANNING,
        /** 正在执行测绘飞行。 */
        IN_PROGRESS,
        /** 已完成。 */
        COMPLETED,
        /** 失败/中止。 */
        FAILED
    }

    /** 任务 ID。 */
    private String id;
    /** 任务名称。 */
    private String name;
    /** 测绘类型。 */
    private MappingType type;
    /** 任务状态。 */
    private Status status;
    /** 测绘区域。 */
    private MappingArea area;
    /** 飞行高度（m）。 */
    private double altitudeM;
    /** 航向重叠率（%）。 */
    private double overlapPct;
    /** 侧向重叠率（%）。 */
    private double sidelapPct;
    /** 相机俯仰角（度）。 */
    private double cameraAngleDeg;
    /** 地面采样距离 GSD（cm/pixel）。 */
    private double gsdCm;
    /** 分配执行的无人机 systemId。 */
    private Integer assignedSysid;
    /** 任务开始时间。 */
    private Instant startTime;
    /** 任务结束时间。 */
    private Instant endTime;
    /** 已采集照片数。 */
    private int photosCaptured;
    /** 进度百分比（0~100）。 */
    private double progressPct;

    public MappingTask() {
    }

    public MappingTask(String id, String name, MappingType type, Status status,
                       MappingArea area, double altitudeM, double overlapPct,
                       double sidelapPct, double cameraAngleDeg, double gsdCm,
                       Integer assignedSysid, Instant startTime, Instant endTime,
                       int photosCaptured, double progressPct) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.status = status;
        this.area = area;
        this.altitudeM = altitudeM;
        this.overlapPct = overlapPct;
        this.sidelapPct = sidelapPct;
        this.cameraAngleDeg = cameraAngleDeg;
        this.gsdCm = gsdCm;
        this.assignedSysid = assignedSysid;
        this.startTime = startTime;
        this.endTime = endTime;
        this.photosCaptured = photosCaptured;
        this.progressPct = progressPct;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public MappingType getType() { return type; }
    public void setType(MappingType type) { this.type = type; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public MappingArea getArea() { return area; }
    public void setArea(MappingArea area) { this.area = area; }

    public double getAltitudeM() { return altitudeM; }
    public void setAltitudeM(double altitudeM) { this.altitudeM = altitudeM; }

    public double getOverlapPct() { return overlapPct; }
    public void setOverlapPct(double overlapPct) { this.overlapPct = overlapPct; }

    public double getSidelapPct() { return sidelapPct; }
    public void setSidelapPct(double sidelapPct) { this.sidelapPct = sidelapPct; }

    public double getCameraAngleDeg() { return cameraAngleDeg; }
    public void setCameraAngleDeg(double cameraAngleDeg) { this.cameraAngleDeg = cameraAngleDeg; }

    public double getGsdCm() { return gsdCm; }
    public void setGsdCm(double gsdCm) { this.gsdCm = gsdCm; }

    public Integer getAssignedSysid() { return assignedSysid; }
    public void setAssignedSysid(Integer assignedSysid) { this.assignedSysid = assignedSysid; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }

    public int getPhotosCaptured() { return photosCaptured; }
    public void setPhotosCaptured(int photosCaptured) { this.photosCaptured = photosCaptured; }

    public double getProgressPct() { return progressPct; }
    public void setProgressPct(double progressPct) { this.progressPct = progressPct; }
}