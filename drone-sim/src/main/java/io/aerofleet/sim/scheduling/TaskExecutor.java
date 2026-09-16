package io.aerofleet.sim.scheduling;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * M10 任务执行器：接收任务分配，执行任务，上报状态。
 * 状态机：IDLE -> ASSIGNED -> IN_PROGRESS -> COMPLETED/FAILED/ABORTED
 */
public class TaskExecutor {

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
        System.out.println("[scheduling] sysid=" + sysid + " task " + taskId + " assigned");
    }

    public void startTask() {
        if ("ASSIGNED".equals(taskStatus)) {
            taskStatus = "IN_PROGRESS";
            System.out.println("[scheduling] sysid=" + sysid + " task " + currentTaskId + " started");
        }
    }

    public void tick() {
        if ("IN_PROGRESS".equals(taskStatus)) {
            int p = progressPercent.incrementAndGet();
            if (p >= 100) {
                taskStatus = "COMPLETED";
                System.out.println("[scheduling] sysid=" + sysid + " task " + currentTaskId + " completed");
            }
        }
    }

    public void abortTask() {
        taskStatus = "ABORTED";
        System.out.println("[scheduling] sysid=" + sysid + " task " + currentTaskId + " aborted");
    }

    public String getTaskId() { return currentTaskId; }
    public String getTaskStatus() { return taskStatus; }
    public int getProgress() { return progressPercent.get(); }
}
