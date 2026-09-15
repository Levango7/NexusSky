package io.aerofleet.sim;

/**
 * IMU 数据源抽象接口（M4 硬件抽象，FR-15）。
 * <p>
 * 输出加速度三轴/角速度三轴/磁场三轴/温度。
 * {@link SimulatedImuSource} 为模拟实现（基于 DronePhysics + 合成噪声/偏置）。
 */
public interface ImuSource {

    /** 采样一次 IMU 数据（FR-15/FR-16）。 */
    ImuSample sample();

    /** 采样频率（Hz，FR-15）。 */
    double sampleRate();

    /** IMU 采样数据（数据约束 6.6）。 */
    record ImuSample(double accelX, double accelY, double accelZ,
                     double gyroX, double gyroY, double gyroZ,
                     double magX, double magY, double magZ,
                     double tempC, long timestamp) {
    }
}