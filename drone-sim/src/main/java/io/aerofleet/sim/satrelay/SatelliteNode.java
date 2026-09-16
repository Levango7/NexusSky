package io.aerofleet.sim.satrelay;

/**
 * 卫星节点（M7 星-空-地多层级中继，FR-5.1，数据约束 6.1）。
 * <p>
 * 配置（final 不可变）：satId / 轨道高度 / 倾角 / RAAN / 初始平近点角。
 * 状态（volatile）：可见性 / 仰角 / 方位角 / 当前平近点角 / ECI 坐标。
 * <p>
 * 构造器校验轨道参数物理范围（FR-5.1.1.1/2/8），非法参数抛 IllegalArgumentException。
 */
public final class SatelliteNode {

    // ===== 配置（final 不可变） =====
    private final int satId;
    private final double orbitAltitudeKm;
    private final double inclinationDeg;
    private final double raanDeg;
    private final double initialMeanAnomalyDeg;

    // ===== 状态（volatile，tick 线程写，读线程读） =====
    private volatile boolean visible = false;
    private volatile double elevationDeg = 0.0;
    private volatile double azimuthDeg = 0.0;
    private volatile double currentMeanAnomalyDeg;
    private volatile double[] eciPositionKm = new double[]{0, 0, 0};

    /**
     * 构造器：校验轨道参数物理范围。
     *
     * @param satId               卫星 ID（正整数）
     * @param orbitAltitudeKm     轨道高度（300-1200 km）
     * @param inclinationDeg      轨道倾角（0-180°）
     * @param raanDeg             升交点赤经（0-360°）
     * @param initialMeanAnomalyDeg 初始平近点角（0-360°）
     * @throws IllegalArgumentException 参数缺失或越界
     */
    public SatelliteNode(int satId, double orbitAltitudeKm, double inclinationDeg,
                         double raanDeg, double initialMeanAnomalyDeg) {
        if (satId <= 0) {
            throw new IllegalArgumentException("satId must be positive, got " + satId);
        }
        if (orbitAltitudeKm < 300.0 || orbitAltitudeKm > 1200.0) {
            throw new IllegalArgumentException(
                    "orbitAltitudeKm must be in [300, 1200] km, got " + orbitAltitudeKm);
        }
        if (inclinationDeg < 0.0 || inclinationDeg > 180.0) {
            throw new IllegalArgumentException(
                    "inclinationDeg must be in [0, 180] deg, got " + inclinationDeg);
        }
        if (raanDeg < 0.0 || raanDeg >= 360.0) {
            throw new IllegalArgumentException(
                    "raanDeg must be in [0, 360) deg, got " + raanDeg);
        }
        if (initialMeanAnomalyDeg < 0.0 || initialMeanAnomalyDeg >= 360.0) {
            throw new IllegalArgumentException(
                    "initialMeanAnomalyDeg must be in [0, 360) deg, got " + initialMeanAnomalyDeg);
        }
        this.satId = satId;
        this.orbitAltitudeKm = orbitAltitudeKm;
        this.inclinationDeg = inclinationDeg;
        this.raanDeg = raanDeg;
        this.initialMeanAnomalyDeg = initialMeanAnomalyDeg;
        this.currentMeanAnomalyDeg = initialMeanAnomalyDeg;
    }

    // ===== 配置访问器 =====
    public int satId() {
        return satId;
    }

    public double orbitAltitudeKm() {
        return orbitAltitudeKm;
    }

    public double inclinationDeg() {
        return inclinationDeg;
    }

    public double raanDeg() {
        return raanDeg;
    }

    public double initialMeanAnomalyDeg() {
        return initialMeanAnomalyDeg;
    }

    // ===== 状态访问器 =====
    public boolean isVisible() {
        return visible;
    }

    public double elevationDeg() {
        return elevationDeg;
    }

    public double azimuthDeg() {
        return azimuthDeg;
    }

    public double currentMeanAnomalyDeg() {
        return currentMeanAnomalyDeg;
    }

    public double[] eciPositionKm() {
        return eciPositionKm.clone();
    }

    /**
     * 更新轨道位置（由 LeoConstellation.tick 调用）。
     * <p>
     * 平近点角按时间线性演化，ECI 坐标随之更新（FR-5.1.1.3）。
     *
     * @param timeMs 仿真时钟（ms）
     */
    public void updatePosition(long timeMs) {
        this.currentMeanAnomalyDeg = OrbitModel.evolvedMeanAnomaly(
                initialMeanAnomalyDeg, orbitAltitudeKm, timeMs);
        this.eciPositionKm = OrbitModel.positionAt(
                orbitAltitudeKm, inclinationDeg, raanDeg, initialMeanAnomalyDeg, timeMs);
    }

    /**
     * 更新可见性状态（由 LinkWindowCalculator 调用）。
     *
     * @param visible      是否可见
     * @param elevationDeg 仰角（度）
     * @param azimuthDeg   方位角（度）
     */
    public void updateVisibility(boolean visible, double elevationDeg, double azimuthDeg) {
        this.visible = visible;
        this.elevationDeg = visible ? elevationDeg : 0.0;
        this.azimuthDeg = azimuthDeg;
    }

    @Override
    public String toString() {
        return "SatelliteNode{sat=" + satId + ", alt=" + orbitAltitudeKm + "km"
                + ", inc=" + inclinationDeg + "°, raan=" + raanDeg + "°"
                + ", M=" + currentMeanAnomalyDeg + "°, visible=" + visible
                + ", el=" + elevationDeg + "°}";
    }
}