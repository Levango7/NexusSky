package io.aerofleet.sim.celltower;

/**
 * 覆盖区域值类（M6 移动基站载荷抽象，FR-COV-01~06）。
 * <p>
 * 以无人机投影点为圆心、覆盖半径为半径的圆形区域，或扇形区域（方位角 + 扇区张角），
 * 受地形遮挡裁剪。不可变。
 */
public final class CoverageArea {

    /** 覆盖形状。 */
    public enum Shape {
        CIRCLE, SECTOR
    }

    public final int centerLatE7;
    public final int centerLonE7;
    public final double radiusM;
    public final Shape shape;
    /** 扇形方位角（shape=SECTOR 时有效），[0, 360)。 */
    public final double azimuthDeg;
    /** 扇区张角（shape=SECTOR 时有效），(0, 360]。 */
    public final double beamWidthDeg;
    /** 是否经地形遮挡裁剪。 */
    public final boolean occluded;

    public CoverageArea(int centerLatE7, int centerLonE7, double radiusM,
                        Shape shape, double azimuthDeg, double beamWidthDeg, boolean occluded) {
        this.centerLatE7 = centerLatE7;
        this.centerLonE7 = centerLonE7;
        this.radiusM = radiusM;
        this.shape = shape;
        this.azimuthDeg = azimuthDeg;
        this.beamWidthDeg = beamWidthDeg;
        this.occluded = occluded;
    }

    /** 全向圆形覆盖的便捷工厂。 */
    public static CoverageArea circle(int centerLatE7, int centerLonE7, double radiusM, boolean occluded) {
        return new CoverageArea(centerLatE7, centerLonE7, radiusM, Shape.CIRCLE, 0, 360, occluded);
    }

    /** 扇形覆盖的便捷工厂。 */
    public static CoverageArea sector(int centerLatE7, int centerLonE7, double radiusM,
                                      double azimuthDeg, double beamWidthDeg, boolean occluded) {
        return new CoverageArea(centerLatE7, centerLonE7, radiusM, Shape.SECTOR, azimuthDeg, beamWidthDeg, occluded);
    }

    /**
     * 判定给定坐标（degE7）是否在覆盖区域内（FR-COV-02 / FR-COV-03）。
     * 使用简化平面距离公式（小范围覆盖足够精确）。
     */
    public boolean contains(int latE7, int lonE7) {
        // 平面近似距离（degE7 → 米）
        double dLat = (latE7 - centerLatE7) / 1e7 * 111_320.0;
        double dLon = (lonE7 - centerLonE7) / 1e7 * 111_320.0
                * Math.cos(Math.toRadians(centerLatE7 / 1e7));
        double dist = Math.sqrt(dLat * dLat + dLon * dLon);
        if (dist > radiusM) {
            return false;
        }
        if (shape == Shape.CIRCLE) {
            return true;
        }
        // 扇形：判定方位角是否在 [azimuth - beamWidth/2, azimuth + beamWidth/2] 内
        double bearing = Math.toDegrees(Math.atan2(dLon, dLat));
        if (bearing < 0) {
            bearing += 360;
        }
        double half = beamWidthDeg / 2;
        double diff = Math.abs(angleDiff(bearing, azimuthDeg));
        return diff <= half;
    }

    /** 角度差归一化到 [-180, 180]。 */
    private static double angleDiff(double a, double b) {
        double d = (a - b) % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    @Override
    public String toString() {
        return "CoverageArea{center=(" + centerLatE7 + "," + centerLonE7
                + "), radius=" + radiusM + "m, shape=" + shape
                + (shape == Shape.SECTOR ? ", az=" + azimuthDeg + ", bw=" + beamWidthDeg : "")
                + ", occluded=" + occluded + "}";
    }
}