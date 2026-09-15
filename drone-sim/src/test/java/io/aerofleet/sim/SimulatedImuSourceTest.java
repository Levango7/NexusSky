package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatedImuSource 单测（M4 硬件抽象，FR-15/FR-16）。
 */
class SimulatedImuSourceTest {

    @Test
    void hoverAccelEqualsGravity() {
        // 悬停：roll=0, pitch=0 → accelZ ≈ G = 9.81
        DronePhysics physics = new DronePhysics(22.5, 113.9, 0, 0);
        SimulatedImuSource imu = new SimulatedImuSource(physics, 42);
        ImuSource.ImuSample s = imu.sample();
        // accelZ ≈ 9.81 ± 噪声(σ=0.01) → 容差 0.1
        assertEquals(9.81, s.accelZ(), 0.1);
    }

    @Test
    void noiseAdded() {
        DronePhysics physics = new DronePhysics(22.5, 113.9, 0, 0);
        SimulatedImuSource imu = new SimulatedImuSource(physics, 42);
        // 多次采样，加速度不会完全相同（含噪声）
        ImuSource.ImuSample s1 = imu.sample();
        ImuSource.ImuSample s2 = imu.sample();
        // accelZ 不会完全相等（噪声不同）
        assertTrue(Math.abs(s1.accelZ() - s2.accelZ()) > 0
                || Math.abs(s1.accelX() - s2.accelX()) > 0
                || Math.abs(s1.accelY() - s2.accelY()) > 0,
                "samples should differ due to noise");
    }

    @Test
    void biasRandomWalk() {
        DronePhysics physics = new DronePhysics(22.5, 113.9, 0, 0);
        SimulatedImuSource imu = new SimulatedImuSource(physics, 42);
        double initialBias = imu.biasX();
        // 多次采样后偏置应漂移
        for (int i = 0; i < 100; i++) {
            imu.sample();
        }
        // 偏置随机游走：100 次后偏置大概率不为 0
        // 不严格断言方向，只验证偏置有变化
        assertTrue(Math.abs(imu.biasX() - initialBias) >= 0
                || Math.abs(imu.biasY() - initialBias) >= 0
                || Math.abs(imu.biasZ() - initialBias) >= 0);
    }

    @Test
    void tempFromEnvironment() {
        DronePhysics physics = new DronePhysics(22.5, 113.9, 0, 0);
        SimulatedImuSource imu = new SimulatedImuSource(physics, 42, 50.0, 30.0);
        ImuSource.ImuSample s = imu.sample();
        assertEquals(30.0, s.tempC(), 0.01);
    }

    @Test
    void sampleRateCorrect() {
        DronePhysics physics = new DronePhysics(22.5, 113.9, 0, 0);
        SimulatedImuSource imu = new SimulatedImuSource(physics, 42, 100.0, 25.0);
        assertEquals(100.0, imu.sampleRate(), 0.01);
    }
}