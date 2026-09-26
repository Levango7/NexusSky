package io.aerofleet.sim.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 地理边界模型（灾区通信隔离，FR-04）。
 * <p>
 * 用多边形（List&lt;GeoPoint&gt;）表示灾区地理边界，
 * 支持 contains(lat, lon) 判定节点是否在边界内。
 * <p>
 * 不可变值对象：所有修改操作返回新实例。
 */
public final class GeoBoundary {

    /** 地理坐标点（经纬度）。 */
    public record GeoPoint(double lat, double lon) {}

    /** 多边形顶点列表（按顺时针或逆时针顺序排列）。 */
    public final List<GeoPoint> vertices;

    /**
     * 构造地理边界。
     *
     * @param vertices 多边形顶点列表（至少3个点）
     * @throws IllegalArgumentException 顶点数 < 3
     */
    public GeoBoundary(List<GeoPoint> vertices) {
        if (vertices == null || vertices.size() < 3) {
            throw new IllegalArgumentException("地理边界至少需要3个顶点，当前: "
                    + (vertices == null ? 0 : vertices.size()));
        }
        this.vertices = Collections.unmodifiableList(new ArrayList<>(vertices));
    }

    /**
     * 判定点是否在多边形边界内（射线法 / Ray Casting）。
     * <p>
     * 从待判定点向右发射一条水平射线，计算与多边形边的交点数：
     * 交点数为奇数 → 点在内部；偶数 → 点在外部。
     *
     * @param lat 纬度
     * @param lon 经度
     * @return true 若点在边界内
     */
    public boolean contains(double lat, double lon) {
        int n = vertices.size();
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            GeoPoint pi = vertices.get(i);
            GeoPoint pj = vertices.get(j);

            // 判定射线是否穿过边 (pi, pj)
            if (((pi.lat() > lat) != (pj.lat() > lat))
                    && (lon < (pj.lon() - pi.lon()) * (lat - pi.lat())
                    / (pj.lat() - pi.lat()) + pi.lon())) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * 判定 GeoPoint 是否在边界内。
     */
    public boolean contains(GeoPoint point) {
        return contains(point.lat(), point.lon());
    }

    /**
     * 返回顶点数的不可变列表。
     */
    public List<GeoPoint> getVertices() {
        return vertices;
    }

    /**
     * 顶点数量。
     */
    public int vertexCount() {
        return vertices.size();
    }

    @Override
    public String toString() {
        return "GeoBoundary{vertices=" + vertices + "}";
    }
}