package io.aerofleet.cloud.scheduling;

/** M10 任务请求 DTO */
public class TaskRequest {
    private String taskId;
    private String taskType; // SURVEY / SPRAY / RELAY / RESCUE
    private int priority;    // 0=lowest, 9=highest
    private double targetLat;
    private double targetLon;
    private double targetAlt;

    public TaskRequest() {}

    public TaskRequest(String taskId, String taskType, int priority,
                       double targetLat, double targetLon, double targetAlt) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.priority = priority;
        this.targetLat = targetLat;
        this.targetLon = targetLon;
        this.targetAlt = targetAlt;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String v) { this.taskId = v; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String v) { this.taskType = v; }
    public int getPriority() { return priority; }
    public void setPriority(int v) { this.priority = v; }
    public double getTargetLat() { return targetLat; }
    public void setTargetLat(double v) { this.targetLat = v; }
    public double getTargetLon() { return targetLon; }
    public void setTargetLon(double v) { this.targetLon = v; }
    public double getTargetAlt() { return targetAlt; }
    public void setTargetAlt(double v) { this.targetAlt = v; }
}
