package io.aerofleet.sim.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * M10 任务执行器：接收任务分配，执行任务，上报状态。
 * 状态机：IDLE -> ASSIGNED -> IN_PROGRESS -> COMPLETED/FAILED/ABORTED
 */
public class TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);
    private final int sysid;
    private volatile String currentTaskId;
    private volatile String taskStatus = "IDLE";
    private final AtomicInteger progressPercent = new AtomicInteger(0);

    public TaskExecutor(int sysid) {
        this.sysid = sysid;
    }

    public void assignTask(String taskId) {
        this.currentTaskId = taskId;
        this.taskStatus = "ASSIGNED";
        this.progressPercent.set(0);
        log.info("[scheduling] sysid={} task {} assigned", sysid, taskId);
    }

    public void startTask() {
        if ("ASSIGNED".equals(taskStatus)) {
            taskStatus = "IN_PROGRESS";
            log.info("[scheduling] sysid={} task {} started", sysid, currentTaskId);
        }
    }

    public void tick() {
        if ("IN_PROGRESS".equals(taskStatus)) {
            int p = progressPercent.incrementAndGet();
            if (p >= 100) {
                taskStatus = "COMPLETED";
                log.info("[scheduling] sysid={} task {} completed", sysid, currentTaskId);
            }
        }
    }

    public void abortTask() {
        taskStatus = "ABORTED";
        log.warn("[scheduling] sysid={} task {} aborted", sysid, currentTaskId);
    }

    public String getTaskId() { return currentTaskId; }
    public String getTaskStatus() { return taskStatus; }
    public int getProgress() { return progressPercent.get(); }
}
