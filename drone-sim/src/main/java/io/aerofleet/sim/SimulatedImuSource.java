package io.aerofleet.sim;

import java.util.Random;

/**
 * 模拟 IMU 数据源（M4 硬件抽象，FR-16）。
 * <p>
 * 基于 {@link DronePhysics} 的加速度/姿态 + 合成噪声（高斯白噪声 σ=0.01）/
 * 合成偏置（随机游走）产出 IMU 原始数据，模拟真实 IMU 传感器输出。
 * <p>
 * 悬停时 accelZ ≈ 9.81 + 噪声，gyro ≈ 0 + 偏置（FR-16 验收条件）。
 * 磁场基于地磁场 ~50 μT × cos/sin(yaw) 简化模型。
 * 温度由环境温度源提供（默认 25℃）。
 */
public class SimulatedImuSource implements ImuSource {

    private static final double GRAVITY = 9.81;
    private static final double NOISE_SIGMA = 0.01;       // 加速度噪声 σ
    private static final double GYRO_NOISE_SIGMA = 0.001;  // 陀螺仪噪声 σ
    private static final double BIAS_RW_SIGMA = 0.0001;    // 偏置随机游走 σ
    private static final double EARTH_FIELD_UT = 50.0;     // 地磁场 ~50 μT

    private final DronePhysics physics;
    private final Random rng;
    private final double sampleRateHz;
    private final double defaultTempC;

    // 偏置随机游走状态
    private double biasX = 0;
    private double biasY = 0;
    private double biasZ = 0;

    /**
     * @param physics      物理引擎（只读加速度/姿态）
     * @param seed         伪随机种子（确定性可复现）
     * @param sampleRateHz 采样频率（Hz）
     * @param defaultTempC 默认温度（℃），环境温度源未注入时使用
     */
    public SimulatedImuSource(DronePhysics physics, long seed, double sampleRateHz,
                              double defaultTempC) {
        this.physics = physics;
        this.rng = new Random(seed);
        this.sampleRateHz = sampleRateHz;
        this.defaultTempC = defaultTempC;
    }

    /** 便利构造：默认 50Hz 采样 + 25℃ 温度。 */
    public SimulatedImuSource(DronePhysics physics, long seed) {
        this(physics, seed, 50.0, 25.0);
    }

    @Override
    public ImuSample sample() {
        // 从 DronePhysics 获取加速度/姿态
        // 悬停时加速度 ≈ 0（水平），但 IMU 测量的是比力（含重力），所以 accelZ ≈ G
        double physicsAccelX = 0;  // DronePhysics 不直接暴露加速度，用 0 近似
        double physicsAccelY = 0;
        double physicsAccelZ = GRAVITY;  // 悬停时比力 = 重力

        // 从姿态推导加速度（飞行中倾斜会改变比力方向）
        double roll = physics.rollRad();
        double pitch = physics.pitchRad();
        // 比力在体坐标系：a_body = R^T × [0, 0, g]
        double ax = -GRAVITY * Math.sin(pitch);
        double ay = GRAVITY * Math.sin(roll) * Math.cos(pitch);
        double az = GRAVITY * Math.cos(roll) * Math.cos(pitch);

        // 加高斯白噪声 + 偏置
        double accelX = ax + gaussian(NOISE_SIGMA) + biasX;
        double accelY = ay + gaussian(NOISE_SIGMA) + biasY;
        double accelZ = az + gaussian(NOISE_SIGMA) + biasZ;

        // 角速度：从姿态变化率近似（简化：用 0 + 噪声 + 偏置）
        double gyroX = gaussian(GYRO_NOISE_SIGMA) + biasX * 0.1;
        double gyroY = gaussian(GYRO_NOISE_SIGMA) + biasY * 0.1;
        double gyroZ = gaussian(GYRO_NOISE_SIGMA) + biasZ * 0.1;

        // 磁场（地磁场 ~50 μT，基于航向旋转）
        double yaw = physics.yawRad();
        double magX = EARTH_FIELD_UT * Math.cos(yaw);
        double magY = EARTH_FIELD_UT * Math.sin(yaw);
        double magZ = 0;

        double tempC = defaultTempC;

        // 偏置随机游走（FR-16）
        biasX += gaussian(BIAS_RW_SIGMA);
        biasY += gaussian(BIAS_RW_SIGMA);
        biasZ += gaussian(BIAS_RW_SIGMA);

        return new ImuSample(accelX, accelY, accelZ, gyroX, gyroY, gyroZ,
                magX, magY, magZ, tempC, System.currentTimeMillis());
    }

    @Override
    public double sampleRate() {
        return sampleRateHz;
    }

    /** 高斯白噪声（Box-Muller 变换）。 */
    private double gaussian(double sigma) {
        return rng.nextGaussian() * sigma;
    }

    /** 当前偏置（供测试验证随机游走）。 */
    public double biasX() { return biasX; }
    public double biasY() { return biasY; }
    public double biasZ() { return biasZ; }
}