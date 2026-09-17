package io.aerofleet.cloud.scheduling;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * M10 任务请求 DTO。
 * <p>
 * 字段约束（JSR303 Bean Validation）：
 * <ul>
 *   <li>{@code taskId} — 非空，长度 1~64</li>
 *   <li>{@code taskType} — 非空，仅允许 SURVEY / SPRAY / RELAY / RESCUE</li>
 *   <li>{@code priority} — 0~10</li>
 *   <li>{@code targetLat} — -90~90（纬度）</li>
 *   <li>{@code targetLon} — -180~180（经度）</li>
 * </ul>
 */
public class TaskRequest {
    @NotBlank
    @Size(min = 1, max = 64)
    private String taskId;

    @NotNull
    @Pattern(regexp = "SURVEY|SPRAY|RELAY|RESCUE")
    private String taskType; // SURVEY / SPRAY / RELAY / RESCUE

    @Min(0)
    @Max(10)
    private int priority;    // 0=lowest, 10=highest

    @Min(-90)
    @Max(90)
    private double targetLat;

    @Min(-180)
    @Max(180)
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
