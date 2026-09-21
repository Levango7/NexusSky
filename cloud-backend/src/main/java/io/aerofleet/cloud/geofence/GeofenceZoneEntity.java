package io.aerofleet.cloud.geofence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GeofenceZone 的 JPA 持久化实体。
 * <p>
 * 采用混合模式：内存缓存（{@link GeofenceStore} 中的 {@link java.util.concurrent.ConcurrentHashMap}）
 * 保证并发读性能，本实体负责重启后的状态恢复。
 * <p>
 * 经验参考：从不可变值对象迁移到 JPA Entity 时，需去掉字段 {@code final} 修饰符、
 * 添加无参构造器、为所有字段添加 setter。
 * 来源：2026-09-21-immutable-value-object-to-jpa-entity-migration
 */
@Entity
@Table(name = "geofence_zone")
public class GeofenceZoneEntity {

    @Id
    @Column(name = "id")
    private int id;

    @Column(name = "name")
    private String name;

    @Column(name = "type")
    private String type;

    @Column(name = "center_lat")
    private double centerLat;

    @Column(name = "center_lon")
    private double centerLon;

    @Column(name = "radius_m")
    private double radiusM;

    @Column(name = "points")
    @Convert(converter = GeoPointListConverter.class)
    private List<GeofenceZone.GeoPoint> points;

    @Column(name = "action")
    private String action;

    @Column(name = "enabled")
    private boolean enabled;

    @Column(name = "created_at_ms")
    private long createdAtMs;

    /** JPA 要求的无参构造器。 */
    public GeofenceZoneEntity() {
    }

    // --- getter / setter ---

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public double getCenterLat() { return centerLat; }
    public void setCenterLat(double centerLat) { this.centerLat = centerLat; }

    public double getCenterLon() { return centerLon; }
    public void setCenterLon(double centerLon) { this.centerLon = centerLon; }

    public double getRadiusM() { return radiusM; }
    public void setRadiusM(double radiusM) { this.radiusM = radiusM; }

    public List<GeofenceZone.GeoPoint> getPoints() { return points; }
    public void setPoints(List<GeofenceZone.GeoPoint> points) { this.points = points; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCreatedAtMs() { return createdAtMs; }
    public void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    // --- 与值对象的转换 ---

    /**
     * 转换为不可变值对象 {@link GeofenceZone}。
     * <p>
     * 根据 type 字段选择工厂方法：CIRCLE 使用 {@link GeofenceZone#circleZone}，
     * POLYGON 使用 {@link GeofenceZone#polygonZone}。然后通过 {@link GeofenceZone#withEnabled}
     * 同步启用状态和 createdAtMs。
     */
    public GeofenceZone toZone() {
        GeofenceZone.Type typeEnum = GeofenceZone.Type.valueOf(type);
        GeofenceZone.Action actionEnum = GeofenceZone.Action.valueOf(action);

        GeofenceZone zone;
        if (typeEnum == GeofenceZone.Type.CIRCLE) {
            zone = GeofenceZone.circleZone(id, name, centerLat, centerLon, radiusM, actionEnum);
        } else {
            zone = GeofenceZone.polygonZone(id, name,
                    points != null ? points : Collections.emptyList(), actionEnum);
        }

        // 同步 enabled 状态（工厂方法默认 enabled=true）
        if (!enabled) {
            zone = zone.withEnabled(false);
        }
        return zone;
    }

    /**
     * 从不可变值对象创建 JPA Entity。
     */
    public static GeofenceZoneEntity fromZone(GeofenceZone zone) {
        GeofenceZoneEntity entity = new GeofenceZoneEntity();
        entity.setId(zone.getId());
        entity.setName(zone.getName());
        entity.setType(zone.getType().name());
        entity.setCenterLat(zone.getCenterLat());
        entity.setCenterLon(zone.getCenterLon());
        entity.setRadiusM(zone.getRadiusM());
        entity.setPoints(zone.getPoints() != null ? new ArrayList<>(zone.getPoints()) : new ArrayList<>());
        entity.setAction(zone.getAction().name());
        entity.setEnabled(zone.isEnabled());
        entity.setCreatedAtMs(zone.getCreatedAtMs());
        return entity;
    }
}

/**
 * {@link GeofenceZone.GeoPoint} 列表的 JSON 序列化转换器。
 * <p>
 * 不引入 Jackson 依赖，手动拼接 {@code [{"lat":1.0,"lon":2.0},...]} 格式。
 * 空列表序列化为空字符串 {@code ""}，反序列化空字符串为空列表。
 */
class GeoPointListConverter implements AttributeConverter<List<GeofenceZone.GeoPoint>, String> {

    @Override
    public String convertToDatabaseColumn(List<GeofenceZone.GeoPoint> points) {
        if (points == null || points.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"lat\":").append(points.get(i).lat())
              .append(",\"lon\":").append(points.get(i).lon()).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public List<GeofenceZone.GeoPoint> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        List<GeofenceZone.GeoPoint> result = new ArrayList<>();
        // 手动解析 [{"lat":1.0,"lon":2.0},...] 格式
        int i = 0;
        while (i < json.length()) {
            int latStart = json.indexOf("\"lat\":", i);
            if (latStart == -1) {
                break;
            }
            latStart += 6; // 跳过 "lat":
            int latEnd = findNumberEnd(json, latStart);
            double lat = Double.parseDouble(json.substring(latStart, latEnd));

            int lonStart = json.indexOf("\"lon\":", latEnd);
            if (lonStart == -1) {
                break;
            }
            lonStart += 6; // 跳过 "lon":
            int lonEnd = findNumberEnd(json, lonStart);
            double lon = Double.parseDouble(json.substring(lonStart, lonEnd));

            result.add(new GeofenceZone.GeoPoint(lat, lon));
            i = lonEnd;
        }
        return result;
    }

    /** 找到数字值的结束位置（遇到逗号、}、] 等非数字字符）。 */
    private static int findNumberEnd(String s, int start) {
        int i = start;
        // 跳过可能的负号
        if (i < s.length() && s.charAt(i) == '-') {
            i++;
        }
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '-') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }
}