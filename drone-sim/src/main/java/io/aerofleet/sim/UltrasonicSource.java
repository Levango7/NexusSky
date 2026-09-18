package io.aerofleet.sim;

import java.util.Random;

/**
 * 超声波避障数据源（丐版雷达替代）。
 * 模拟 HC-SR04 阵列（前/后/左/右 4 个传感器），
 * 测距范围 2-400cm，精度 ±3mm。
 * <p>
 * 实现 {@link DepthSource} 接口，使 {@link ObstacleDetector} 等感知组件
 * 可直接使用丐版超声波传感器替代昂贵的相控阵雷达/LiDAR。
 * 接口方法基于最近一次 {@link #measure} 的读数工作。
 */
public class UltrasonicSource implements DepthSource {
    private final double maxRangeM;      // 4.0
    private final double minRangeM;      // 0.02
    private final int sensorCount;       // 4 (前/后/左/右)
    private final Random rng;

    /** HC-SR04 精度 ±3mm。 */
    private static final double ACCURACY_M = 0.003;

    /**
     * 4 个传感器方向角（度），索引对应 measure 读数顺序：
     * 0=前(0°), 1=后(180°), 2=左(90°), 3=右(270°)。
     */
    private static final double[] DIRECTIONS_DEG = {0, 180, 90, 270};

    /** 无障碍位置的远距填充值（米），与 SimulatedDepthSource 对齐。 */
    public static final double FAR_DISTANCE_M = 100.0;

    /** 最近一次 measure 的读数（接口方法基于此工作），null 表示尚未测量。 */
    private double[] lastReadings = null;

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
     * <p>
     * 读数会缓存到 lastReadings，供 {@link #depthMap}/{@link #nearestObstacle}/
     * {@link #pointCloudStats} 使用。
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
            // 量程外（真实距离 >= maxRange）直接返回 maxRange，不加噪声：
            // 物理上传感器在量程外检测不到障碍，返回精确最大量程值，
            // 避免 maxRange ± 噪声导致 nearestObstacle 误判为有障碍。
            if (d >= maxRangeM) {
                readings[i] = maxRangeM;
                continue;
            }
            // ±3mm 噪声
            double noise = (rng.nextDouble() * 2 - 1) * ACCURACY_M;
            readings[i] = clamp(d + noise, minRangeM, maxRangeM);
        }
        this.lastReadings = readings;
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

    // ===== DepthSource 接口实现 =====
    // 基于 lastReadings（最近一次 measure 的结果）提供深度图/最近障碍/点云统计。
    // 传感器索引 → 方向：0=前(0°), 1=后(180°), 2=左(90°), 3=右(270°)。
    // "有障碍" 判定：读数 < maxRangeM（传感器未达到最大量程，说明量程内检测到障碍）。

    /**
     * 简化深度图：4×4 矩阵，将 4 方向传感器读数按方向填充到对应边缘行/列，
     * 中心区域填 {@link #FAR_DISTANCE_M}。未调用 measure 时全部填远距。
     * <p>
     * 布局：
     * <pre>
     * [front front front front]
     * [left  FAR   FAR   right]
     * [left  FAR   FAR   right]
     * [back  back  back  back ]
     * </pre>
     *
     * @return 4×4 深度图矩阵（米）
     */
    @Override
    public double[][] depthMap() {
        double[][] map = new double[4][4];
        double front = readingOrFar(0);
        double back = readingOrFar(1);
        double left = readingOrFar(2);
        double right = readingOrFar(3);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                if (y == 0) {
                    map[y][x] = front;
                } else if (y == 3) {
                    map[y][x] = back;
                } else if (x == 0) {
                    map[y][x] = left;
                } else if (x == 3) {
                    map[y][x] = right;
                } else {
                    map[y][x] = FAR_DISTANCE_M;
                }
            }
        }
        return map;
    }

    /**
     * 最近障碍：从 lastReadings 中找最小读数（即最近障碍距离）。
     * 读数 &lt; maxRangeM 视为有障碍；全部达到 maxRangeM 或未测量时返回 Double.MAX_VALUE。
     *
     * @return 最近障碍（距离 + 方向度），无障碍时 distance=Double.MAX_VALUE
     */
    @Override
    public NearestObstacle nearestObstacle() {
        if (lastReadings == null) {
            return new NearestObstacle(Double.MAX_VALUE, 0);
        }
        double nearest = Double.MAX_VALUE;
        double direction = 0;
        for (int i = 0; i < sensorCount; i++) {
            if (lastReadings[i] < maxRangeM && lastReadings[i] < nearest) {
                nearest = lastReadings[i];
                direction = directionDeg(i);
            }
        }
        return new NearestObstacle(nearest, direction);
    }

    /**
     * 点云统计：基于检测到障碍的传感器数量生成简化统计。
     * <ul>
     *   <li>pointCount = 有障碍的传感器数 × 100（每个方向模拟 100 个点）</li>
     *   <li>density = pointCount / 传感器覆盖面积（maxRangeM² × π）</li>
     *   <li>nearestDistance = 最近障碍距离（无障碍时 Double.MAX_VALUE）</li>
     * </ul>
     *
     * @return 点云统计
     */
    @Override
    public PointCloudStats pointCloudStats() {
        if (lastReadings == null) {
            return new PointCloudStats(0, 0, Double.MAX_VALUE);
        }
        int obstacleSensors = 0;
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < sensorCount; i++) {
            if (lastReadings[i] < maxRangeM) {
                obstacleSensors++;
                if (lastReadings[i] < nearest) {
                    nearest = lastReadings[i];
                }
            }
        }
        int pointCount = obstacleSensors * 100;
        double coverageArea = Math.PI * maxRangeM * maxRangeM;
        double density = pointCount / coverageArea;
        return new PointCloudStats(pointCount, density, nearest);
    }

    /** 返回传感器索引对应的方向角（度）。索引超出已知方向时按 360°/sensorCount 等分推算。 */
    private double directionDeg(int index) {
        if (index < DIRECTIONS_DEG.length) {
            return DIRECTIONS_DEG[index];
        }
        return (360.0 / sensorCount) * index;
    }

    /** 返回 lastReadings 指定索引的读数；未测量时返回 FAR_DISTANCE_M。 */
    private double readingOrFar(int index) {
        if (lastReadings == null || index >= lastReadings.length) {
            return FAR_DISTANCE_M;
        }
        return lastReadings[index];
    }

    /** 暴露最近一次 measure 的读数副本（供测试/调试），未测量时返回 null。 */
    public double[] lastReadings() {
        return lastReadings == null ? null : lastReadings.clone();
    }
}