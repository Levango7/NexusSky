package io.aerofleet.cloud.geofence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * GeofenceBreachEvent 的 JPA 持久化实体。
 * <p>
 * 越界事件由 {@link GeofenceMonitor} 生成，存入 {@link GeofenceStore} 的内存缓存，
 * 同时通过本实体持久化到数据库以支持重启恢复和历史查询。
 * <p>
 * 经验参考：从不可变值对象迁移到 JPA Entity 时，需去掉字段 {@code final} 修饰符、
 * 添加无参构造器、为所有字段添加 setter。
 * 来源：2026-09-21-immutable-value-object-to-jpa-entity-migration
 */
@Entity
@Table(name = "geofence_breach_event")
public class GeofenceBreachEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "sysid")
    private int sysid;

    @Column(name = "zone_id")
    private int zoneId;

    @Column(name = "zone_name")
    private String zoneName;

    @Column(name = "breach_type")
    private String breachType;

    @Column(name = "lat")
    private double lat;

    @Column(name = "lon")
    private double lon;

    @Column(name = "timestamp_ms")
    private long timestampMs;

    /** 租户 ID（数据隔离）。 */
    @Column(name = "tenant_id")
    private Integer tenantId;

    /** JPA 要求的无参构造器。 */
    public GeofenceBreachEventEntity() {
    }

    // --- getter / setter ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public int getSysid() { return sysid; }
    public void setSysid(int sysid) { this.sysid = sysid; }

    public int getZoneId() { return zoneId; }
    public void setZoneId(int zoneId) { this.zoneId = zoneId; }

    public String getZoneName() { return zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }

    public String getBreachType() { return breachType; }
    public void setBreachType(String breachType) { this.breachType = breachType; }

    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }

    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }

    public long getTimestampMs() { return timestampMs; }
    public void setTimestampMs(long timestampMs) { this.timestampMs = timestampMs; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer tenantId) { this.tenantId = tenantId; }

    // --- 与值对象的转换 ---

    /**
     * 转换为不可变值对象 {@link GeofenceBreachEvent}。
     */
    public GeofenceBreachEvent toEvent() {
        return new GeofenceBreachEvent(
                sysid, zoneId, zoneName,
                GeofenceBreachEvent.BreachType.valueOf(breachType),
                lat, lon, timestampMs);
    }

    /**
     * 从不可变值对象创建 JPA Entity。
     */
    public static GeofenceBreachEventEntity fromEvent(GeofenceBreachEvent event) {
        GeofenceBreachEventEntity entity = new GeofenceBreachEventEntity();
        entity.setSysid(event.getSysid());
        entity.setZoneId(event.getZoneId());
        entity.setZoneName(event.getZoneName());
        entity.setBreachType(event.getBreachType().name());
        entity.setLat(event.getLat());
        entity.setLon(event.getLon());
        entity.setTimestampMs(event.getTimestampMs());
        return entity;
    }
}