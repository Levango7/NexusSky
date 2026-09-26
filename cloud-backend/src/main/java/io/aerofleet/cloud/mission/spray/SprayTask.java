package io.aerofleet.cloud.mission.spray;

import java.util.ArrayList;
import java.util.List;

/**
 * 喷洒任务规划（FR-12~FR-15）。
 * <p>
 * 沿给定航线航点序列进行均匀喷洒的任务规划：
 * <ol>
 *   <li>构造期将航点序列按相邻航段切分为 {@link SpraySegment} 列表（FR-13）</li>
 *   <li>段长用 Haversine 公式计算，totalArea = totalLen × sprayWidth</li>
 *   <li>管理任务状态机：PENDING → RUNNING → PAUSED → COMPLETED / FAILED</li>
 *   <li>追踪已喷洒面积 / 覆盖率 / 剩余药量 / 当前段进度（FR-15）</li>
 * </ol>
 *
 * <p>线程安全：mutable 字段 volatile；由 {@link SprayTaskService} 在 REST 线程写，
 * 查询方法可并发读。
 */
public final class SprayTask {

    /** 喷洒任务状态机。 */
    public enum State { PENDING, RUNNING, PAUSED, COMPLETED, FAILED }

    /** 地球半径 m（Haversine 公式用，与 drone-sim GeoUtil 一致）。 */
    private static final double EARTH_R = 6371000.0;

    private final int taskId;
    private final int targetSysid;
    private final List<SpraySegment> segments;
    private final double totalArea;       // 任务总面积 m²
    private final double sprayWidth;      // 喷幅 m
    private final double capacityMl;      // 药箱容量 mL
    private final double targetRate;      // 目标流量 mL/s

    private volatile State state = State.PENDING;
    private volatile int currentSegment = 0;
    private volatile double coveredArea = 0;
    private volatile double remainingChemical;

    /**
     * 从航点序列构造喷洒任务（FR-13 航线均匀喷洒规划）。
     * <p>
     * 将相邻航点切分为喷洒段，每段标注起止点、段长、目标流量。
     *
     * @param taskId      任务 ID
     * @param sysid       目标无人机 sysid
     * @param waypoints   航点序列，每个元素为 {lat, lon}（至少 2 个航点）
     * @param targetRate  目标流量 mL/s
     * @param capacityMl  药箱容量 mL
     * @param sprayWidth  喷幅 m
     */
    public SprayTask(int taskId, int sysid, List<double[]> waypoints,
                     double targetRate, double capacityMl, double sprayWidth) {
        this.taskId = taskId;
        this.targetSysid = sysid;
        this.capacityMl = capacityMl;
        this.remainingChemical = capacityMl;
        this.sprayWidth = sprayWidth;
        this.targetRate = targetRate;
        this.segments = new ArrayList<>();
        double totalLen = 0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            double[] p1 = waypoints.get(i);
            double[] p2 = waypoints.get(i + 1);
            double len = haversine(p1[0], p1[1], p2[0], p2[1]);
            segments.add(new SpraySegment(i, p1[0], p1[1], p2[0], p2[1],
                    len, targetRate));
            totalLen += len;
        }
        this.totalArea = totalLen * sprayWidth;
    }

    // ---- 查询方法（FR-15）----

    public int taskId() { return taskId; }
    public int targetSysid() { return targetSysid; }
    public State state() { return state; }
    public int currentSegment() { return currentSegment; }
    public double coveredArea() { return coveredArea; }
    public double totalArea() { return totalArea; }
    public double remainingChemical() { return remainingChemical; }
    public double capacityMl() { return capacityMl; }
    public double sprayWidth() { return sprayWidth; }
    public double targetRate() { return targetRate; }
    public List<SpraySegment> segments() { return List.copyOf(segments); }
    public int segmentCount() { return segments.size(); }

    /** 覆盖率百分比（FR-15）。 */
    public double coverageRate() {
        return totalArea > 0 ? coveredArea / totalArea * 100 : 0;
    }

    /** 药量百分比。 */
    public double chemicalPercent() {
        return capacityMl > 0 ? remainingChemical / capacityMl * 100 : 0;
    }

    // ---- 状态推进方法（FR-14）----

    /** 启动任务：PENDING → RUNNING。 */
    public void start() {
        if (state == State.PENDING) {
            state = State.RUNNING;
        }
    }

    /** 暂停任务：RUNNING → PAUSED。 */
    public void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
        }
    }

    /** 恢复任务：PAUSED → RUNNING。 */
    public void resume() {
        if (state == State.PAUSED) {
            state = State.RUNNING;
        }
    }

    /** 停止任务：任意状态 → COMPLETED。 */
    public void stop() {
        if (state != State.COMPLETED && state != State.FAILED) {
            state = State.COMPLETED;
        }
    }

    /** 标记失败：任意状态 → FAILED。 */
    public void fail() {
        state = State.FAILED;
    }

    /** 标记当前段完成，推进到下一段；最后一段完成时任务转 COMPLETED。 */
    public void markSegmentComplete() {
        if (currentSegment + 1 < segments.size()) {
            currentSegment++;
        } else if (state == State.RUNNING) {
            state = State.COMPLETED;
        }
    }

    /** 更新已喷洒面积与剩余药量（由遥测回传驱动）。 */
    public void updateProgress(double coveredArea, double remainingChemical) {
        this.coveredArea = coveredArea;
        this.remainingChemical = remainingChemical;
    }

    // ---- Haversine 公式 ----

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}