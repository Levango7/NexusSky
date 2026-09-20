package io.aerofleet.cloud.inspection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 巡检任务（一次具体的巡检执行实例）。
 * <p>
 * 基于巡检模板创建，管理任务生命周期：
 * <pre>
 *   PENDING → IN_PROGRESS → COMPLETED
 *                      ↘ → FAILED
 *                      ↘ → ABORTED
 * </pre>
 * 追踪航点执行进度、拍摄照片数、检测到的异常数。
 */
public final class InspectionTask {

    /** 任务状态。 */
    public enum Status {
        /** 待启动。 */
        PENDING,
        /** 执行中。 */
        IN_PROGRESS,
        /** 已完成。 */
        COMPLETED,
        /** 执行失败。 */
        FAILED,
        /** 已中止。 */
        ABORTED
    }

    /** 简化航点（任务内部记录的航点摘要）。 */
    public static final class Wp {
        public final int seq;
        public final double lat;
        public final double lon;
        public final double alt;
        public final Waypoint.Action action;

        public Wp(int seq, double lat, double lon, double alt, Waypoint.Action action) {
            this.seq = seq;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.action = action;
        }
    }

    private final String id;
    private final String templateId;
    private volatile Status status;
    private final int assignedSysid;
    private volatile Instant startTime;
    private volatile Instant endTime;
    private volatile double progressPct;
    private final List<Wp> waypointsGenerated;
    private volatile int photosCaptured;
    private volatile int anomaliesFound;

    public InspectionTask(String id, String templateId, int assignedSysid,
                          List<Wp> waypointsGenerated) {
        this.id = id;
        this.templateId = templateId;
        this.assignedSysid = assignedSysid;
        this.status = Status.PENDING;
        this.waypointsGenerated = waypointsGenerated == null
                ? Collections.emptyList()
                : new ArrayList<>(waypointsGenerated);
    }

    public String id() { return id; }
    public String templateId() { return templateId; }
    public Status status() { return status; }
    public int assignedSysid() { return assignedSysid; }
    public Instant startTime() { return startTime; }
    public Instant endTime() { return endTime; }
    public double progressPct() { return progressPct; }
    public List<Wp> waypointsGenerated() { return Collections.unmodifiableList(waypointsGenerated); }
    public int photosCaptured() { return photosCaptured; }
    public int anomaliesFound() { return anomaliesFound; }

    /** 启动任务：PENDING → IN_PROGRESS。 */
    public void start() {
        if (status == Status.PENDING) {
            status = Status.IN_PROGRESS;
            startTime = Instant.now();
        }
    }

    /** 中止任务：IN_PROGRESS → ABORTED。 */
    public void abort() {
        if (status == Status.IN_PROGRESS || status == Status.PENDING) {
            status = Status.ABORTED;
            endTime = Instant.now();
        }
    }

    /** 完成任务：IN_PROGRESS → COMPLETED。 */
    public void complete() {
        if (status == Status.IN_PROGRESS) {
            status = Status.COMPLETED;
            endTime = Instant.now();
            progressPct = 100.0;
        }
    }

    /** 标记失败：任意 → FAILED。 */
    public void fail() {
        status = Status.FAILED;
        endTime = Instant.now();
    }

    /** 更新进度百分比。 */
    public void updateProgress(double pct) {
        this.progressPct = Math.max(0, Math.min(100, pct));
    }

    /** 增加拍摄照片计数。 */
    public void addPhotos(int count) {
        this.photosCaptured += count;
    }

    /** 设置异常发现数。 */
    public void setAnomaliesFound(int count) {
        this.anomaliesFound = count;
    }
}