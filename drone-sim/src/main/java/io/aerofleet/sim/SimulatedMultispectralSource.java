package io.aerofleet.sim;

import java.util.Random;

/**
 * 模拟多光谱数据源（M3 感知成像增强，FR-05/FR-06）。
 * <p>
 * 基于地面覆盖类型合成 NIR/Red 波段值，计算 NDVI。
 * <ul>
 *   <li>植被区域：NIR 高（180-220）Red 低（60-80）→ NDVI>0.2</li>
 *   <li>裸土区域：NIR≈Red（90-120）→ NDVI≈0</li>
 *   <li>水体区域：NIR 低（40-60）Red 高（120-160）→ NDVI<0</li>
 * </ul>
 */
public class SimulatedMultispectralSource implements MultispectralSource {

    private final Random rng;

    public SimulatedMultispectralSource() {
        this(new Random());
    }

    /** 测试可注入确定性 Random。 */
    public SimulatedMultispectralSource(Random rng) {
        this.rng = rng;
    }

    @Override
    public NdviResult computeNdvi(double[][] nir, double[][] red) {
        if (nir == null || red == null) {
            throw new IllegalArgumentException("NIR and Red bands required for NDVI");
        }
        int h = nir.length;
        if (h == 0 || nir[0].length == 0) {
            throw new IllegalArgumentException("bands must be non-empty");
        }
        int w = nir[0].length;
        if (red.length != h || red[0].length != w) {
            throw new IllegalArgumentException("NIR and Red bands must have same dimensions");
        }
        double[][] ndvi = new double[h][w];
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int vegPixels = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double denom = nir[y][x] + red[y][x];
                // 分母为 0 时 NDVI=0（避免除零）
                ndvi[y][x] = denom == 0 ? 0 : (nir[y][x] - red[y][x]) / denom;
                sum += ndvi[y][x];
                min = Math.min(min, ndvi[y][x]);
                max = Math.max(max, ndvi[y][x]);
                // 植被覆盖率：NDVI>0.2 的像素占比
                if (ndvi[y][x] > 0.2) {
                    vegPixels++;
                }
            }
        }
        int total = h * w;
        return new NdviResult(ndvi, sum / total, min, max, (double) vegPixels / total);
    }

    @Override
    public double[][] bandComposite(double[][][] bands, double[] weights) {
        if (bands == null || weights == null || bands.length != weights.length) {
            throw new IllegalArgumentException("bands and weights must be non-null and same length");
        }
        if (bands.length == 0) {
            throw new IllegalArgumentException("no bands provided");
        }
        int h = bands[0].length;
        int w = bands[0][0].length;
        double[][] out = new double[h][w];
        for (int i = 0; i < bands.length; i++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[y][x] += weights[i] * bands[i][y][x];
                }
            }
        }
        return out;
    }

    /**
     * FR-06 基于地面覆盖类型合成 NIR/Red 波段。
     * <p>
     * 植被 NIR 高 Red 低 / 裸土 NIR≈Red / 水体 NIR 低 Red 高。
     *
     * @param width  影像宽度
     * @param height 影像高度
     * @param cover  地面覆盖类型矩阵（[height][width]）
     * @return 双波段影像 [0]=NIR, [1]=Red
     */
    public double[][][] synthesizeBands(int width, int height, GroundCover[][] cover) {
        if (cover == null || cover.length != height || cover[0].length != width) {
            throw new IllegalArgumentException("cover matrix must match width x height");
        }
        double[][] nir = new double[height][width];
        double[][] red = new double[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                switch (cover[y][x]) {
                    case VEGETATION -> {
                        nir[y][x] = 180 + rng.nextDouble() * 40;   // NIR 高（180-220）
                        red[y][x] = 60 + rng.nextDouble() * 20;    // Red 低（60-80）
                    }
                    case BARE_SOIL -> {
                        nir[y][x] = 100 + rng.nextDouble() * 20;  // NIR（100-120）
                        red[y][x] = 90 + rng.nextDouble() * 20;   // Red（90-110）≈ NIR
                    }
                    case WATER -> {
                        nir[y][x] = 40 + rng.nextDouble() * 20;   // NIR 低（40-60）
                        red[y][x] = 120 + rng.nextDouble() * 40;  // Red 高（120-160）
                    }
                }
            }
        }
        return new double[][][]{nir, red};
    }
}