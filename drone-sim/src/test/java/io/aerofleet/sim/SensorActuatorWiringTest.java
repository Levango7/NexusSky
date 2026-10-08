package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 传感器装配语义的守卫（FR-21 LiDAR / FR-22 IMU）。
 *
 * <p><b>缺口性质</b>：与夹爪未 enable 同类——{@code SimulatedLiDARSource} 与
 * {@code SimulatedImuSource} 早已实现且各有独立单测，但 {@code VirtualDrone}
 * 从未构造注入它们，{@code SimConfig} 也没有对应开关。于是 drone-sim 无论怎么启动，
 * cloud-backend 的 {@code /api/v1/lidar/data} 与 {@code /api/v1/imu/data} 都恒 404：
 * 演示时这两路传感器没有任何数据，而 404 符合端点契约，**没有任何信号会变红**。
 *
 * <p>本类钉住"开关缺省关、开关打开即注入"，并区分"未装配"与"装配了但没数据"——
 * 前者应当是显式配置选择，后者才是缺陷。
 */
@DisplayName("M4 传感器装配：--lidar / --imu 缺省关，打开即注入模拟源")
class SensorActuatorWiringTest {

    @Test
    @DisplayName("缺省：两个开关都关（保持既有行为，未显式装配即不产生载荷数据）")
    void sensorsOffByDefault() {
        SimConfig cfg = SimConfig.parse(new String[]{"--port=24795", "--failsafe=off"});
        assertThat(cfg.lidarEnabled).as("LiDAR 缺省不装配").isFalse();
        assertThat(cfg.imuEnabled).as("IMU 缺省不装配").isFalse();
    }

    @Test
    @DisplayName("打开：--lidar / --imu 被解析为 true")
    void flagsEnableSensors() {
        SimConfig cfg = SimConfig.parse(new String[]{
                "--port=24796", "--failsafe=off", "--lidar", "--imu"});
        assertThat(cfg.lidarEnabled).as("--lidar 应生效").isTrue();
        assertThat(cfg.imuEnabled).as("--imu 应生效").isTrue();
    }

    @Test
    @DisplayName("布尔开关裸写即生效且不吞下一个 token（BOOLEAN_FLAGS 契约）")
    void bareFlagsDoNotConsumeNextToken() {
        // 这是 BOOLEAN_FLAGS 存在的原因：裸写开关若吞掉后一个 token，
        // "--lidar --imu" 会变成 lidar 吃掉 imu，imu 永远装配不上——
        // 且表现为"偶尔不生效"，极难排查。
        SimConfig cfg = SimConfig.parse(new String[]{
                "--port=24797", "--failsafe=off", "--lidar", "--imu", "--actuators"});
        assertThat(cfg.lidarEnabled).isTrue();
        assertThat(cfg.imuEnabled)
                .as("--imu 不应被 --lidar 吞掉")
                .isTrue();
        assertThat(cfg.actuatorsEnabled)
                .as("--actuators 不应被 --imu 吞掉")
                .isTrue();
    }

    @Test
    @DisplayName("单开一个也互不干扰")
    void flagsAreIndependent() {
        SimConfig onlyLidar = SimConfig.parse(new String[]{
                "--port=24798", "--failsafe=off", "--lidar"});
        assertThat(onlyLidar.lidarEnabled).isTrue();
        assertThat(onlyLidar.imuEnabled).as("只开 lidar 不应顺带开 imu").isFalse();

        SimConfig onlyImu = SimConfig.parse(new String[]{
                "--port=24799", "--failsafe=off", "--imu"});
        assertThat(onlyImu.imuEnabled).isTrue();
        assertThat(onlyImu.lidarEnabled).as("只开 imu 不应顺带开 lidar").isFalse();
    }

    @Test
    @DisplayName("--imu-seed 解析为 long，且默认 42")
    void imuSeedParses() {
        SimConfig def = SimConfig.parse(new String[]{"--port=24800", "--failsafe=off"});
        assertThat(def.imuSeed).as("默认种子应稳定，便于复现").isEqualTo(42L);

        SimConfig seeded = SimConfig.parse(new String[]{
                "--port=24801", "--failsafe=off", "--imu", "--imu-seed", "7"});
        assertThat(seeded.imuSeed).isEqualTo(7L);
    }

    @Test
    @DisplayName("SimulatedLiDARSource 在合成障碍场下产出非空点云（注入后数据非零值占位）")
    void simulatedLidarProducesRealData() {
        SimulatedLiDARSource lidar = new SimulatedLiDARSource(
                java.util.List.of(new SyntheticObstacle(30, 0, 2.0)),
                TerrainModel.flat(), 0, 0, 10.0);
        assertThat(lidar.pointCloud())
                .as("有障碍物时点云不应为空 —— 否则接上线路径后端点也只是零值占位")
                .isNotEmpty();
        assertThat(lidar.nearestDistance())
                .as("最近距离应为有限正数")
                .isGreaterThan(0.0)
                .isLessThan(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("无障碍物时最近距离为 MAX_VALUE（与有障碍物可区分）")
    void emptyLidarReportsMaxDistance() {
        SimulatedLiDARSource lidar = new SimulatedLiDARSource(
                java.util.List.of(), TerrainModel.flat(), 0, 0, 10.0);
        assertThat(lidar.nearestDistance())
                .as("空场景须与有障碍物可区分，否则'接上了'也看不出差别")
                .isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("SimulatedImuSource 产出含重力的加速度（水平悬停时 Z≈9.8）")
    void simulatedImuIncludesGravity() {
        // 水平悬停：roll=0, pitch=0 → accelZ ≈ G（与 SimulatedImuSourceTest 同口径）
        DronePhysics physics = new DronePhysics(22.5, 113.9, 0, 0);
        SimulatedImuSource imu = new SimulatedImuSource(physics, 42L);
        ImuSource.ImuSample s = imu.sample();
        double mag = Math.sqrt(s.accelX() * s.accelX()
                + s.accelY() * s.accelY()
                + s.accelZ() * s.accelZ());
        assertThat(mag)
                .as("比力应含重力，量级≈9.8；全零说明只是占位实现")
                .isGreaterThan(5.0)
                .isLessThan(15.0);
    }
}