package io.aerofleet.sim.satrelay;

/**
 * HAPS 高空中继节点（M7 星-空-地多层级中继，FR-5.2，L2 层）。
 * <p>
 * 飞行高度 18-20km，单机覆盖半径 200km+，作为 L2 层中继节点。
 * 覆盖判定基于大圆距离（球面近似）。
 */
public final class HapsRelayNode {

    private final int nodeId;
    private final double latDeg;
    private final double lonDeg;
    private final double altitudeM;
    private final double coverageRadiusKm;
    private volatile boolean reachable;

    /**
     * 构造器。
     *
     * @param nodeId          节点唯一标识
     * @param latDeg          纬度（度）
     * @param lonDeg          经度（度）
     * @param altitudeM       飞行高度（米，18000-20000）
     * @param coverageRadiusKm 覆盖半径（km），默认 200
     */
    public HapsRelayNode(int nodeId, double latDeg, double lonDeg,
                         double altitudeM, double coverageRadiusKm) {
        if (nodeId <= 0) {
            throw new IllegalArgumentException("nodeId must be positive, got " + nodeId);
        }
        if (altitudeM < 18_000.0 || altitudeM > 20_000.0) {
            throw new IllegalArgumentException(
                    "HAPS altitude must be in [18000, 20000] m, got " + altitudeM);
        }
        if (coverageRadiusKm <= 0) {
            throw new IllegalArgumentException("coverageRadiusKm must be > 0, got " + coverageRadiusKm);
        }
        this.nodeId = nodeId;
        this.latDeg = latDeg;
        this.lonDeg = lonDeg;
        this.altitudeM = altitudeM;
        this.coverageRadiusKm = coverageRadiusKm;
        this.reachable = true;
    }

    /** 便捷工厂：默认覆盖半径 200km、高度 19km。 */
    public static HapsRelayNode atDefault(int nodeId, double latDeg, double lonDeg) {
        return new HapsRelayNode(nodeId, latDeg, lonDeg, 19_000.0, 200.0);
    }

    public int nodeId() {
        return nodeId;
    }

    public double latDeg() {
        return latDeg;
    }

    public double lonDeg() {
        return lonDeg;
    }

    public double altitudeM() {
        return altitudeM;
    }

    public double coverageRadiusKm() {
        return coverageRadiusKm;
    }

    public boolean isReachable() {
        return reachable;
    }

    public void setReachable(boolean reachable) {
        this.reachable = reachable;
    }

    /**
     * 判定地面点是否在本 HAPS 覆盖范围内。
     * <p>
     * 基于大圆距离（球面 haversine 近似），距离 < 覆盖半径则覆盖。
     *
     * @param groundLatDeg 地面点纬度（度）
     * @param groundLonDeg 地面点经度（度）
     * @return 是否覆盖
     */
    public boolean covers(double groundLatDeg, double groundLonDeg) {
        double distanceKm = greatCircleDistanceKm(latDeg, lonDeg, groundLatDeg, groundLonDeg);
        return distanceKm <= coverageRadiusKm;
    }

    /** 大圆距离（km，haversine 公式）。 */
    public static double greatCircleDistanceKm(double lat1Deg, double lon1Deg,
                                                double lat2Deg, double lon2Deg) {
        double lat1Rad = Math.toRadians(lat1Deg);
        double lat2Rad = Math.toRadians(lat2Deg);
        double dLat = Math.toRadians(lat2Deg - lat1Deg);
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * OrbitModel.EARTH_R_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    @Override
    public String toString() {
        return "HapsRelayNode{id=" + nodeId + ", lat=" + latDeg + "°, lon=" + lonDeg + "°"
                + ", alt=" + altitudeM + "m, cov=" + coverageRadiusKm + "km"
                + ", reachable=" + reachable + "}";
    }
}