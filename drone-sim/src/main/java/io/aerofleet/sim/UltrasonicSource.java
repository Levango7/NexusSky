package io.aerofleet.sim;

import java.util.Random;

/**
 * 超声波避障数据源（丐版雷达替代）。
 * 模拟 HC-SR04 阵列（前/后/左/右 4 个传感器），
 * 测距范围 2-400cm，精度 ±3mm。
 */
public class UltrasonicSource {
    private final double maxRangeM;      // 4.0
    private final double minRangeM;      // 0.02
    private final int sensorCount;       // 4 (前/后/左/右)
    private final Random rng;

    /** HC-SR04 精度 ±3mm。 */
    private static final double ACCURACY_M = 0.003;

    public UltrasonicSource() {
        this(4.0, 4, new Random());
    }

    public UltrasonicSource(double maxRangeM, int sensorCount) {
        this(maxRangeM, sensorCount, new Random());
    }

    /** 测试可注入确定性 Random。 */
    public UltrasonicSource(double maxRangeM, int sensorCount, Random rng) {
        if (maxRangeM <= 0) {
            throw new IllegalArgumentException("maxRangeM must be positive");
        }
        if (sensorCount <= 0) {
            throw new IllegalArgumentException("sensorCount must be positive");
        }
        this.maxRangeM = maxRangeM;
        this.minRangeM = 0.02; // HC-SR04 最小测距 2cm
        this.sensorCount = sensorCount;
        this.rng = rng != null ? rng : new Random();
    }

    /**
     * 模拟测距：给定真实距离，返回带噪声的读数。
     * 每个读数先 clamp 到 [minRange, maxRange]，再叠加 ±3mm 噪声并再次 clamp。
     *
     * @param trueDistances 真实距离数组（长度须等于 sensorCount）
     * @return 带噪声的读数数组
     */
    public double[] measure(double[] trueDistances) {
        if (trueDistances == null || trueDistances.length != sensorCount) {
            throw new IllegalArgumentException(
                    "trueDistances length must equal sensorCount=" + sensorCount);
        }
        double[] readings = new double[sensorCount];
        for (int i = 0; i < sensorCount; i++) {
            double d = clamp(trueDistances[i], minRangeM, maxRangeM);
            // ±3mm 噪声
            double noise = (rng.nextDouble() * 2 - 1) * ACCURACY_M;
            readings[i] = clamp(d + noise, minRangeM, maxRangeM);
        }
        return readings;
    }

    /**
     * 检测最近障碍物方向（0=前,1=后,2=左,3=右），无障碍返回 -1。
     *
     * @param readings   测距读数
     * @param thresholdM 障碍阈值（米），读数小于此值视为有障碍
     * @return 最近障碍方向索引，或 -1
     */
    public int nearestObstacleDirection(double[] readings, double thresholdM) {
        if (readings == null || readings.length != sensorCount) {
            throw new IllegalArgumentException(
                    "readings length must equal sensorCount=" + sensorCount);
        }
        int nearest = -1;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < sensorCount; i++) {
            if (readings[i] < thresholdM && readings[i] < minDist) {
                minDist = readings[i];
                nearest = i;
            }
        }
        return nearest;
    }

    public double maxRangeM() {
        return maxRangeM;
    }

    public int sensorCount() {
        return sensorCount;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}