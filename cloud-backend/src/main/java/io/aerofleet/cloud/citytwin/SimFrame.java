package io.aerofleet.cloud.citytwin;

/**
 * 模拟时间线帧，表示灾害推演中某一时间步的状态快照。
 */
public class SimFrame {

    private int timestampMin;
    private String description;
    private double affectedAreaKm2;
    private String severity;

    public SimFrame() {
    }

    public SimFrame(int timestampMin, String description, double affectedAreaKm2, String severity) {
        this.timestampMin = timestampMin;
        this.description = description;
        this.affectedAreaKm2 = affectedAreaKm2;
        this.severity = severity;
    }

    public int getTimestampMin() {
        return timestampMin;
    }

    public void setTimestampMin(int timestampMin) {
        this.timestampMin = timestampMin;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getAffectedAreaKm2() {
        return affectedAreaKm2;
    }

    public void setAffectedAreaKm2(double affectedAreaKm2) {
        this.affectedAreaKm2 = affectedAreaKm2;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }
}