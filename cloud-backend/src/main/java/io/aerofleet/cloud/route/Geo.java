package io.aerofleet.cloud.route;

/**
 * 航线模板几何工具（F3）。
 * <p>
 * 局部平面近似：目标区域通常 <10km，纬度 1°≈111320m、经度 1°≈111320×cos(lat)m
 * 的线性换算误差在厘米级——不引入投影库。极区/洲际尺度不适用（模板本来就是
 * 行业场景的局部巡检，诚实边界见 spec §4）。
 */
final class Geo {

    static final double M_PER_DEG_LAT = 111_320.0;

    private Geo() {
    }

    /** 经度方向 1 度的米数（随纬度收缩）。 */
    static double mPerDegLon(double lat) {
        return M_PER_DEG_LAT * Math.cos(Math.toRadians(lat));
    }

    /** 两点球面距离（米，仓库惯例同款实现）。 */
    static double distM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 从 from 沿方位角 bearingDeg 前进 distM 的坐标（局部平面近似）。 */
    static double[] forward(double lat, double lon, double bearingDeg, double distM) {
        double dx = Math.sin(Math.toRadians(bearingDeg)) * distM;   // 东向
        double dy = Math.cos(Math.toRadians(bearingDeg)) * distM;   // 北向
        return new double[]{lat + dy / M_PER_DEG_LAT, lon + dx / mPerDegLon(lat)};
    }

    /** from→to 的方位角（度，正北 0 顺时针）。 */
    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double dLat = lat2 - lat1;
        double dLon = (lon2 - lon1) * mPerDegLon(lat1) / M_PER_DEG_LAT;
        return (Math.toDegrees(Math.atan2(dLon, dLat)) + 360.0) % 360.0;
    }

    /** 折线总长（米）。 */
    static double pathLength(double[][] pts) {
        double total = 0;
        for (int i = 1; i < pts.length; i++) {
            total += distM(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]);
        }
        return total;
    }

    /** 折线按 stepM 等距采样：拐点强制保留，段内插值；返回含首尾的采样序列。 */
    static double[][] sampleAlong(double[][] pts, double stepM) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        out.add(pts[0]);
        for (int i = 1; i < pts.length; i++) {
            double[] from = pts[i - 1];
            double[] to = pts[i];
            double segLen = distM(from[0], from[1], to[0], to[1]);
            if (segLen < stepM) {
                continue; // 短段不插值，直接在拐点处由下一段处理（避免超密点）
            }
            double brg = bearing(from[0], from[1], to[0], to[1]);
            for (double d = stepM; d < segLen - stepM / 2; d += stepM) {
                out.add(forward(from[0], from[1], brg, d));
            }
            out.add(to);
        }
        // 去重（相邻重复点距 <0.1m 移除——生成器守卫的前置）
        java.util.List<double[]> dedup = new java.util.ArrayList<>();
        for (double[] p : out) {
            if (dedup.isEmpty()
                    || distM(dedup.get(dedup.size() - 1)[0], dedup.get(dedup.size() - 1)[1],
                    p[0], p[1]) > 0.1) {
                dedup.add(p);
            }
        }
        return dedup.toArray(new double[0][]);
    }

    /** 闭合多边形按 stepM 等距采样成环（首尾同点闭合，含偏移）。 */
    static double[][] sampleRing(double[][] polygon, double stepM, double offsetM) {
        double[][] closed = new double[polygon.length + 1][];
        System.arraycopy(polygon, 0, closed, 0, polygon.length);
        closed[polygon.length] = polygon[0];
        double[][] sampled = sampleAlong(closed, stepM);
        if (offsetM == 0) {
            return sampled;
        }
        // 顶点法向偏移。绕向不用顶点序假设判——用 shoelace 有向面积做数据驱动判定
        // （正=逆时针），外法向 = 逆时针时前进方向左侧、顺时针时右侧。
        // 此前硬编码"右侧朝外"在顺时针顶点序下把环缩到了内侧（测试抓出）。
        boolean ccw = shoelacePositive(polygon);
        double side = ccw ? -90 : 90;
        double[][] out = new double[sampled.length][];
        for (int i = 0; i < sampled.length; i++) {
            double[] prev = sampled[(i - 1 + sampled.length) % sampled.length];
            double[] next = sampled[(i + 1) % sampled.length];
            double[] cur = sampled[i];
            double brgIn = bearing(prev[0], prev[1], cur[0], cur[1]);
            double brgOut = bearing(cur[0], cur[1], next[0], next[1]);
            double diff = ((brgOut - brgIn + 540) % 360) - 180;   // 归一化到 (-180,180]
            double bisector = (brgIn + diff / 2 + 360) % 360;
            double outward = (bisector + side + 360) % 360;
            out[i] = forward(cur[0], cur[1], outward, offsetM);
        }
        return out;
    }

    /** shoelace 有向面积为正 = 顶点逆时针（经纬度坐标下，lat 向北 lon 向东）。 */
    private static boolean shoelacePositive(double[][] polygon) {
        double sum = 0;
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i];
            double[] b = polygon[(i + 1) % polygon.length];
            sum += (a[0] * b[1] - b[0] * a[1]);
        }
        return sum > 0;
    }
}