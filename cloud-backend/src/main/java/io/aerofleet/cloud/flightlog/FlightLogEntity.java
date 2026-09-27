package io.aerofleet.cloud.flightlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * FlightLog JPA 实体，映射 flight_log 表。
 * <p>
 * 用于将飞行日志事件（telemetry/alert/mission/connectivity）持久化到 PostgreSQL，
 * 替代或补充现有的 JSONL 文件路径。字段名遵循 camelCase，Hibernate
 * SpringPhysicalNamingStrategy 自动转换为下划线列名（如 relativeAlt → relative_alt）。
 */
@Entity
@Table(name = "flight_log")
public class FlightLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "timestamp")
    private Instant timestamp;

    @Column(name = "type")
    private String type;

    @Column(name = "sysid")
    private int sysid;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lon")
    private Double lon;

    @Column(name = "relative_alt")
    private Double relativeAlt;

    @Column(name = "groundspeed")
    private Double groundspeed;

    @Column(name = "battery")
    private Integer battery;

    @Column(name = "voltage")
    private Integer voltage;

    @Column(name = "mode")
    private String mode;

    @Column(name = "armed")
    private Boolean armed;

    @Column(name = "online")
    private Boolean online;

    @Column(name = "severity")
    private Integer severity;

    @Column(name = "text")
    private String text;

    @Column(name = "tenant_id")
    private Integer tenantId;

    /** JPA 要求的无参构造器 */
    public FlightLogEntity() {
    }

    /** 全参构造器 */
    public FlightLogEntity(Long id, Instant timestamp, String type, int sysid,
                           Double lat, Double lon, Double relativeAlt, Double groundspeed,
                           Integer battery, Integer voltage, String mode, Boolean armed,
                           Boolean online, Integer severity, String text, Integer tenantId) {
        this.id = id;
        this.timestamp = timestamp;
        this.type = type;
        this.sysid = sysid;
        this.lat = lat;
        this.lon = lon;
        this.relativeAlt = relativeAlt;
        this.groundspeed = groundspeed;
        this.battery = battery;
        this.voltage = voltage;
        this.mode = mode;
        this.armed = armed;
        this.online = online;
        this.severity = severity;
        this.text = text;
        this.tenantId = tenantId;
    }

    /**
     * 从事件 Map 构造 FlightLogEntity（工厂方法）。
     * <p>
     * Map 的 key 名与 JSONL 路径一致：t/type/sysid/lat/lon/relativeAlt/groundspeed/
     * battery/voltage/mode/armed/online/severity/text。
     *
     * @param event 事件 Map
     * @return FlightLogEntity 实例
     */
    @SuppressWarnings("unchecked")
    public static FlightLogEntity from(Map<String, Object> event) {
        FlightLogEntity e = new FlightLogEntity();
        e.type = (String) event.get("type");
        e.sysid = event.get("sysid") instanceof Number n ? n.intValue() : 0;
        e.lat = event.get("lat") instanceof Number n ? n.doubleValue() : null;
        e.lon = event.get("lon") instanceof Number n ? n.doubleValue() : null;
        e.relativeAlt = event.get("relativeAlt") instanceof Number n ? n.doubleValue() : null;
        e.groundspeed = event.get("groundspeed") instanceof Number n ? n.doubleValue() : null;
        e.battery = event.get("battery") instanceof Number n ? n.intValue() : null;
        e.voltage = event.get("voltage") instanceof Number n ? n.intValue() : null;
        e.mode = (String) event.get("mode");
        e.armed = event.get("armed") instanceof Boolean b ? b : null;
        e.online = event.get("online") instanceof Boolean b ? b : null;
        e.severity = event.get("severity") instanceof Number n ? n.intValue() : null;
        e.text = (String) event.get("text");
        e.tenantId = event.get("tenantId") instanceof Number n ? n.intValue() : null;

        // timestamp: 优先用 Map 中的 "t" 字段（ISO LocalDateTime 字符串），否则用当前时间
        Object t = event.get("t");
        if (t instanceof String s) {
            try {
                e.timestamp = java.time.LocalDateTime.parse(s,
                        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant();
            } catch (Exception ex) {
                e.timestamp = Instant.now();
            }
        } else {
            e.timestamp = Instant.now();
        }

        return e;
    }

    /**
     * 将 Entity 转换为 Map（用于 query() 返回格式兼容 JSONL 路径）。
     * <p>
     * 返回的 Map key 名与 JSONL 路径一致：t/type/sysid/lat/lon/relativeAlt/groundspeed/
     * battery/voltage/mode/armed/online/severity/text。
     *
     * @return Map<String, Object>
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("t", timestamp != null
                ? timestamp.atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime()
                        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : null);
        m.put("type", type);
        m.put("sysid", sysid);
        m.put("lat", lat);
        m.put("lon", lon);
        m.put("relativeAlt", relativeAlt);
        m.put("groundspeed", groundspeed);
        m.put("battery", battery);
        m.put("voltage", voltage);
        m.put("mode", mode);
        m.put("armed", armed);
        m.put("online", online);
        m.put("severity", severity);
        m.put("text", text);
        return m;
    }

    // --- getters / setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getSysid() {
        return sysid;
    }

    public void setSysid(int sysid) {
        this.sysid = sysid;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public Double getLon() {
        return lon;
    }

    public void setLon(Double lon) {
        this.lon = lon;
    }

    public Double getRelativeAlt() {
        return relativeAlt;
    }

    public void setRelativeAlt(Double relativeAlt) {
        this.relativeAlt = relativeAlt;
    }

    public Double getGroundspeed() {
        return groundspeed;
    }

    public void setGroundspeed(Double groundspeed) {
        this.groundspeed = groundspeed;
    }

    public Integer getBattery() {
        return battery;
    }

    public void setBattery(Integer battery) {
        this.battery = battery;
    }

    public Integer getVoltage() {
        return voltage;
    }

    public void setVoltage(Integer voltage) {
        this.voltage = voltage;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Boolean getArmed() {
        return armed;
    }

    public void setArmed(Boolean armed) {
        this.armed = armed;
    }

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

    public Integer getSeverity() {
        return severity;
    }

    public void setSeverity(Integer severity) {
        this.severity = severity;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer tenantId) {
        this.tenantId = tenantId;
    }
}