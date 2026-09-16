package io.aerofleet.cloud.scheduling;

/** M10 任务分配结果 DTO */
public class AssignmentResult {
    private String taskId;
    private int assignedSysid;
    private double score;       // 分配评分 0-100
    private String reason;      // 分配理由
    private boolean success;

    public AssignmentResult() {}

    public AssignmentResult(String taskId, int assignedSysid, double score, String reason, boolean success) {
        this.taskId = taskId;
        this.assignedSysid = assignedSysid;
        this.score = score;
        this.reason = reason;
        this.success = success;
    }

    public String getTaskId() { return taskId; }
    public int getAssignedSysid() { return assignedSysid; }
    public double getScore() { return score; }
    public String getReason() { return reason; }
    public boolean isSuccess() { return success; }
    public void setTaskId(String v) { this.taskId = v; }
    public void setAssignedSysid(int v) { this.assignedSysid = v; }
    public void setScore(double v) { this.score = v; }
    public void setReason(String v) { this.reason = v; }
    public void setSuccess(boolean v) { this.success = v; }
}