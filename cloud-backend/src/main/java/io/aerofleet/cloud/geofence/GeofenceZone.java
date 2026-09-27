package io.aerofleet.cloud.geofence;

import java.util.Collections;
import java.util.List;

/**
 * 电子围栏区域定义。
 * <p>
 * 支持两种类型：
 * <ul>
 *   <li>{@link Type#CIRCLE CIRCLE}：圆形围栏，由中心经纬度 + 半径（米）描述。</li>
 *   <li>{@link Type#POLYGON POLYGON}：多边形围栏，由至少 3 个 {@link GeoPoint} 顶点描述。</li>
 * </ul>
 * <p>
 * 围栏类型 {@link FenceType} 决定围栏语义：
 * <ul>
 *   <li>{@link FenceType#KEEP_IN KEEP_IN}：允许活动区，无人机应在围栏内部，越出围栏触发 EXIT 事件。</li>
 *   <li>{@link FenceType#KEEP_OUT KEEP_OUT}：禁飞区，无人机应在围栏外部，进入围栏触发 ENTER 事件；
 *       支持接近缓冲区告警（proximityBufferM）。</li>
 * </ul>
 * <p>
 * 越界动作 {@link Action} 决定无人机越界后的处置策略：
 * <ul>
 *   <li>{@link Action#WARN WARN}：仅生成告警事件，不干预飞行。</li>
 *   <li>{@link Action#LOCK_RTH LOCK_RTH}：锁定返航（RTH），强制无人机返航。</li>
 * </ul>
 * <p>
 * 不可变值对象；线程安全通过不可变性保证。
 */
public final class GeofenceZone {

    /** 围栏几何类型。 */
    public enum Type { CIRCLE, POLYGON }

    /** 越界动作：仅告警 / 锁定返航。 */
    public enum Action { WARN, LOCK_RTH }

    private final int id;
    private final String name;
    private final Type type;
    /** 圆形围栏中心纬度；POLYGON 时为 NaN。 */
    private final double centerLat;
    /** 圆形围栏中心经度；POLYGON 时为 NaN。 */
    private final double centerLon;
    /** 圆形围栏半径（米）；POLYGON 时为 NaN。 */
    private final double radiusM;
    /** 多边形围栏顶点列表（不可变）；CIRCLE 时为空列表。 */
    private final List<GeoPoint> points;
    private final Action action;
    /** 围栏类型：KEEP_IN（允许活动区）或 KEEP_OUT（禁飞区），默认 KEEP_IN。 */
    private final FenceType fenceType;
    /** 接近缓冲区距离（米），仅对 KEEP_OUT 围栏生效，默认 100。 */
    private final int proximityBufferM;
    private final boolean enabled;
    private final long createdAtMs;

    private GeofenceZone(int id, String name, Type type,
                         double centerLat, double centerLon, double radiusM,
                         List<GeoPoint> points, Action action,
                         FenceType fenceType, int proximityBufferM,
                         boolean enabled, long createdAtMs) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.centerLat = centerLat;
        this.centerLon = centerLon;
        this.radiusM = radiusM;
        this.points = points;
        this.action = action;
        this.fenceType = fenceType;
        this.proximityBufferM = proximityBufferM;
        this.enabled = enabled;
        this.createdAtMs = createdAtMs;
    }

    /**
     * 创建圆形围栏（默认 KEEP_IN 类型，proximityBufferM=100）。
     *
     * @param id        围栏 ID
     * @param name      围栏名称
     * @param centerLat 中心纬度
     * @param centerLon 中心经度
     * @param radiusM   半径（米），必须 > 0
     * @param action    越界动作
     */
    public static GeofenceZone circleZone(int id, String name,
                                          double centerLat, double centerLon,
                                          double radiusM, Action action) {
        return circleZone(id, name, centerLat, centerLon, radiusM, action,
                FenceType.KEEP_IN, 100);
    }

    /**
     * 创建圆形围栏（指定围栏类型和接近缓冲区）。
     *
     * @param id               围栏 ID
     * @param name             围栏名称
     * @param centerLat        中心纬度
     * @param centerLon        中心经度
     * @param radiusM          半径（米），必须 > 0
     * @param action           越界动作
     * @param fenceType        围栏类型（KEEP_IN / KEEP_OUT）
     * @param proximityBufferM 接近缓冲区距离（米），KEEP_OUT 时必须 > 0
     */
    public static GeofenceZone circleZone(int id, String name,
                                          double centerLat, double centerLon,
                                          double radiusM, Action action,
                                          FenceType fenceType, int proximityBufferM) {
        if (radiusM <= 0) {
            throw new IllegalArgumentException("circle radius must be > 0, got " + radiusM);
        }
        if (fenceType == FenceType.KEEP_OUT && proximityBufferM <= 0) {
            throw new IllegalArgumentException(
                    "proximityBufferM must be > 0 for KEEP_OUT fence, got " + proximityBufferM);
        }
        return new GeofenceZone(id, name, Type.CIRCLE,
                centerLat, centerLon, radiusM,
                Collections.emptyList(), action, fenceType, proximityBufferM,
                true, System.currentTimeMillis());
    }

    /**
     * 创建多边形围栏（默认 KEEP_IN 类型，proximityBufferM=100）。
     *
     * @param id     围栏 ID
     * @param name   围栏名称
     * @param points 顶点列表，至少 3 个点
     * @param action 越界动作
     */
    public static GeofenceZone polygonZone(int id, String name,
                                           List<GeoPoint> points, Action action) {
        return polygonZone(id, name, points, action, FenceType.KEEP_IN, 100);
    }

    /**
     * 创建多边形围栏（指定围栏类型和接近缓冲区）。
     *
     * @param id               围栏 ID
     * @param name             围栏名称
     * @param points           顶点列表，至少 3 个点
     * @param action           越界动作
     * @param fenceType        围栏类型（KEEP_IN / KEEP_OUT）
     * @param proximityBufferM 接近缓冲区距离（米），KEEP_OUT 时必须 > 0
     */
    public static GeofenceZone polygonZone(int id, String name,
                                           List<GeoPoint> points, Action action,
                                           FenceType fenceType, int proximityBufferM) {
        if (points == null || points.size() < 3) {
            throw new IllegalArgumentException(
                    "polygon requires at least 3 points, got "
                            + (points == null ? 0 : points.size()));
        }
        if (fenceType == FenceType.KEEP_OUT && proximityBufferM <= 0) {
            throw new IllegalArgumentException(
                    "proximityBufferM must be > 0 for KEEP_OUT fence, got " + proximityBufferM);
        }
        return new GeofenceZone(id, name, Type.POLYGON,
                Double.NaN, Double.NaN, Double.NaN,
                Collections.unmodifiableList(List.copyOf(points)),
                action, fenceType, proximityBufferM,
                true, System.currentTimeMillis());
    }

    /**
     * 更新围栏启用状态（返回新实例，保持不可变性）。
     */
    public GeofenceZone withEnabled(boolean enabled) {
        return new GeofenceZone(id, name, type,
                centerLat, centerLon, radiusM,
                points, action, fenceType, proximityBufferM,
                enabled, createdAtMs);
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public Type getType() { return type; }
    public double getCenterLat() { return centerLat; }
    public double getCenterLon() { return centerLon; }
    public double getRadiusM() { return radiusM; }
    public List<GeoPoint> getPoints() { return points; }
    public Action getAction() { return action; }
    public FenceType getFenceType() { return fenceType; }
    public int getProximityBufferM() { return proximityBufferM; }
    public boolean isEnabled() { return enabled; }
    public long getCreatedAtMs() { return createdAtMs; }

    @Override
    public String toString() {
        return "GeofenceZone{id=" + id + ", name='" + name + "', type=" + type
                + (type == Type.CIRCLE
                    ? ", center=(" + centerLat + "," + centerLon + "), radius=" + radiusM + "m"
                    : ", points=" + points.size())
                + ", action=" + action + ", fenceType=" + fenceType
                + ", proximityBufferM=" + proximityBufferM
                + ", enabled=" + enabled + "}";
    }

    /**
     * 地理坐标点（纬度 / 经度）。
     * <p>
     * Java 17 record，不可变值对象，自动生成 equals/hashCode/toString。
     */
    public record GeoPoint(double lat, double lon) {
        @Override
        public String toString() {
            return "(" + lat + "," + lon + ")";
        }
    }
}