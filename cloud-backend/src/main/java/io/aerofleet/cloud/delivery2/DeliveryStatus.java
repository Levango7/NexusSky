package io.aerofleet.cloud.delivery2;

/**
 * 配送状态追踪模型。
 * <p>
 * 描述配送任务的实时状态，包括阶段、位置、剩余距离和负载状况。
 */
public class DeliveryStatus {

    /** 配送阶段枚举。 */
    public enum Phase {
        CREATED, ASSIGNED, PICKED_UP, IN_TRANSIT, APPROACHING, DELIVERING, DELIVERED
    }

    /** 负载状况枚举。 */
    public enum PayloadCondition {
        NORMAL, TEMPERATURE_ALERT, DAMAGED
    }

    private String taskId;
    private volatile Phase phase;
    private double currentLat;
    private double currentLon;
    private double remainingDistanceKm;
    private double estimatedArrivalMin;
    private PayloadCondition payloadCondition;

    public DeliveryStatus() {
    }

    public DeliveryStatus(String taskId, Phase phase, double currentLat, double currentLon,
                          double remainingDistanceKm, double estimatedArrivalMin,
                          PayloadCondition payloadCondition) {
        this.taskId = taskId;
        this.phase = phase;
        this.currentLat = currentLat;
        this.currentLon = currentLon;
        this.remainingDistanceKm = remainingDistanceKm;
        this.estimatedArrivalMin = estimatedArrivalMin;
        this.payloadCondition = payloadCondition;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public double getCurrentLat() {
        return currentLat;
    }

    public void setCurrentLat(double currentLat) {
        this.currentLat = currentLat;
    }

    public double getCurrentLon() {
        return currentLon;
    }

    public void setCurrentLon(double currentLon) {
        this.currentLon = currentLon;
    }

    public double getRemainingDistanceKm() {
        return remainingDistanceKm;
    }

    public void setRemainingDistanceKm(double remainingDistanceKm) {
        this.remainingDistanceKm = remainingDistanceKm;
    }

    public double getEstimatedArrivalMin() {
        return estimatedArrivalMin;
    }

    public void setEstimatedArrivalMin(double estimatedArrivalMin) {
        this.estimatedArrivalMin = estimatedArrivalMin;
    }

    public PayloadCondition getPayloadCondition() {
        return payloadCondition;
    }

    public void setPayloadCondition(PayloadCondition payloadCondition) {
        this.payloadCondition = payloadCondition;
    }
}