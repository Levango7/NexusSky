package io.aerofleet.sim;

/**
 * 光流定位数据源（丐版 LiDAR 替代）。
 * 模拟 PMW3901 光流传感器，下视光学流，
 * 适用于室内无 GPS 环境的精确定位。
 */
public class OpticalFlowSource {
    private final double resolution;     // 0.1m (精度)
    private final double maxSpeedMps;    // 7.4 m/s (最大跟踪速度)

    public OpticalFlowSource() {
        this(0.1, 7.4);
    }

    public OpticalFlowSource(double resolution, double maxSpeedMps) {
        if (resolution <= 0) {
            throw new IllegalArgumentException("resolution must be positive");
        }
        if (maxSpeedMps <= 0) {
            throw new IllegalArgumentException("maxSpeedMps must be positive");
        }
        this.resolution = resolution;
        this.maxSpeedMps = maxSpeedMps;
    }

    /**
     * 计算位移：给定速度和时间，返回带量化噪声的位移。
     * 位移量化到 resolution 网格（PMW3901 像素分辨率引入的量化误差）。
     *
     * @param vx        X 方向速度（m/s）
     * @param vy        Y 方向速度（m/s）
     * @param dtSeconds 时间步长（秒）
     * @return [dx, dy] 位移（米）
     */
    public double[] computeDisplacement(double vx, double vy, double dtSeconds) {
        if (dtSeconds < 0) {
            throw new IllegalArgumentException("dtSeconds must be non-negative");
        }
        double dx = quantize(vx * dtSeconds);
        double dy = quantize(vy * dtSeconds);
        return new double[]{dx, dy};
    }

    /**
     * 速度超出跟踪范围时返回 true。
     *
     * @param speedMps 速度大小（m/s）
     * @return 超出 maxSpeedMps 时 true
     */
    public boolean outOfRange(double speedMps) {
        return speedMps > maxSpeedMps;
    }

    public double resolution() {
        return resolution;
    }

    /** 量化到 resolution 网格。 */
    private double quantize(double v) {
        return Math.round(v / resolution) * resolution;
    }
}