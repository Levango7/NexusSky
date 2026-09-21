package io.aerofleet.cloud.delivery2;

import java.time.Instant;


import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 配送任务模型（P4-1 无人机物流配送）。
 * <p>
 * 支持应急物资空投、医疗样本运输、偏远地区配送等场景。
 */
@Entity
@Table(name = "delivery_task2")
public class DeliveryTask2 {

    /** 配送任务类型。 */
    public enum Type {
        EMERGENCY_SUPPLY, MEDICAL_SAMPLE, REGULAR_PARCEL
    }

    /** 配送任务状态。 */
    public enum Status {
        PENDING, IN_PROGRESS, DELIVERED, FAILED, ABORTED
    }

    /** 优先级。 */
    public enum Priority {
        HIGH, NORMAL, LOW
    }

    @Id
    private String id;
    @Enumerated(EnumType.STRING)
    private Type type;
    @Enumerated(EnumType.STRING)
    private Status status;
    private double senderLat;
    private double senderLon;
    private double receiverLat;
    private double receiverLon;
    private String receiverName;
    @Embedded
    private Payload payload;
    private Integer assignedSysid;
    @Enumerated(EnumType.STRING)
    private Priority priority;
    private Instant startTime;
    private Instant estimatedDeliveryTime;
    private Instant actualDeliveryTime;
    private double routeDistanceKm;

    public DeliveryTask2() {
    }

    public DeliveryTask2(String id, Type type, Status status,
                          double senderLat, double senderLon,
                          double receiverLat, double receiverLon,
                          String receiverName, Payload payload,
                          Integer assignedSysid, Priority priority) {
        this.id = id;
        this.type = type;
        this.status = status;
        this.senderLat = senderLat;
        this.senderLon = senderLon;
        this.receiverLat = receiverLat;
        this.receiverLon = receiverLon;
        this.receiverName = receiverName;
        this.payload = payload;
        this.assignedSysid = assignedSysid;
        this.priority = priority;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public double getSenderLat() {
        return senderLat;
    }

    public void setSenderLat(double senderLat) {
        this.senderLat = senderLat;
    }

    public double getSenderLon() {
        return senderLon;
    }

    public void setSenderLon(double senderLon) {
        this.senderLon = senderLon;
    }

    public double getReceiverLat() {
        return receiverLat;
    }

    public void setReceiverLat(double receiverLat) {
        this.receiverLat = receiverLat;
    }

    public double getReceiverLon() {
        return receiverLon;
    }

    public void setReceiverLon(double receiverLon) {
        this.receiverLon = receiverLon;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public Payload getPayload() {
        return payload;
    }

    public void setPayload(Payload payload) {
        this.payload = payload;
    }

    public Integer getAssignedSysid() {
        return assignedSysid;
    }

    public void setAssignedSysid(Integer assignedSysid) {
        this.assignedSysid = assignedSysid;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEstimatedDeliveryTime() {
        return estimatedDeliveryTime;
    }

    public void setEstimatedDeliveryTime(Instant estimatedDeliveryTime) {
        this.estimatedDeliveryTime = estimatedDeliveryTime;
    }

    public Instant getActualDeliveryTime() {
        return actualDeliveryTime;
    }

    public void setActualDeliveryTime(Instant actualDeliveryTime) {
        this.actualDeliveryTime = actualDeliveryTime;
    }

    public double getRouteDistanceKm() {
        return routeDistanceKm;
    }

    public void setRouteDistanceKm(double routeDistanceKm) {
        this.routeDistanceKm = routeDistanceKm;
    }
}