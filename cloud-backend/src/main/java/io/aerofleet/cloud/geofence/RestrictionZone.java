package io.aerofleet.cloud.geofence;

import java.util.Collections;
import java.util.List;

/**
 * 限飞区定义：不可变值对象，由 {@link RestrictionDataSource#fetch} 返回。
 * <p>
 * 限飞区恒为 {@link FenceType#KEEP_OUT KEEP_OUT} 语义（无人机应保持在围栏外部）。
 * 支持两种几何类型：
 * <ul>
 *   <li>{@link GeofenceZone.Type#CIRCLE CIRCLE}：圆形限飞区，由中心经纬度 + 半径描述。</li>
 *   <li>{@link GeofenceZone.Type#POLYGON POLYGON}：多边形限飞区，由顶点列表描述。</li>
 * </ul>
 * <p>
 * 与 {@link GeofenceZone} 的区别：RestrictionZone 来自外部数据源（如官方限飞区数据库），
 * 包含数据来源和拉取时间信息，且 fenceType 恒为 KEEP_OUT。
 * <p>
 * 不可变值对象；线程安全通过不可变性保证。
 *
 * @see RestrictionDataSource
 */
public final class RestrictionZone {

    private final String zoneId;
    private final String name;
    private final GeofenceZone.Type type;
    /** 圆形限飞区中心纬度；POLYGON 时为 NaN。 */
    private final double centerLat;
    /** 圆形限飞区中心经度；POLYGON 时为 NaN。 */
    private final double centerLon;
    /** 圆形限飞区半径（米）；POLYGON 时为 NaN。 */
    private final double radiusM;
    /** 多边形限飞区顶点列表（不可变）；CIRCLE 时为空列表。 */
    private final List<GeofenceZone.GeoPoint> points;
    /** 数据来源标识（如 "caac-official"、"mock"）。 */
    private final String source;
    /** 数据拉取时间戳（毫秒）。 */
    private final long fetchedAtMs;

    private RestrictionZone(String zoneId, String name, GeofenceZone.Type type,
                            double centerLat, double centerLon, double radiusM,
                            List<GeofenceZone.GeoPoint> points,
                            String source, long fetchedAtMs) {
        this.zoneId = zoneId;
        this.name = name;
        this.type = type;
        this.centerLat = centerLat;
        this.centerLon = centerLon;
        this.radiusM = radiusM;
        this.points = points;
        this.source = source;
        this.fetchedAtMs = fetchedAtMs;
    }

    /**
     * 创建圆形限飞区。
     *
     * @param zoneId    限飞区 ID（字符串，来自外部数据源）
     * @param name      限飞区名称
     * @param centerLat 中心纬度
     * @param centerLon 中心经度
     * @param radiusM   半径（米），必须 > 0
     * @param source    数据来源标识
     * @return 圆形限飞区实例
     */
    public static RestrictionZone circleRestriction(String zoneId, String name,
                                                    double centerLat, double centerLon,
                                                    double radiusM, String source) {
        if (radiusM <= 0) {
            throw new IllegalArgumentException("circle radius must be > 0, got " + radiusM);
        }
        return new RestrictionZone(zoneId, name, GeofenceZone.Type.CIRCLE,
                centerLat, centerLon, radiusM,
                Collections.emptyList(), source, System.currentTimeMillis());
    }

    /**
     * 创建多边形限飞区。
     *
     * @param zoneId  限飞区 ID（字符串，来自外部数据源）
     * @param name    限飞区名称
     * @param points  顶点列表，至少 3 个点
     * @param source  数据来源标识
     * @return 多边形限飞区实例
     */
    public static RestrictionZone polygonRestriction(String zoneId, String name,
                                                     List<GeofenceZone.GeoPoint> points,
                                                     String source) {
        if (points == null || points.size() < 3) {
            throw new IllegalArgumentException(
                    "polygon requires at least 3 points, got "
                            + (points == null ? 0 : points.size()));
        }
        return new RestrictionZone(zoneId, name, GeofenceZone.Type.POLYGON,
                Double.NaN, Double.NaN, Double.NaN,
                Collections.unmodifiableList(List.copyOf(points)),
                source, System.currentTimeMillis());
    }

    /** 限飞区 ID（字符串，来自外部数据源）。 */
    public String getZoneId() { return zoneId; }

    /** 限飞区名称。 */
    public String getName() { return name; }

    /** 几何类型（CIRCLE / POLYGON）。 */
    public GeofenceZone.Type getType() { return type; }

    /** 圆形限飞区中心纬度；POLYGON 时为 NaN。 */
    public double getCenterLat() { return centerLat; }

    /** 圆形限飞区中心经度；POLYGON 时为 NaN。 */
    public double getCenterLon() { return centerLon; }

    /** 圆形限飞区半径（米）；POLYGON 时为 NaN。 */
    public double getRadiusM() { return radiusM; }

    /** 多边形限飞区顶点列表（不可变）；CIRCLE 时为空列表。 */
    public List<GeofenceZone.GeoPoint> getPoints() { return points; }

    /** 围栏类型恒为 KEEP_OUT。 */
    public FenceType getFenceType() { return FenceType.KEEP_OUT; }

    /** 数据来源标识。 */
    public String getSource() { return source; }

    /** 数据拉取时间戳（毫秒）。 */
    public long getFetchedAtMs() { return fetchedAtMs; }

    @Override
    public String toString() {
        return "RestrictionZone{zoneId='" + zoneId + "', name='" + name + "', type=" + type
                + (type == GeofenceZone.Type.CIRCLE
                    ? ", center=(" + centerLat + "," + centerLon + "), radius=" + radiusM + "m"
                    : ", points=" + points.size())
                + ", fenceType=KEEP_OUT, source='" + source + "', fetchedAt=" + fetchedAtMs + "}";
    }
}