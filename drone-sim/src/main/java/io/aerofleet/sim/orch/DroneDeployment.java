package io.aerofleet.sim.orch;

/**
 * 单架无人机部署方案（M9 应急任务编排，T3 覆盖优化算法）。
 * <p>
 * 描述一架无人机在灾区中的目标部署位置、基站配置、中继角色与预期指标。
 * 所有字段 final 不可变，由 {@link CoverageOptimizer} 在优化过程中生成。
 * <p>
 * 字段说明：
 * <pre>
 * droneId          无人机唯一标识
 * targetLat        目标纬度（度）
 * targetLon        目标经度（度）
 * targetAlt        目标高度（m）
 * cellType         基站类型（0=无, 1=LTE, 2=WiFi, 3=LoRa）
 * relayRole        中继角色（0=无, 1=mesh, 2=HAPS, 3=LEO）
 * txPower          发射功率（dBm）
 * expectedCoverage 预期覆盖率（%）
 * batteryBudget    电量预算（%）
 * </pre>
 */
public final class DroneDeployment {

    /** 无人机唯一标识。 */
    public final int droneId;
    /** 目标纬度（度）。 */
    public final double targetLat;
    /** 目标经度（度）。 */
    public final double targetLon;
    /** 目标高度（m）。 */
    public final double targetAlt;
    /** 基站类型（0=无, 1=LTE, 2=WiFi, 3=LoRa）。 */
    public final int cellType;
    /** 中继角色（0=无, 1=mesh, 2=HAPS, 3=LEO）。 */
    public final int relayRole;
    /** 发射功率（dBm）。 */
    public final int txPower;
    /** 预期覆盖率（%）。 */
    public final double expectedCoverage;
    /** 电量预算（%）。 */
    public final int batteryBudget;

    /**
     * 构造单架无人机部署方案。
     *
     * @param droneId          无人机唯一标识
     * @param targetLat        目标纬度（度）
     * @param targetLon        目标经度（度）
     * @param targetAlt        目标高度（m）
     * @param cellType         基站类型（0=无, 1=LTE, 2=WiFi, 3=LoRa）
     * @param relayRole        中继角色（0=无, 1=mesh, 2=HAPS, 3=LEO）
     * @param txPower          发射功率（dBm）
     * @param expectedCoverage 预期覆盖率（%）
     * @param batteryBudget    电量预算（%）
     */
    public DroneDeployment(int droneId, double targetLat, double targetLon, double targetAlt,
                           int cellType, int relayRole, int txPower,
                           double expectedCoverage, int batteryBudget) {
        this.droneId = droneId;
        this.targetLat = targetLat;
        this.targetLon = targetLon;
        this.targetAlt = targetAlt;
        this.cellType = cellType;
        this.relayRole = relayRole;
        this.txPower = txPower;
        this.expectedCoverage = expectedCoverage;
        this.batteryBudget = batteryBudget;
    }

    /** @return 无人机唯一标识 */
    public int getDroneId() {
        return droneId;
    }

    /** @return 目标纬度（度） */
    public double getTargetLat() {
        return targetLat;
    }

    /** @return 目标经度（度） */
    public double getTargetLon() {
        return targetLon;
    }

    /** @return 目标高度（m） */
    public double getTargetAlt() {
        return targetAlt;
    }

    /** @return 基站类型（0=无, 1=LTE, 2=WiFi, 3=LoRa） */
    public int getCellType() {
        return cellType;
    }

    /** @return 中继角色（0=无, 1=mesh, 2=HAPS, 3=LEO） */
    public int getRelayRole() {
        return relayRole;
    }

    /** @return 发射功率（dBm） */
    public int getTxPower() {
        return txPower;
    }

    /** @return 预期覆盖率（%） */
    public double getExpectedCoverage() {
        return expectedCoverage;
    }

    /** @return 电量预算（%） */
    public int getBatteryBudget() {
        return batteryBudget;
    }

    @Override
    public String toString() {
        return "DroneDeployment{droneId=" + droneId + ", lat=" + targetLat + ", lon=" + targetLon
                + ", alt=" + targetAlt + "m, cell=" + cellTypeName(cellType)
                + ", relay=" + relayRoleName(relayRole)
                + ", txPower=" + txPower + "dBm"
                + ", coverage=" + String.format("%.2f", expectedCoverage) + "%"
                + ", battery=" + batteryBudget + "%}";
    }

    /** 基站类型名称。 */
    private static String cellTypeName(int cellType) {
        switch (cellType) {
            case 1: return "LTE";
            case 2: return "WiFi";
            case 3: return "LoRa";
            default: return "none";
        }
    }

    /** 中继角色名称。 */
    private static String relayRoleName(int relayRole) {
        switch (relayRole) {
            case 1: return "mesh";
            case 2: return "HAPS";
            case 3: return "LEO";
            default: return "none";
        }
    }
}