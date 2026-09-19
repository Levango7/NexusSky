package io.aerofleet.cloud.surveillance;

import java.util.Objects;

/**
 * 安防事件模型。
 * <p>
 * 用于表示来自安防设备的事件通知（移动侦测、入侵报警、火警、门禁等），
 * 通常通过 ONVIF 事件订阅（WS-BaseNotification）接收，或由前端通过 SSE 推送消费。
 */
public class SurveillanceEvent {

    /** 事件类型枚举。 */
    public enum EventType {
        /** 移动侦测。 */
        MOTION,
        /** 入侵报警（周界越界）。 */
        INTRUSION,
        /** 火警。 */
        FIRE,
        /** 门禁事件。 */
        DOOR,
        /** 自定义/厂商扩展事件。 */
        CUSTOM
    }

    /** 事件严重级别。 */
    public enum Severity {
        INFO,
        WARN,
        CRITICAL
    }

    /** 来源设备 ID。 */
    public final String deviceId;
    /** 事件类型。 */
    public final EventType eventType;
    /** 严重级别。 */
    public final Severity severity;
    /** 事件描述。 */
    public final String description;
    /** 事件发生地纬度（可选，0 表示未提供）。 */
    public final double lat;
    /** 事件发生地经度（可选，0 表示未提供）。 */
    public final double lon;
    /** 事件时间戳（System.currentTimeMillis()）。 */
    public final long timestampMs;

    public SurveillanceEvent(String deviceId, EventType eventType, Severity severity,
                             String description, double lat, double lon, long timestampMs) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        this.deviceId = deviceId;
        this.eventType = eventType;
        this.severity = severity;
        this.description = description == null ? "" : description;
        this.lat = lat;
        this.lon = lon;
        this.timestampMs = timestampMs;
    }

    /** 创建当前时间戳的事件（便捷工厂方法）。 */
    public static SurveillanceEvent now(String deviceId, EventType eventType, Severity severity,
                                        String description) {
        return new SurveillanceEvent(deviceId, eventType, severity, description,
                0.0, 0.0, System.currentTimeMillis());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SurveillanceEvent)) return false;
        SurveillanceEvent that = (SurveillanceEvent) o;
        return timestampMs == that.timestampMs
                && deviceId.equals(that.deviceId)
                && eventType == that.eventType
                && severity == that.severity
                && Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, eventType, severity, description, timestampMs);
    }

    @Override
    public String toString() {
        return "SurveillanceEvent{deviceId=" + deviceId
                + ", type=" + eventType
                + ", severity=" + severity
                + ", ts=" + timestampMs + "}";
    }
}