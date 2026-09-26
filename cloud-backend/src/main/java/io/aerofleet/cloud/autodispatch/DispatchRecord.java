package io.aerofleet.cloud.autodispatch;

import java.util.Collections;
import java.util.List;

/**
 * 出警记录（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 描述一次自动出警的完整记录：触发报警、派遣时间、目标位置、派遣状态、
 * 已派遣无人机列表、中止时间、完成时间。用于出警历史查询与状态追踪。
 * <p>
 * 不可变值对象（除 abortTime/completeTime 外），便于在并发结构中安全共享。
 *
 * @see AutoDispatchService
 * @see DispatchResult
 */
public class DispatchRecord {

    /** 派遣 ID。 */
    private final String dispatchId;
    /** 关联的报警事件 ID。 */
    private final String alarmId;
    /** 触发时间戳（毫秒）。 */
    private final long triggerTime;
    /** 目标纬度（WGS84，度）。 */
    private final double lat;
    /** 目标经度（WGS84，度）。 */
    private final double lon;
    /** 派遣状态（SUCCESS/NO_DRONE/PARTIAL/ABORTED/COMPLETED）。 */
    private volatile String status;
    /** 已派遣的无人机列表。 */
    private final List<DispatchResult.DispatchedDrone> dispatchedDrones;
    /** 中止时间戳（毫秒，0 表示未中止）。 */
    private volatile long abortTime;
    /** 完成时间戳（毫秒，0 表示未完成）。 */
    private volatile long completeTime;

    public DispatchRecord(String dispatchId, String alarmId, long triggerTime,
                          double lat, double lon, String status,
                          List<DispatchResult.DispatchedDrone> dispatchedDrones) {
        this.dispatchId = dispatchId;
        this.alarmId = alarmId;
        this.triggerTime = triggerTime;
        this.lat = lat;
        this.lon = lon;
        this.status = status;
        this.dispatchedDrones = dispatchedDrones == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(dispatchedDrones);
        this.abortTime = 0L;
        this.completeTime = 0L;
    }

    public String getDispatchId() {
        return dispatchId;
    }

    public String getAlarmId() {
        return alarmId;
    }

    public long getTriggerTime() {
        return triggerTime;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<DispatchResult.DispatchedDrone> getDispatchedDrones() {
        return dispatchedDrones;
    }

    public long getAbortTime() {
        return abortTime;
    }

    public void setAbortTime(long abortTime) {
        this.abortTime = abortTime;
    }

    public long getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(long completeTime) {
        this.completeTime = completeTime;
    }

    /** 标记出警任务已中止。 */
    public void markAborted() {
        this.abortTime = System.currentTimeMillis();
        this.status = "ABORTED";
    }

    /** 标记出警任务已完成。 */
    public void markCompleted() {
        this.completeTime = System.currentTimeMillis();
        this.status = "COMPLETED";
    }

    /** 是否处于进行中状态（未中止且未完成）。 */
    public boolean isActive() {
        return abortTime == 0L && completeTime == 0L;
    }

    @Override
    public String toString() {
        return "DispatchRecord{dispatchId=" + dispatchId
                + ", alarmId=" + alarmId
                + ", triggerTime=" + triggerTime
                + ", lat=" + lat
                + ", lon=" + lon
                + ", status=" + status
                + ", drones=" + dispatchedDrones.size()
                + ", abortTime=" + abortTime
                + ", completeTime=" + completeTime + '}';
    }
}