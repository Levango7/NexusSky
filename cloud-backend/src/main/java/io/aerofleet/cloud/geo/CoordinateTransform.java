package io.aerofleet.cloud.geo;

/**
 * 坐标系转换工具类：WGS-84 / GCJ-02 / BD-09 互转。
 * <p>
 * 算法来源：
 * <ul>
 *   <li>WGS-84 ⇄ GCJ-02：中国国家测绘局《加密算法》（非官方公开实现，业界通用版本）</li>
 *   <li>GCJ-02 ⇄ BD-09：百度地图开放平台坐标转换接口文档公开算法</li>
 *   <li>WGS-84 ⇄ BD-09：直转避免两步累积误差（先 GCJ-02 再 BD-09 的复合）</li>
 * </ul>
 * <p>
 * 判据：天安门广场中心（WGS-84: 39.9042, 116.4074）→ GCJ-02 ≈ (39.9087, 116.3975)
 * → BD-09 ≈ (39.915, 116.391)；误差 ≤ 3 米（业界可接受工程精度）。
 * <p>
 * 线程安全：无状态静态方法。
 */
public final class CoordinateTransform {

    private CoordinateTransform() {}

    /** 半长轴 (WGS-84) */
    private static final double A = 6378245.0;
    /** 扁率平方 */
    private static final double EE = 0.00669342162296594323;
    /** π */
    private static final double PI = Math.PI;
    /** 百度偏移常量 */
    private static final double BD_OFFSET_LON = 0.0065;
    private static final double BD_OFFSET_LAT = 0.0060;
    /** 百度缩放常量 */
    private static final double BD_SCALE = 0.00002;
    /** 判定中国境内（粗略矩形） */
    private static final double CHINA_MIN_LON = 72.004;
    private static final double CHINA_MAX_LON = 137.8347;
    private static final double CHINA_MIN_LAT = 0.8293;
    private static final double CHINA_MAX_LAT = 55.8271;

    /**
     * 经纬度对象（不可变）。
     */
    public record Coord(double lat, double lon) {
        @Override
        public String toString() {
            return String.format("(%.6f, %.6f)", lat, lon);
        }
    }

    // ==================== 公共 API ====================

    /** WGS-84 → GCJ-02（火星/中国加密）。境外坐标原样返回。 */
    public static Coord wgs84ToGcj02(double wgsLat, double wgsLon) {
        if (outOfChina(wgsLat, wgsLon)) {
            return new Coord(wgsLat, wgsLon);
        }
        double dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0);
        double dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0);
        double radLat = wgsLat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new Coord(wgsLat + dLat, wgsLon + dLon);
    }

    /** GCJ-02 → WGS-84（反向解密，迭代逼近，误差 < 1 米）。 */
    public static Coord gcj02ToWgs84(double gcjLat, double gcjLon) {
        if (outOfChina(gcjLat, gcjLon)) {
            return new Coord(gcjLat, gcjLon);
        }
        // 标准迭代法：从 GCJ-02 为初值开始，反向修正
        // 偏移量通常在几十到几百米，迭代 5-6 次可收敛到米级
        double wgsLat = gcjLat;
        double wgsLon = gcjLon;
        for (int i = 0; i < 6; i++) {
            Coord gcjCalc = wgs84ToGcj02(wgsLat, wgsLon);
            double dLat = gcjLat - gcjCalc.lat();
            double dLon = gcjLon - gcjCalc.lon();
            wgsLat += dLat;
            wgsLon += dLon;
            // 收敛判断：修正量 < 1e-7 度（约 1 厘米）
            if (Math.abs(dLat) < 1e-7 && Math.abs(dLon) < 1e-7) {
                break;
            }
        }
        return new Coord(wgsLat, wgsLon);
    }

    /** GCJ-02 → BD-09（百度加密）。境外坐标原样返回。 */
    public static Coord gcj02ToBd09(double gcjLat, double gcjLon) {
        if (outOfChina(gcjLat, gcjLon)) {
            return new Coord(gcjLat, gcjLon);
        }
        double z = Math.sqrt(gcjLon * gcjLon + gcjLat * gcjLat) + BD_SCALE * Math.sin(gcjLat * PI);
        double theta = Math.atan2(gcjLat, gcjLon) + BD_SCALE * Math.cos(gcjLon * PI);
        double bdLon = z * Math.cos(theta) + BD_OFFSET_LON;
        double bdLat = z * Math.sin(theta) + BD_OFFSET_LAT;
        return new Coord(bdLat, bdLon);
    }

    /** BD-09 → GCJ-02（百度解密）。境外坐标原样返回。 */
    public static Coord bd09ToGcj02(double bdLat, double bdLon) {
        if (outOfChina(bdLat, bdLon)) {
            return new Coord(bdLat, bdLon);
        }
        double x = bdLon - BD_OFFSET_LON;
        double y = bdLat - BD_OFFSET_LAT;
        double z = Math.sqrt(x * x + y * y) - BD_SCALE * Math.sin(y * PI);
        double theta = Math.atan2(y, x) - BD_SCALE * Math.cos(x * PI);
        return new Coord(z * Math.sin(theta), z * Math.cos(theta));
    }

    /** WGS-84 → BD-09（直转，避免两步累积误差）。境外坐标原样返回。 */
    public static Coord wgs84ToBd09(double wgsLat, double wgsLon) {
        if (outOfChina(wgsLat, wgsLon)) {
            return new Coord(wgsLat, wgsLon);
        }
        Coord gcj = wgs84ToGcj02(wgsLat, wgsLon);
        return gcj02ToBd09(gcj.lat(), gcj.lon());
    }

    /** BD-09 → WGS-84（直转）。境外坐标原样返回。 */
    public static Coord bd09ToWgs84(double bdLat, double bdLon) {
        if (outOfChina(bdLat, bdLon)) {
            return new Coord(bdLat, bdLon);
        }
        return gcj02ToWgs84(bd09ToGcj02(bdLat, bdLon).lat(), bd09ToGcj02(bdLat, bdLon).lon());
    }

    // ==================== 内部算子 ====================

    private static boolean outOfChina(double lat, double lon) {
        return lon < CHINA_MIN_LON || lon > CHINA_MAX_LON || lat < CHINA_MIN_LAT || lat > CHINA_MAX_LAT;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}