package io.aerofleet.cloud.show;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 表演任务（一次编队表演的执行实例）。
 * <p>
 * 基于队形定义创建，管理表演任务生命周期：
 * <pre>
 *   CREATED → DEPLOYING → PERFORMING → COMPLETED
 *                         ↘ → ABORTED
 * </pre>
 * 关联无人机 sysid 列表、动作序列、音乐同步配置。
 */
@Entity
@Table(name = "show_task")
public class ShowTask {

    @Id
    private String id;
    private String name;
    private String formationId;
    @Enumerated(EnumType.STRING)
    private volatile ShowStatus status;

    @ElementCollection
    @CollectionTable(name = "show_task_drones", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "sysid")
    private List<Integer> droneSysids;

    @Column(columnDefinition = "TIMESTAMP")
    private volatile Instant startTime;
    private int durationSec;
    private double altitudeM;
    private double centerLat;
    private double centerLon;

    /** JPA 无参构造器。 */
    public ShowTask() {
    }

    public ShowTask(String id, String name, String formationId,
                    List<Integer> droneSysids, int durationSec,
                    double altitudeM, double centerLat, double centerLon) {
        this.id = id;
        this.name = name;
        this.formationId = formationId;
        this.status = ShowStatus.CREATED;
        this.droneSysids = droneSysids == null
                ? Collections.emptyList()
                : new ArrayList<>(droneSysids);
        this.durationSec = durationSec;
        this.altitudeM = altitudeM;
        this.centerLat = centerLat;
        this.centerLon = centerLon;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getFormationId() { return formationId; }
    public ShowStatus getStatus() { return status; }
    public List<Integer> getDroneSysids() { return Collections.unmodifiableList(droneSysids); }
    public Instant getStartTime() { return startTime; }
    public int getDurationSec() { return durationSec; }
    public double getAltitudeM() { return altitudeM; }
    public double getCenterLat() { return centerLat; }
    public double getCenterLon() { return centerLon; }

    public void setId(String id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setFormationId(String formationId) { this.formationId = formationId; }
    public void setStatus(ShowStatus status) { this.status = status; }
    public void setDroneSysids(List<Integer> droneSysids) {
        this.droneSysids = droneSysids == null
                ? Collections.emptyList()
                : new ArrayList<>(droneSysids);
    }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public void setDurationSec(int durationSec) { this.durationSec = durationSec; }
    public void setAltitudeM(double altitudeM) { this.altitudeM = altitudeM; }
    public void setCenterLat(double centerLat) { this.centerLat = centerLat; }
    public void setCenterLon(double centerLon) { this.centerLon = centerLon; }

    /** 启动部署：CREATED → DEPLOYING。 */
    public synchronized void deploy() {
        if (status == ShowStatus.CREATED) {
            status = ShowStatus.DEPLOYING;
            startTime = Instant.now();
        }
    }

    /** 开始表演：DEPLOYING → PERFORMING。 */
    public synchronized void perform() {
        if (status == ShowStatus.DEPLOYING) {
            status = ShowStatus.PERFORMING;
        }
    }

    /** 完成表演：PERFORMING → COMPLETED。 */
    public synchronized void complete() {
        if (status == ShowStatus.PERFORMING) {
            status = ShowStatus.COMPLETED;
        }
    }

    /** 中止表演：任意非终态 → ABORTED。 */
    public synchronized void abort() {
        if (status != ShowStatus.COMPLETED && status != ShowStatus.ABORTED) {
            status = ShowStatus.ABORTED;
        }
    }
}
