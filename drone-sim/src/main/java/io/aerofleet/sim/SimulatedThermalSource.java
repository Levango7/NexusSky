package io.aerofleet.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 模拟热成像数据源（M3 感知成像增强，FR-08/FR-09）。
 * <p>
 * 基于合成热源产出温度场矩阵：背景温度 20-30℃ + 合成热源 50-100℃ 局部升温（高斯衰减）。
 * 热点检测：局部极大值超阈值的像素聚合为热点。
 */
public class SimulatedThermalSource implements ThermalSource {

    private final Random rng;

    public SimulatedThermalSource() {
        this(new Random());
    }

    /** 测试可注入确定性 Random。 */
    public SimulatedThermalSource(Random rng) {
        this.rng = rng;
    }

    @Override
    public ThermalResult analyze(double[][] matrix) {
        if (matrix == null || matrix.length == 0 || matrix[0].length == 0) {
            throw new IllegalArgumentException("thermal matrix must be non-empty");
        }
        int h = matrix.length;
        int w = matrix[0].length;
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double t = matrix[y][x];
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
                double d = matrix[y][x] - mean;
                varSum += d * d;
            }
        }
        double stdDev = Math.sqrt(varSum / (h * w));
        // 热点检测：默认阈值 = 均值 + 2σ（统计意义下的显著高温）
        double threshold = mean + 2 * stdDev;
        List<Hotspot> hotspots = detectHotspots(matrix, threshold);
        return new ThermalResult(mean, min, max, stdDev, hotspots);
    }

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
                // 聚合连通的超阈值像素为热点（简化：8 邻域 flood fill）
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

    /**
     * FR-09 合成温度场：背景 20-30℃ + 合成热源高斯衰减升温。
     *
     * @param width   影像宽度
     * @param height  影像高度
     * @param sources 合成热源列表
     * @return 温度场矩阵（℃）
     */
    public double[][] synthesizeThermalField(int width, int height, List<HeatSource> sources) {
        double[][] field = new double[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // 背景 20-30℃
                field[y][x] = 20 + rng.nextDouble() * 10;
                // 热源局部升温（高斯衰减）
                if (sources != null) {
                    for (HeatSource s : sources) {
                        double dist = Math.hypot(x - s.cx(), y - s.cy());
                        field[y][x] += s.intensity() * Math.exp(
                                -dist * dist / (2 * s.sigma() * s.sigma()));
                    }
                }
            }
        }
        return field;
    }

    /** 合成热源 record（中心 + 强度 + 高斯衰减 sigma）。 */
    public record HeatSource(double cx, double cy, double intensity, double sigma) {
        public HeatSource {
            if (sigma <= 0) {
                throw new IllegalArgumentException("sigma must be positive");
            }
        }
    }
}