package io.aerofleet.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * AMG8833 红外热源阵列传感器模拟（千元级灾害应急配置）。
 * <p>
 * 模拟 AMG8833 8×8 像素红外热源阵列传感器：
 * <ul>
 *   <li>分辨率：8×8 像素（64 像素）</li>
 *   <li>温度范围：0~80°C</li>
 *   <li>精度：±2.5°C（典型值）</li>
 *   <li>检测距离：最远 7m（人体热源）</li>
 * </ul>
 * 用于灾害应急场景下废墟人员搜救：检测温度 >37°C 的人体热源，
 * 输出热源位置（像素坐标 + 温度 + 置信度）。
 * <p>
 * 实现 {@link ThermalSource} 接口，使热成像处理链可直接使用 AMG8833
 * 替代昂贵的热成像相机。接口方法 {@link #analyze}/{@link #detectHotspots}
 * 对任意温度场矩阵工作（不限于本类 {@link #generateFrame} 的输出）。
 */
public final class BudgetThermalSource implements ThermalSource {

    /** AMG8833 阵列宽度（像素）。 */
    public static final int WIDTH = 8;
    /** AMG8833 阵列高度（像素）。 */
    public static final int HEIGHT = 8;
    /** AMG8833 最低检测温度（°C）。 */
    public static final double MIN_TEMP_C = 0.0;
    /** AMG8833 最高检测温度（°C）。 */
    public static final double MAX_TEMP_C = 80.0;
    /** AMG8833 典型精度（±°C）。 */
    public static final double ACCURACY_C = 2.5;
    /** 人体热源检测阈值（°C），高于此值视为人体热源。 */
    public static final double HUMAN_TEMP_THRESHOLD = 37.0;

    private final double minTempC;
    private final double maxTempC;
    private final double accuracyC;
    private final Random rng;

    public BudgetThermalSource() {
        this(MIN_TEMP_C, MAX_TEMP_C, ACCURACY_C, new Random());
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
     * 生成 AMG8833 8×8 温度矩阵：给定环境温度和热源列表，返回 8×8 温度数组。
     * <p>
     * heatSources 每行格式：[cx, cy, intensity, sigma]
     * <ul>
     *   <li>cx, cy：热源中心像素坐标（0~7）</li>
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
     * 检测最热像素位置：返回最热像素坐标 [x, y] 和温度，无热源返回 null。
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

    /**
     * 检测人体热源：扫描 8×8 温度矩阵，返回温度 >37°C 的热源列表。
     * <p>
     * 每个热源包含像素坐标、温度和置信度。置信度基于温度超出阈值的程度计算：
     * <ul>
     *   <li>温度 = 37°C → 置信度 ≈ 0.0</li>
     *   <li>温度 = 42°C → 置信度 ≈ 0.5</li>
     *   <li>温度 = 47°C → 置信度 ≈ 1.0</li>
     * </ul>
     * 相邻超阈值像素（4 邻域连通）聚合为同一热源，取峰值温度和中心坐标。
     *
     * @param frame 8×8 温度矩阵（°C）
     * @return 热源列表（空列表表示无人体热源）
     */
    public List<HeatSource> detectHeatSources(double[][] frame) {
        return detectHeatSources(frame, HUMAN_TEMP_THRESHOLD);
    }

    /**
     * 检测热源：扫描温度矩阵，返回温度超过指定阈值的熱源列表。
     * <p>
     * 相邻超阈值像素（4 邻域连通）聚合为同一热源，取峰值温度和质心坐标。
     * 置信度 = clamp((峰值温度 - 阈值) / 10, 0, 1)。
     *
     * @param frame          温度矩阵（°C）
     * @param thresholdTempC 热源阈值（°C）
     * @return 热源列表（空列表表示无热源）
     */
    public List<HeatSource> detectHeatSources(double[][] frame, double thresholdTempC) {
        List<HeatSource> out = new ArrayList<>();
        if (frame == null || frame.length == 0 || frame[0].length == 0) {
            return out;
        }
        int h = frame.length;
        int w = frame[0].length;
        boolean[][] visited = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (visited[y][x] || frame[y][x] <= thresholdTempC) {
                    continue;
                }
                // 聚合连通的超阈值像素为热源（4 邻域 flood fill）
                int[] area = {0};
                double[] peakTemp = {frame[y][x]};
                double[] sumX = {0};
                double[] sumY = {0};
                floodHeatSource(frame, visited, x, y, w, h, thresholdTempC, area, peakTemp, sumX, sumY);
                // 质心坐标
                double cx = sumX[0] / area[0];
                double cy = sumY[0] / area[0];
                // 置信度：温度超出阈值越多，置信度越高
                double confidence = clamp((peakTemp[0] - thresholdTempC) / 10.0, 0.0, 1.0);
                out.add(new HeatSource(cx, cy, peakTemp[0], confidence));
            }
        }
        return out;
    }

    public int width() {
        return WIDTH;
    }

    public int height() {
        return HEIGHT;
    }

    /** AMG8833 热源检测结果（像素坐标 + 温度 + 置信度）。 */
    public record HeatSource(double pixelX, double pixelY, double tempC, double confidence) {
        public HeatSource {
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0, 1]");
            }
        }
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

    /** 4 邻域 flood fill 聚合超阈值像素为热源（计算质心 + 峰值温度）。 */
    private void floodHeatSource(double[][] m, boolean[][] visited, int sx, int sy,
                                  int w, int h, double threshold,
                                  int[] area, double[] peak, double[] sumX, double[] sumY) {
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{sx, sy});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int x = p[0], y = p[1];
            if (x < 0 || x >= w || y < 0 || y >= h || visited[y][x]) continue;
            if (m[y][x] <= threshold) continue;
            visited[y][x] = true;
            area[0]++;
            sumX[0] += x;
            sumY[0] += y;
            if (m[y][x] > peak[0]) peak[0] = m[y][x];
            // 4 邻域
            stack.push(new int[]{x + 1, y});
            stack.push(new int[]{x - 1, y});
            stack.push(new int[]{x, y + 1});
            stack.push(new int[]{x, y - 1});
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
