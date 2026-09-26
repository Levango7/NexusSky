package io.aerofleet.cloud.alarm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Locale;
import java.util.UUID;

/**
 * 报警事件模型（M10 报警联动编排，FR-31）。
 * <p>
 * 描述安防设备（海康/大华/宇视）触发的一次报警，包含事件类型、严重程度、
 * 设备信息、地理位置与时间戳。{@link #acknowledged} 标记是否已被运维人员确认。
 * <p>
 * JPA 实体，持久化到 {@code alarm_event} 表。
 *
 * @see AlarmLinkageRule
 * @see AlarmLinkageEngine
 */
@Entity
@Table(name = "alarm_event")
public class AlarmEvent {

    /** 报警事件类型。 */
    public enum EventType {
        /** 移动侦测。 */
        MOTION,
        /** 周界入侵。 */
        INTRUSION,
        /** 火灾探测。 */
        FIRE,
        /** 门禁异常。 */
        DOOR,
        /** 自定义类型（第三方设备扩展）。 */
        CUSTOM
    }

    /** 报警严重程度（用于规则匹配的最低严重程度过滤）。 */
    public enum Severity {
        /** 信息（0）。 */
        INFO(0),
        /** 警告（1）。 */
        WARN(1),
        /** 严重（2）。 */
        CRITICAL(2);

        private final int level;

        Severity(int level) {
            this.level = level;
        }

        /** 数值层级，用于规则匹配时比较最低严重程度。 */
        public int level() {
            return level;
        }

        /** 大小写不敏感解析，未识别返回 {@link #INFO}。 */
        public static Severity fromString(String s) {
            if (s == null) {
                return INFO;
            }
            try {
                return Severity.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return INFO;
            }
        }
    }

    /** 事件 ID（UUID）。 */
    @Id
    private String id;
    /** 源设备 ID。 */
    private String sourceDeviceId;
    /** 源设备名称。 */
    private String sourceDeviceName;
    /** 事件类型。 */
    @Enumerated(EnumType.STRING)
    private EventType eventType;
    /** 严重程度。 */
    @Enumerated(EnumType.STRING)
    private Severity severity;
    /** 事件描述。 */
    private String description;
    /** 纬度（WGS84，度）。 */
    private double lat;
    /** 经度（WGS84，度）。 */
    private double lon;
    /** 海拔（米）。 */
    private double alt;
    /** 触发时间戳（毫秒）。 */
    private long timestampMs;
    /** 是否已被确认。 */
    private volatile boolean acknowledged;
    /** 租户 ID（用于租户隔离，null 表示全局管理员或未设置）。 */
    @Column(name = "tenant_id")
    private Integer tenantId;

    /** JPA 无参构造器。 */
    public AlarmEvent() {
    }

    public AlarmEvent(String id, String sourceDeviceId, String sourceDeviceName,
                      EventType eventType, Severity severity, String description,
                      double lat, double lon, double alt, long timestampMs,
                      boolean acknowledged) {
        this.id = id;
        this.sourceDeviceId = sourceDeviceId;
        this.sourceDeviceName = sourceDeviceName;
        this.eventType = eventType;
        this.severity = severity;
        this.description = description;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.timestampMs = timestampMs;
        this.acknowledged = acknowledged;
    }

    /**
     * 工厂方法：从安防设备上报字段构造报警事件。
     * <p>
     * 自动生成 UUID、当前时间戳，severity 默认 {@link Severity#WARN}，
     * alt 默认 0，acknowledged 默认 false。eventType 字符串大小写不敏感，
     * 未识别时回退为 {@link EventType#CUSTOM}。
     *
     * @param deviceId       源设备 ID
     * @param deviceName     源设备名称
     * @param eventType      事件类型字符串（MOTION/INTRUSION/FIRE/DOOR/CUSTOM）
     * @param lat            纬度
     * @param lon            经度
     * @param desc           事件描述
     * @return 新建报警事件
     */
    public static AlarmEvent from(String deviceId, String deviceName, String eventType,
                                  double lat, double lon, String desc) {
        EventType type = parseEventType(eventType);
        return new AlarmEvent(
                UUID.randomUUID().toString(),
                deviceId,
                deviceName,
                type,
                Severity.WARN,
                desc,
                lat,
                lon,
                0.0,
                System.currentTimeMillis(),
                false);
    }

    /** 大小写不敏感解析事件类型，未识别返回 {@link EventType#CUSTOM}。 */
    public static EventType parseEventType(String s) {
        if (s == null) {
            return EventType.CUSTOM;
        }
        try {
            return EventType.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EventType.CUSTOM;
        }
    }

    public String getId() {
        return id;
    }

    public String getSourceDeviceId() {
        return sourceDeviceId;
    }

    public String getSourceDeviceName() {
        return sourceDeviceName;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getDescription() {
        return description;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public double getAlt() {
        return alt;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setSourceDeviceId(String sourceDeviceId) {
        this.sourceDeviceId = sourceDeviceId;
    }

    public void setSourceDeviceName(String sourceDeviceName) {
        this.sourceDeviceName = sourceDeviceName;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public void setAlt(double alt) {
        this.alt = alt;
    }

    public void setTimestampMs(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer tenantId) {
        this.tenantId = tenantId;
    }

    /** 标记事件为已确认。 */
    public void acknowledge() {
        this.acknowledged = true;
    }

    @Override
    public String toString() {
        return "AlarmEvent{id=" + id
                + ", device=" + sourceDeviceId
                + ", type=" + eventType
                + ", severity=" + severity
                + ", lat=" + lat
                + ", lon=" + lon
                + ", ack=" + acknowledged + '}';
    }
}