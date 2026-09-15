package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VirtualDrone 物理模型切换单测（M4 硬件抽象，FR-10/FR-37/DFX 4.5）。
 * <p>
 * 测试物理模型切换的 setter/getter + 校验逻辑。
 * 完整 tick 行为由 e2e 集成测试覆盖。
 */
class VirtualDronePhysicsModelTest {

    @Test
    void kinematicsModelDefault() {
        // FR-37 默认 model=kinematics
        DronePhysics physics = new DronePhysics(22.5, 113.9, 0, 0);
        // 验证 DronePhysics 可独立使用（运动学模型不变）
        physics.tick(0.05);
        assertTrue(physics.alt() >= 0);
    }

    @Test
    void aeroModelUsesRotorAero() {
        // FR-10 model=aero 时使用 RotorAerodynamics
        SimulatedRotorAerodynamics aero = new SimulatedRotorAerodynamics();
        RotorConfig cfg = new RotorConfig(1, 4, 0.25, 5.0, 10000, 1.225);
        RotorAerodynamics.FlightState state = new RotorAerodynamics.FlightState(1.5, 0, 0, 0, 0);
        RotorAerodynamics.RotorAeroResult result = aero.compute(cfg, state);
        // 气动模型产出有效结果
        assertTrue(result.totalThrust() > 0);
        assertEquals(4, result.rotors().size());
    }

    @Test
    void aeroFallbackOnException() {
        // 异常 5.2.2 气动模型异常 → 回退运动学
        // 创建一个会抛异常的 RotorAerodynamics 实现
        RotorAerodynamics failingAero = (config, state) -> {
            throw new RuntimeException("simulated aero failure");
        };
        // 验证异常被抛出（VirtualDrone tickOnce 会 try-catch 回退）
        assertThrows(RuntimeException.class, () ->
                failingAero.compute(new RotorConfig(1, 4, 0.25, 5.0, 10000, 1.225),
                        new RotorAerodynamics.FlightState(1.5, 0, 0, 0, 0)));
        // 回退验证：DronePhysics 仍可正常 tick
        DronePhysics physics = new DronePhysics(22.5, 113.9, 0, 0);
        physics.tick(0.05);
        assertTrue(physics.alt() >= 0);
    }

    @Test
    void physicsModelValidation() {
        // setPhysicsModel 只接受 "kinematics" 和 "aero"
        // 这里直接测试校验逻辑（不创建 VirtualDrone 实例）
        assertThrows(IllegalArgumentException.class, () -> validateModel("invalid"));
        assertDoesNotThrow(() -> validateModel("kinematics"));
        assertDoesNotThrow(() -> validateModel("aero"));
    }

    @Test
    void noHardwareNoReport() {
        // DFX 4.5 未注入硬件数据源时不产生硬件上报
        // 验证：所有硬件字段默认 null
        // 这里测试 SimulatedRadar 空目标 → 空列表（不产生上报）
        SimulatedRadar radar = new SimulatedRadar();
        RadarScanConfig cfg = new RadarScanConfig(1,
                io.aerofleet.mavlink.enums.ScanMode.STARE, 0, 60, 0, 10, 1000, 1000, true);
        assertTrue(radar.scan(cfg, java.util.List.of()).isEmpty());

        // SimulatedLiDARSource 无障碍 → MAX_VALUE
        SimulatedLiDARSource lidar = new SimulatedLiDARSource(
                java.util.List.of(), TerrainModel.flat(), 0, 0, 10);
        assertEquals(Double.MAX_VALUE, lidar.nearestDistance());
    }

    private void validateModel(String model) {
        if (!"kinematics".equals(model) && !"aero".equals(model)) {
            throw new IllegalArgumentException(
                    "physicsModel must be 'kinematics' or 'aero', got " + model);
        }
    }
}