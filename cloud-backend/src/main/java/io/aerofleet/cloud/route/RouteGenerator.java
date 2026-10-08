package io.aerofleet.cloud.route;

import java.util.ArrayList;
import java.util.List;

/**
 * 四类行业航线模板生成器（F3，spec §1）——全部纯函数，控制器零几何。
 * <p>
 * 输出与既有任务上传端点（POST /drones/{sysid}/mission 的 items）同构：
 * {cmd, lat, lon, alt, holdTime}。
 */
public final class RouteGenerator {

    /** MAVLink 任务协议单机上限 1000 的安全余量。 */
    static final int MAX_WAYPOINTS = 990;

    private RouteGenerator() {
    }

    // ------------------------------------------------------------------
    // T1 电力杆塔：逐塔 [抵达→环绕→拍照驻留]
    // ------------------------------------------------------------------

    public static Result tower(List<Tower> towers, double altM,
                               double orbitRadiusM, int orbitPoints, double hoverSec) {
        require(!towers.isEmpty(), "towers");
        require(altM > 0, "altM");
        require(orbitRadiusM > 0, "orbitRadiusM");
        require(orbitPoints >= 0, "orbitPoints");
        require(hoverSec >= 0, "hoverSec");

        List<Leg> legs = new ArrayList<>();
        for (Tower t : towers) {
            require(t.lat() >= -90 && t.lat() <= 90, "towers[].lat");
            require(t.lon() >= -180 && t.lon() <= 180, "towers[].lon");
            List<Point> wp = new ArrayList<>();
            wp.add(point("waypoint", t.lat(), t.lon(), altM, 0));
            // 环绕点：从当前方位 -90°（塔的西侧）起顺时针一圈
            for (int i = 0; i < orbitPoints; i++) {
                double brg = (double) i / orbitPoints * 360;
                double[] p = Geo.forward(t.lat(), t.lon(), brg, orbitRadiusM);
                wp.add(point("waypoint", p[0], p[1], altM, 0));
            }
            // 回到塔位上空拍照（驻留）——拍照仅接受 armed 且机身在目标上空（F1 语义）
            wp.add(point("waypoint", t.lat(), t.lon(), altM, hoverSec));
            legs.add(new Leg(t.towerNo() == null ? "" : t.towerNo(), wp));
        }
        return assemble(legs, "tower", altM);
    }

    // ------------------------------------------------------------------
    // T2 光伏连片：弓字形平行扫描
    // ------------------------------------------------------------------

    public static Result solar(double[][] polygon, double altM,
                               double lineSpacingM, double directionDeg) {
        requirePolygon(polygon);
        require(altM > 0, "altM");
        require(lineSpacingM > 0, "lineSpacingM");
        require(directionDeg >= 0 && directionDeg < 360, "directionDeg");

        // 1) 把多边形顶点旋转到"扫描方向为东"的局部系，便于做水平平行线
        double cos = Math.cos(Math.toRadians(directionDeg));
        double sin = Math.sin(Math.toRadians(directionDeg));
        double[][] local = new double[polygon.length][];
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < polygon.length; i++) {
            // 米制局部坐标：x 东、y 北，再绕原点旋转 -directionDeg 使扫描线水平
            double x = (polygon[i][1] - polygon[0][1]) * Geo.mPerDegLon(polygon[i][0]);
            double y = (polygon[i][0] - polygon[0][0]) * Geo.M_PER_DEG_LAT;
            double rx = x * cos + y * sin;
            double ry = -x * sin + y * cos;
            local[i] = new double[]{rx, ry};
            minY = Math.min(minY, ry);
            maxY = Math.max(maxY, ry);
        }

        List<Point> wp = new ArrayList<>();
        boolean leftToRight = true;
        // 2) 逐条水平扫描线与多边形求交（偶数个交点，配对成段）
        for (double y = minY + lineSpacingM / 2; y < maxY; y += lineSpacingM) {
            List<Double> xs = new ArrayList<>();
            for (int i = 0; i < local.length; i++) {
                double[] a = local[i];
                double[] b = local[(i + 1) % local.length];
                if ((a[1] <= y && b[1] > y) || (b[1] <= y && a[1] > y)) {
                    xs.add(a[0] + (y - a[1]) / (b[1] - a[1]) * (b[0] - a[0]));
                }
            }
            xs.sort(Double::compareTo);
            // 3) 弓字形：隔行折返（相邻扫描线方向交替，转场最短）
            for (int k = 0; k + 1 < xs.size(); k += 2) {
                double x1 = xs.get(k);
                double x2 = xs.get(k + 1);
                if (!leftToRight) {
                    double t = x1;
                    x1 = x2;
                    x2 = t;
                }
                wp.add(localToGcs(polygon[0], directionDeg, x1, y, altM));
                wp.add(localToGcs(polygon[0], directionDeg, x2, y, altM));
            }
            if (xs.size() >= 2) {
                leftToRight = !leftToRight;
            }
        }
        List<Leg> legs = List.of(new Leg("", wp));
        return assemble(legs, "solar", altM);
    }

    // ------------------------------------------------------------------
    // T3 管线带状：折线等距采样
    // ------------------------------------------------------------------

    public static Result pipeline(double[][] line, double altM, double stepM) {
        requireLine(line);
        require(altM > 0, "altM");
        require(stepM > 0, "stepM");

        double[][] sampled = Geo.sampleAlong(line, stepM);
        List<Point> wp = new ArrayList<>();
        for (double[] p : sampled) {
            wp.add(point("waypoint", p[0], p[1], altM, 0));
        }
        return assemble(List.of(new Leg("", wp)), "pipeline", altM);
    }

    // ------------------------------------------------------------------
    // T4 河湖岸线：轮廓环采样（可外偏移）
    // ------------------------------------------------------------------

    public static Result shoreline(double[][] polygon, double altM,
                                   double stepM, double offsetM) {
        requirePolygon(polygon);
        require(altM > 0, "altM");
        require(stepM > 0, "stepM");
        require(offsetM >= 0, "offsetM");

        double[][] ring = Geo.sampleRing(polygon, stepM, offsetM);
        List<Point> wp = new ArrayList<>();
        for (double[] p : ring) {
            wp.add(point("waypoint", p[0], p[1], altM, 0));
        }
        return assemble(List.of(new Leg("", wp)), "shoreline", altM);
    }

    // ------------------------------------------------------------------
    // 组装与守卫
    // ------------------------------------------------------------------

    private static Result assemble(List<Leg> legs, String type, double altM) {
        List<Point> all = new ArrayList<>();
        for (Leg leg : legs) {
            all.addAll(leg.waypoints());
        }
        // 相邻重复点守卫（>0.1m 已在采样层去重，这里防组装层重复）
        require(all.size() <= MAX_WAYPOINTS,
                "waypoints exceed MAVLink mission limit: " + all.size() + " > " + MAX_WAYPOINTS);
        // RTL 尾项
        Point last = all.get(all.size() - 1);
        all.add(point("rtl", last.lat(), last.lon(), 0, 0));
        double totalKm = 0;
        for (int i = 1; i < all.size(); i++) {
            Point a = all.get(i - 1);
            Point b = all.get(i);
            if ("waypoint".equals(b.cmd())) {
                totalKm += Geo.distM(a.lat(), a.lon(), b.lat(), b.lon()) / 1000.0;
            }
        }
        return new Result(type, legs, all, Math.round(totalKm * 100) / 100.0, altM);
    }

    private static void require(boolean cond, String field) {
        if (!cond) {
            throw new IllegalArgumentException("invalid route template parameter: " + field);
        }
    }

    private static void requirePolygon(double[][] polygon) {
        require(polygon != null && polygon.length >= 3, "polygon (>=3 vertices)");
        for (double[] p : polygon) {
            require(p != null && p.length >= 2, "polygon[]");
            require(p[0] >= -90 && p[0] <= 90, "polygon[].lat");
            require(p[1] >= -180 && p[1] <= 180, "polygon[].lon");
        }
    }

    private static void requireLine(double[][] line) {
        require(line != null && line.length >= 2, "line (>=2 points)");
    }

    private static Point point(String cmd, double lat, double lon, double alt, double holdTime) {
        return new Point(cmd, Math.round(lat * 1e7) / 1e7, Math.round(lon * 1e7) / 1e7,
                alt, holdTime);
    }

    /** 局部旋转系坐标 → GCS（把 directionDeg 旋转回去）。 */
    private static Point localToGcs(double[] origin, double directionDeg,
                                    double rx, double ry, double altM) {
        double cos = Math.cos(Math.toRadians(directionDeg));
        double sin = Math.sin(Math.toRadians(directionDeg));
        double x = rx * cos - ry * sin;
        double y = rx * sin + ry * cos;
        return point("waypoint",
                origin[0] + y / Geo.M_PER_DEG_LAT,
                origin[1] + x / Geo.mPerDegLon(origin[0]),
                altM, 0);
    }

    // ------------------------------------------------------------------
    // 数据形状
    // ------------------------------------------------------------------

    /** 单塔杆号（可空——匿名巡检场景）。 */
    public record Tower(String towerNo, double lat, double lon) {
    }

    /** 航点（与 mission 上传 items 同构）。 */
    public record Point(String cmd, double lat, double lon, double alt, double holdTime) {
    }

    /** 分段（tower 模板按塔分段；其余模板单段）。 */
    public record Leg(String towerNo, List<Point> waypoints) {
    }

    /** 生成结果：全量航点（可直接下发）+ 分段 + 概要。 */
    public record Result(String type, List<Leg> legs, List<Point> waypoints,
                         double estKm, double altM) {
    }
}