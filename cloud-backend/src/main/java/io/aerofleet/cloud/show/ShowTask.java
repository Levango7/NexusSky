package io.aerofleet.cloud.show;

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
public final class ShowTask {

    private final String id;
    private final String name;
    private final String formationId;
    private volatile ShowStatus status;
    private final List<Integer> droneSysids;
    private volatile Instant startTime;
    private final int durationSec;
    private final double altitudeM;
    private final double centerLat;
    private final double centerLon;

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

    /** 启动部署：CREATED → DEPLOYING。 */
    public void deploy() {
        if (status == ShowStatus.CREATED) {
            status = ShowStatus.DEPLOYING;
            startTime = Instant.now();
        }
    }

    /** 开始表演：DEPLOYING → PERFORMING。 */
    public void perform() {
        if (status == ShowStatus.DEPLOYING) {
            status = ShowStatus.PERFORMING;
        }
    }

    /** 完成表演：PERFORMING → COMPLETED。 */
    public void complete() {
        if (status == ShowStatus.PERFORMING || status == ShowStatus.DEPLOYING) {
            status = ShowStatus.COMPLETED;
        }
    }

    /** 中止表演：任意非终态 → ABORTED。 */
    public void abort() {
        if (status != ShowStatus.COMPLETED && status != ShowStatus.ABORTED) {
            status = ShowStatus.ABORTED;
        }
    }
}