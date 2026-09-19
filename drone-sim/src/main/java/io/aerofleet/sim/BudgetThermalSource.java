package io.aerofleet.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 红外阵列热源（丐版热成像替代）。
 * 模拟 MLX90640 32×24 红外阵列，
 * 温度范围 -40~300°C，精度 ±1°C。
 * <p>
 * 实现 {@link ThermalSource} 接口，使热成像处理链可直接使用丐版红外阵列
 * 替代昂贵的热成像相机。接口方法 {@link #analyze}/{@link #detectHotspots}
 * 对任意温度场矩阵工作（不限于本类 {@link #generateFrame} 的输出）。
 */
public class BudgetThermalSource implements ThermalSource {
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
                        if (src == null || src.length < 4) {
                            continue;
                        }
                        double cx = src[0];
                        double cy = src[1];
                        double intensity = src[2];
                        double sigma = src[3];
                        if (sigma <= 0) {
                            continue;
                        }
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

    // ===== ThermalSource 接口实现 =====
    // 对任意温度场矩阵提供统计分析与热点检测，与 SimulatedThermalSource 行为对齐。

    /**
     * 分析温度场：计算均值/最小/最高/标准差，并以默认阈值（均值+2σ）检测热点。
     *
     * @param thermalMatrix 温度场矩阵（℃，非 null，矩形非空）
     * @return 温度统计 + 热点列表
     */
    @Override
    public ThermalResult analyze(double[][] thermalMatrix) {
        if (thermalMatrix == null || thermalMatrix.length == 0 || thermalMatrix[0].length == 0) {
            throw new IllegalArgumentException("thermal matrix must be non-empty");
        }
        int h = thermalMatrix.length;
        int w = thermalMatrix[0].length;
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double t = thermalMatrix[y][x];
                sum += t;
                min = Math.min(min, t);
                max = Math.max(max, t);
            }
        }
        double mean = sum / (h * w);
        // 标准差
        double varSum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double d = thermalMatrix[y][x] - mean;
                varSum += d * d;
            }
        }
        double stdDev = Math.sqrt(varSum / (h * w));
        // 热点检测：默认阈值 = 均值 + 2σ（统计意义下的显著高温）
        double threshold = mean + 2 * stdDev;
        List<Hotspot> hotspots = detectHotspots(thermalMatrix, threshold);
        return new ThermalResult(mean, min, max, stdDev, hotspots);
    }

    /**
     * 热点检测：检测局部极大值超阈值的像素，聚合为热点（位置 + 温度 + 面积）。
     * <p>
     * 采用 4 邻域局部极大值判断 + 8 邻域 flood fill 聚合超阈值连通区域。
     * 温度场全均匀时返回空列表。
     *
     * @param matrix     温度场矩阵（℃）
     * @param thresholdC 热点阈值（℃），高于此值且为局部极大值的像素为热点
     * @return 热点列表（空列表表示无热点）
     */
    @Override
    public List<Hotspot> detectHotspots(double[][] matrix, double thresholdC) {
        List<Hotspot> out = new ArrayList<>();
        if (matrix == null || matrix.length == 0 || matrix[0].length == 0) {
            return out;
        }
        int h = matrix.length;
        int w = matrix[0].length;
        boolean[][] visited = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (visited[y][x] || matrix[y][x] <= thresholdC) {
                    continue;
                }
                // 检查是否为局部极大值（4 邻域）
                if (!isLocalMax(matrix, x, y, w, h)) {
                    continue;
                }
                // 聚合连通的超阈值像素为热点（8 邻域 flood fill）
                int[] area = {0};
                double[] peakTemp = {matrix[y][x]};
                floodFillHotspot(matrix, visited, x, y, w, h, thresholdC, area, peakTemp);
                out.add(new Hotspot(x, y, peakTemp[0], area[0]));
            }
        }
        return out;
    }

    /** 4 邻域局部极大值判断。 */
    private boolean isLocalMax(double[][] m, int x, int y, int w, int h) {
        double v = m[y][x];
        if (y > 0 && m[y - 1][x] > v) return false;
        if (y < h - 1 && m[y + 1][x] > v) return false;
        if (x > 0 && m[y][x - 1] > v) return false;
        if (x < w - 1 && m[y][x + 1] > v) return false;
        return true;
    }

    /** 8 邻域 flood fill 聚合超阈值像素（简化热点面积计算）。 */
    private void floodFillHotspot(double[][] m, boolean[][] visited, int sx, int sy,
                                  int w, int h, double threshold, int[] area, double[] peak) {
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{sx, sy});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int x = p[0], y = p[1];
            if (x < 0 || x >= w || y < 0 || y >= h || visited[y][x]) continue;
            if (m[y][x] <= threshold) continue;
            visited[y][x] = true;
            area[0]++;
            if (m[y][x] > peak[0]) peak[0] = m[y][x];
            // 8 邻域
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    stack.push(new int[]{x + dx, y + dy});
                }
            }
        }
    }
}