package io.aerofleet.sim.mesh;

/**
 * 自适应路由度量（灾害应急通讯组网，FR-16）。
 * <p>
 * 6 维加权度量：跳数×W1 + RSSI×W2 + 延迟×W3 + 地形衰减×W4 + 链路稳定性×W5 + 节点电量×W6。
 * 权重在灾害模式下自动调整：
 * <ul>
 *   <li>搜救通讯（灾害模式）：偏重延迟与稳定性（W3/W5 增大）</li>
 *   <li>测绘通讯（正常模式）：偏重带宽（W2 增大）</li>
 * </ul>
 * 度量值越小表示路径越优。
 */
public final class AdaptiveRouteMetric {

    /** 灾害模式触发原因枚举。 */
    public enum DisasterScenario {
        /** 搜救通讯场景。 */
        SEARCH_AND_RESCUE,
        /** 测绘通讯场景。 */
        MAPPING,
        /** 通用灾害场景。 */
        GENERAL_DISASTER
    }

    // ===== 正常模式权重（测绘偏重带宽/RSSI） =====
    /** W1: 跳数权重。 */
    public static final double NORMAL_W1 = 1.0;
    /** W2: RSSI 权重（测绘偏重带宽 → RSSI 权重大）。 */
    public static final double NORMAL_W2 = 0.8;
    /** W3: 延迟权重。 */
    public static final double NORMAL_W3 = 0.1;
    /** W4: 地形衰减权重。 */
    public static final double NORMAL_W4 = 0.3;
    /** W5: 链路稳定性权重。 */
    public static final double NORMAL_W5 = 0.2;
    /** W6: 节点电量权重。 */
    public static final double NORMAL_W6 = 0.2;

    // ===== 灾害模式权重（搜救偏重延迟与稳定性） =====
    /** W1: 跳数权重（灾害模式降低，容忍多跳）。 */
    public static final double DISASTER_W1 = 0.5;
    /** W2: RSSI 权重（灾害模式降低）。 */
    public static final double DISASTER_W2 = 0.3;
    /** W3: 延迟权重（搜救偏重 → 延迟权重大幅增大）。 */
    public static final double DISASTER_W3 = 1.0;
    /** W4: 地形衰减权重（灾害模式增大，灾区地形复杂）。 */
    public static final double DISASTER_W4 = 0.5;
    /** W5: 链路稳定性权重（搜救偏重 → 稳定性权重大幅增大）。 */
    public static final double DISASTER_W5 = 1.0;
    /** W6: 节点电量权重（灾害模式增大，关注节点存活）。 */
    public static final double DISASTER_W6 = 0.5;

    // ===== 归一化常数 =====
    /** RSSI 归一化范围：[-120, 0] → [0, 1]，RSSI 越高（接近0）越好。 */
    public static final int RSSI_MIN = -120;
    public static final int RSSI_MAX = 0;

    /** 延迟归一化上限（ms）：超过此值按最大值处理。 */
    public static final double DELAY_MAX_MS = 5000.0;

    /** 地形衰减归一化上限（dB）。 */
    public static final double TERRAIN_ATTEN_MAX_DB = 30.0;

    /** 链路稳定性归一化：0=最不稳定，1=最稳定。 */
    public static final double STABILITY_MIN = 0.0;
    public static final double STABILITY_MAX = 1.0;

    /** 电量归一化范围：[0, 100] → [0, 1]。 */
    public static final int BATTERY_MIN = 0;
    public static final int BATTERY_MAX = 100;

    /** 当前权重集。 */
    private double w1, w2, w3, w4, w5, w6;

    /** 当前是否灾害模式。 */
    private volatile boolean disasterMode = false;

    /** 当前灾害场景（默认搜救，灾害模式最典型场景）。 */
    private volatile DisasterScenario scenario = DisasterScenario.SEARCH_AND_RESCUE;

    /**
     * 构造：默认正常模式权重。
     */
    public AdaptiveRouteMetric() {
        this.w1 = NORMAL_W1;
        this.w2 = NORMAL_W2;
        this.w3 = NORMAL_W3;
        this.w4 = NORMAL_W4;
        this.w5 = NORMAL_W5;
        this.w6 = NORMAL_W6;
    }

    /**
     * 计算度量值（FR-16）。
     * <p>
     * 度量 = hopCount×W1 + rssiNorm×W2 + delayNorm×W3
     *      + terrainNorm×W4 + stabilityNorm×W5 + batteryNorm×W6
     * <p>
     * 各维度归一化到 [0,1] 区间，度量值越小表示路径越优。
     *
     * @param route       路由表项
     * @param disasterMode 是否灾害模式
     * @return 度量值（越小越优）
     */
    public double calculateMetric(RouteEntry route, boolean disasterMode) {
        return calculateMetric(
                route.hopCount,
                -70, // 默认 RSSI（GOOD 级别中值）
                100.0, // 默认延迟 100ms
                5.0, // 默认地形衰减 5dB
                0.8, // 默认稳定性 0.8
                80, // 默认电量 80%
                disasterMode
        );
    }

    /**
     * 计算度量值（全参数版）。
     *
     * @param hopCount      跳数
     * @param rssiDbm       RSSI（dBm，[-120,0]）
     * @param delayMs       端到端延迟（ms）
     * @param terrainAttDb  地形衰减（dB）
     * @param stability     链路稳定性（0-1）
     * @param batteryPercent 节点电量（0-100）
     * @param disasterMode  是否灾害模式
     * @return 度量值（越小越优）
     */
    public double calculateMetric(int hopCount, int rssiDbm, double delayMs,
                                  double terrainAttDb, double stability,
                                  int batteryPercent, boolean disasterMode) {
        // 切换权重集
        if (disasterMode != this.disasterMode) {
            setDisasterMode(disasterMode);
        }

        // 归一化各维度到 [0,1]
        double hopNorm = Math.min(hopCount / 30.0, 1.0); // 跳数归一化：0-30 → 0-1
        double rssiNorm = normalizeRssi(rssiDbm); // RSSI 越高越好 → 归一化值越小
        double delayNorm = Math.min(delayMs / DELAY_MAX_MS, 1.0);
        double terrainNorm = Math.min(terrainAttDb / TERRAIN_ATTEN_MAX_DB, 1.0);
        double stabilityNorm = clamp(stability, STABILITY_MIN, STABILITY_MAX);
        // 稳定性越高越好 → 取反（1-stability）使度量值越小越优
        double stabilityCost = 1.0 - stabilityNorm;
        double batteryNorm = normalizeBattery(batteryPercent);
        // 电量越高越好 → 取反
        double batteryCost = 1.0 - batteryNorm;

        return hopNorm * w1
                + rssiNorm * w2
                + delayNorm * w3
                + terrainNorm * w4
                + stabilityCost * w5
                + batteryCost * w6;
    }

    /**
     * 设置灾害模式：自动调整权重。
     *
     * @param disasterMode 是否灾害模式
     */
    public void setDisasterMode(boolean disasterMode) {
        this.disasterMode = disasterMode;
        if (disasterMode) {
            applyDisasterWeights(scenario);
        } else {
            applyNormalWeights();
        }
    }

    /**
     * 设置灾害场景：调整灾害模式下的权重细分。
     *
     * @param scenario 灾害场景类型
     */
    public void setDisasterScenario(DisasterScenario scenario) {
        this.scenario = scenario;
        if (disasterMode) {
            applyDisasterWeights(scenario);
        }
    }

    /**
     * 应用正常模式权重（测绘偏重带宽）。
     */
    private void applyNormalWeights() {
        this.w1 = NORMAL_W1;
        this.w2 = NORMAL_W2;
        this.w3 = NORMAL_W3;
        this.w4 = NORMAL_W4;
        this.w5 = NORMAL_W5;
        this.w6 = NORMAL_W6;
    }

    /**
     * 应用灾害模式权重（搜救偏重延迟与稳定性）。
     */
    private void applyDisasterWeights(DisasterScenario scenario) {
        switch (scenario) {
            case SEARCH_AND_RESCUE -> {
                // 搜救：延迟和稳定性权重最大
                this.w1 = DISASTER_W1;
                this.w2 = DISASTER_W2;
                this.w3 = DISASTER_W3;
                this.w4 = DISASTER_W4;
                this.w5 = DISASTER_W5;
                this.w6 = DISASTER_W6;
            }
            case MAPPING -> {
                // 测绘灾害：带宽仍偏重，但延迟与稳定性也增大
                this.w1 = DISASTER_W1;
                this.w2 = 0.6; // 测绘仍偏重 RSSI/带宽
                this.w3 = 0.5;
                this.w4 = DISASTER_W4;
                this.w5 = 0.5;
                this.w6 = DISASTER_W6;
            }
            case GENERAL_DISASTER -> {
                // 通用灾害：均衡调整
                this.w1 = DISASTER_W1;
                this.w2 = DISASTER_W2;
                this.w3 = 0.7;
                this.w4 = DISASTER_W4;
                this.w5 = 0.7;
                this.w6 = DISASTER_W6;
            }
        }
    }

    /** RSSI 归一化：[-120,0] → [1,0]，RSSI 越高（接近0）归一化值越小。 */
    private static double normalizeRssi(int rssiDbm) {
        double clamped = clamp(rssiDbm, RSSI_MIN, RSSI_MAX);
        // RSSI=-120 → 1.0（最差），RSSI=0 → 0.0（最好）
        return (clamped - RSSI_MAX) / (double) (RSSI_MIN - RSSI_MAX);
    }

    /** 电量归一化：[0,100] → [0,1]。 */
    private static double normalizeBattery(int batteryPercent) {
        return clamp(batteryPercent, BATTERY_MIN, BATTERY_MAX) / 100.0;
    }

    /** 数值限幅。 */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 当前 W1 权重。 */
    public double getW1() { return w1; }
    /** 当前 W2 权重。 */
    public double getW2() { return w2; }
    /** 当前 W3 权重。 */
    public double getW3() { return w3; }
    /** 当前 W4 权重。 */
    public double getW4() { return w4; }
    /** 当前 W5 权重。 */
    public double getW5() { return w5; }
    /** 当前 W6 权重。 */
    public double getW6() { return w6; }
    /** 是否灾害模式。 */
    public boolean isDisasterMode() { return disasterMode; }
}