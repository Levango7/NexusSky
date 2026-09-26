package io.aerofleet.cloud.inspection;

import java.util.List;

/**
 * 巡检区域（无人机巡检覆盖的地理范围）。
 * <p>
 * 支持两种区域类型：
 * <ul>
 *   <li>{@link Kind#POLYGON} 多边形区域 — 由有序顶点列表定义边界</li>
 *   <li>{@link Kind#CIRCLE} 圆形区域 — 由中心点 + 半径定义</li>
 * </ul>
 * <p>
 * 提供 {@link #contains(double, double)} 方法判断给定坐标是否落在区域内，
 * 用于航点裁剪与覆盖率统计。
 */
public final class InspectionArea {

    /** 区域类型。 */
    public enum Kind {
        /** 多边形区域。 */
        POLYGON,
        /** 圆形区域。 */
        CIRCLE
    }

    /** 地球半径 m（Haversine 公式用）。 */
    private static final double EARTH_R = 6371000.0;

    private final Kind kind;
    /** 多边形顶点列表（POLYGON 模式），每个元素为 {lat, lon}。 */
    private final List<double[]> points;
    /** 圆心纬度（CIRCLE 模式）。 */
    private final double centerLat;
    /** 圆心经度（CIRCLE 模式）。 */
    private final double centerLon;
    /** 圆半径 m（CIRCLE 模式）。 */
    private final double radiusM;

    private InspectionArea(Kind kind, List<double[]> points,
                           double centerLat, double centerLon, double radiusM) {
        this.kind = kind;
        this.points = points;
        this.centerLat = centerLat;
        this.centerLon = centerLon;
        this.radiusM = radiusM;
    }

    /** 创建多边形区域。 */
    public static InspectionArea polygon(List<double[]> points) {
        if (points == null || points.size() < 3) {
            throw new IllegalArgumentException("polygon needs >= 3 points");
        }
        return new InspectionArea(Kind.POLYGON, List.copyOf(points),
                Double.NaN, Double.NaN, Double.NaN);
    }

    /** 创建圆形区域。 */
    public static InspectionArea circle(double centerLat, double centerLon, double radiusM) {
        if (radiusM <= 0) {
            throw new IllegalArgumentException("radius must be positive");
        }
        return new InspectionArea(Kind.CIRCLE, null, centerLat, centerLon, radiusM);
    }

    public Kind kind() { return kind; }
    public List<double[]> points() { return points; }
    public double centerLat() { return centerLat; }
    public double centerLon() { return centerLon; }
    public double radiusM() { return radiusM; }

    /**
     * 判断给定坐标是否落在区域内。
     * <ul>
     *   <li>POLYGON：使用射线投射法（ray casting）</li>
     *   <li>CIRCLE：Haversine 距离 ≤ radiusM</li>
     * </ul>
     */
    public boolean contains(double lat, double lon) {
        if (kind == Kind.CIRCLE) {
            return haversine(centerLat, centerLon, lat, lon) <= radiusM;
        }
        return pointInPolygon(lat, lon, points);
    }

    /** 计算多边形包围盒 [minLat, maxLat, minLon, maxLon]。 */
    public double[] boundingBox() {
        if (kind == Kind.CIRCLE) {
            double latOffset = radiusM / 111320.0;
            double lonOffset = radiusM / (111320.0 * Math.cos(Math.toRadians(centerLat)));
            return new double[]{
                    centerLat - latOffset, centerLat + latOffset,
                    centerLon - lonOffset, centerLon + lonOffset
            };
        }
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] p : points) {
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]);
            maxLon = Math.max(maxLon, p[1]);
        }
        return new double[]{minLat, maxLat, minLon, maxLon};
    }

    /** 射线投射法判断点是否在多边形内部。 */
    private static boolean pointInPolygon(double lat, double lon, List<double[]> poly) {
        int n = poly.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = poly.get(i)[0], xi = poly.get(i)[1];
            double yj = poly.get(j)[0], xj = poly.get(j)[1];
            if (((yi > lat) != (yj > lat))
                    && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Haversine 距离（m）。 */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}