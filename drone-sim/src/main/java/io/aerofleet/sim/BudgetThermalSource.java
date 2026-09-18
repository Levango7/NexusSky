package io.aerofleet.sim;

import java.util.Random;

/**
 * 红外阵列热源（丐版热成像替代）。
 * 模拟 MLX90640 32×24 红外阵列，
 * 温度范围 -40~300°C，精度 ±1°C。
 */
public class BudgetThermalSource {
    public static final int WIDTH = 32;
    public static final int HEIGHT = 24;

    private final double minTempC;   // -40
    private final double maxTempC;   // 300
    private final double accuracyC;  // 1.0
    private final Random rng;

    public BudgetThermalSource() {
        this(-40, 300, 1.0, new Random());
    }

    public BudgetThermalSource(double min, double max, double acc) {
        this(min, max, acc, new Random());
    }

    /** 测试可注入确定性 Random。 */
    public BudgetThermalSource(double min, double max, double acc, Random rng) {
        if (min >= max) {
            throw new IllegalArgumentException("min must be less than max");
        }
        if (acc <= 0) {
            throw new IllegalArgumentException("accuracy must be positive");
        }
        this.minTempC = min;
        this.maxTempC = max;
        this.accuracyC = acc;
        this.rng = rng != null ? rng : new Random();
    }

    /**
     * 生成温度矩阵：给定环境温度和热源列表，返回 32×24 温度数组。
     * <p>
     * heatSources 每行格式：[cx, cy, intensity, sigma]
     * <ul>
     *   <li>cx, cy：热源中心像素坐标</li>
     *   <li>intensity：热源强度（°C，叠加到环境温度）</li>
     *   <li>sigma：高斯衰减标准差（像素）</li>
     * </ul>
     * 每个像素 = clamp(ambient + Σ intensity·exp(-d²/2σ²) + 噪声, min, max)
     *
     * @param ambientTempC 环境温度（°C）
     * @param heatSources  热源列表（N×4），可为 null
     * @return HEIGHT×WIDTH 温度数组（°C）
     */
    public double[][] generateFrame(double ambientTempC, double[][] heatSources) {
        double[][] frame = new double[HEIGHT][WIDTH];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                double temp = ambientTempC;
                if (heatSources != null) {
                    for (double[] src : heatSources) {
                        double cx = src[0];
                        double cy = src[1];
                        double intensity = src[2];
                        double sigma = src[3];
                        double dist = Math.hypot(x - cx, y - cy);
                        temp += intensity * Math.exp(-dist * dist / (2 * sigma * sigma));
                    }
                }
                // ±accuracyC 噪声
                double noise = (rng.nextDouble() * 2 - 1) * accuracyC;
                frame[y][x] = clamp(temp + noise, minTempC, maxTempC);
            }
        }
        return frame;
    }

    /**
     * 检测热源位置：返回最热像素坐标 [x, y] 和温度，无热源返回 null。
     *
     * @param frame          温度矩阵
     * @param thresholdTempC 热点阈值（°C），最热像素温度须超过此值
     * @return [x, y, tempC] 或 null
     */
    public double[] detectHotspot(double[][] frame, double thresholdTempC) {
        if (frame == null || frame.length == 0 || frame[0].length == 0) {
            return null;
        }
        double maxTemp = -Double.MAX_VALUE;
        int maxX = -1, maxY = -1;
        for (int y = 0; y < frame.length; y++) {
            for (int x = 0; x < frame[y].length; x++) {
                if (frame[y][x] > maxTemp) {
                    maxTemp = frame[y][x];
                    maxX = x;
                    maxY = y;
                }
            }
        }
        if (maxTemp > thresholdTempC) {
            return new double[]{maxX, maxY, maxTemp};
        }
        return null;
    }

    public int width() {
        return WIDTH;
    }

    public int height() {
        return HEIGHT;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}