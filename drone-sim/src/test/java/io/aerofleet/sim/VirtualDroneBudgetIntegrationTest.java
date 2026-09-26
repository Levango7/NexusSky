package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.ThreatLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VirtualDrone 丐版模式（budget）深度集成测试。
 * <p>
 * 验证 toy/standard/advanced 三档 budget 模式下 VirtualDrone 自动装配丐版传感器
 * （UltrasonicSource / BudgetThermalSource / OpticalFlowSource），
 * 以及 advanced/null 模式下既有行为完全不变（DFX 4.5 向下兼容）。
 * <p>
 * 每个测试用独立 UDP 端口避免冲突，通过 try-with-resources 确保 VirtualDrone 关闭释放资源。
 */
class VirtualDroneBudgetIntegrationTest {

    /** 基础端口（高端口避免与默认 14540 或其他测试冲突）。 */
    private static final int BASE_PORT = 24590;

    /**
     * 构造指定 budget 模式 + 端口的 SimConfig。
     * budgetMode=null 时不添加 --budget 参数（完整版，既有行为不变）。
     */
    private SimConfig configWithBudget(String budgetMode, int port) {
        if (budgetMode == null) {
            return SimConfig.parse(new String[]{
                    "--port=" + port, "--failsafe=off"
            });
        }
        return SimConfig.parse(new String[]{
                "--budget=" + budgetMode, "--port=" + port, "--failsafe=off"
        });
    }

    // ===== toy 模式（百元级）=====

    @Test
    @DisplayName("toy 模式：自动装配 UltrasonicSource 作为 DepthSource")
    void toyMode_assemblesUltrasonicAsDepthSource() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("toy", BASE_PORT))) {
            assertEquals(BudgetMode.TOY, drone.getBudgetMode());
            assertNotNull(drone.getUltrasonicSource(),
                    "toy 模式应自动实例化 UltrasonicSource");
            assertNotNull(drone.getDepthSource(),
                    "toy 模式应自动装配 DepthSource（超声波）");
            assertInstanceOf(UltrasonicSource.class, drone.getDepthSource(),
                    "DepthSource 应为 UltrasonicSource 实例");
            assertSame(drone.getUltrasonicSource(), drone.getDepthSource(),
                    "depthSource 应与 ultrasonicSource 是同一实例");
        }
    }

    @Test
    @DisplayName("toy 模式：自动装配 ObstacleDetector 使用超声波避障")
    void toyMode_assemblesObstacleDetectorWithUltrasonic() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("toy", BASE_PORT + 1))) {
            assertNotNull(drone.getObstacleDetector(),
                    "toy 模式应自动装配 ObstacleDetector");
            // 验证 ObstacleDetector 的阈值（safety=2.0, emergency=0.5）
            assertEquals(2.0, drone.getObstacleDetector().safetyDistanceM(), 0.001);
            assertEquals(0.5, drone.getObstacleDetector().emergencyHoverM(), 0.001);
        }
    }

    @Test
    @DisplayName("toy 模式：自动装配 BudgetThermalSource 替代高端热成像")
    void toyMode_assemblesBudgetThermalSource() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("toy", BASE_PORT + 2))) {
            assertNotNull(drone.getBudgetThermalSource(),
                    "toy 模式应自动实例化 BudgetThermalSource");
            assertNotNull(drone.getThermalSource(),
                    "toy 模式应自动装配 ThermalSource（红外阵列）");
            assertInstanceOf(BudgetThermalSource.class, drone.getThermalSource(),
                    "ThermalSource 应为 BudgetThermalSource 实例");
            assertSame(drone.getBudgetThermalSource(), drone.getThermalSource(),
                    "thermalSource 应与 budgetThermalSource 是同一实例");
        }
    }

    @Test
    @DisplayName("toy 模式：自动装配 OpticalFlowSource 辅助定位")
    void toyMode_assemblesOpticalFlowSource() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("toy", BASE_PORT + 3))) {
            assertNotNull(drone.getOpticalFlowSource(),
                    "toy 模式应自动实例化 OpticalFlowSource（无 GPS 环境定位）");
        }
    }

    @Test
    @DisplayName("toy 模式：setDepthSource 被忽略（超声波强制使用）")
    void toyMode_setDepthSource_ignored() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("toy", BASE_PORT + 4))) {
            UltrasonicSource original = drone.getUltrasonicSource();
            // 尝试注入一个不同的 DepthSource（应被忽略）
            UltrasonicSource another = new UltrasonicSource(6.0, 4);
            drone.setDepthSource(another);
            assertSame(original, drone.getDepthSource(),
                    "toy 模式下 setDepthSource 应被忽略，depthSource 不变");
        }
    }

    @Test
    @DisplayName("toy 模式：setObstacleDetector 被忽略（超声波检测器强制使用）")
    void toyMode_setObstacleDetector_ignored() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("toy", BASE_PORT + 5))) {
            ObstacleDetector original = drone.getObstacleDetector();
            // 尝试注入一个不同的 ObstacleDetector（应被忽略）
            ObstacleDetector another = new ObstacleDetector(new UltrasonicSource(), 3.0, 1.0);
            drone.setObstacleDetector(another);
            assertSame(original, drone.getObstacleDetector(),
                    "toy 模式下 setObstacleDetector 应被忽略，obstacleDetector 不变");
        }
    }

    // ===== standard 模式（千元级）=====

    @Test
    @DisplayName("standard 模式：自动装配 UltrasonicSource 作为 DepthSource（双冗余避障）")
    void standardMode_assemblesUltrasonicAsDepthSource() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("standard", BASE_PORT + 10))) {
            assertEquals(BudgetMode.STANDARD, drone.getBudgetMode());
            assertNotNull(drone.getUltrasonicSource(),
                    "standard 模式应自动实例化 UltrasonicSource");
            assertNotNull(drone.getDepthSource(),
                    "standard 模式应自动装配 DepthSource（超声波）");
            assertInstanceOf(UltrasonicSource.class, drone.getDepthSource(),
                    "DepthSource 应为 UltrasonicSource 实例");
        }
    }

    @Test
    @DisplayName("standard 模式：自动装配 ObstacleDetector 使用超声波避障")
    void standardMode_assemblesObstacleDetectorWithUltrasonic() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("standard", BASE_PORT + 11))) {
            assertNotNull(drone.getObstacleDetector(),
                    "standard 模式应自动装配 ObstacleDetector");
            assertEquals(2.0, drone.getObstacleDetector().safetyDistanceM(), 0.001);
            assertEquals(0.5, drone.getObstacleDetector().emergencyHoverM(), 0.001);
        }
    }

    @Test
    @DisplayName("standard 模式：自动装配 BudgetThermalSource 替代高端热成像")
    void standardMode_assemblesBudgetThermalSource() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("standard", BASE_PORT + 12))) {
            assertNotNull(drone.getBudgetThermalSource(),
                    "standard 模式应自动实例化 BudgetThermalSource");
            assertNotNull(drone.getThermalSource(),
                    "standard 模式应自动装配 ThermalSource（红外阵列）");
            assertInstanceOf(BudgetThermalSource.class, drone.getThermalSource(),
                    "ThermalSource 应为 BudgetThermalSource 实例");
        }
    }

    @Test
    @DisplayName("standard 模式：不实例化 OpticalFlowSource（保留 GPS 定位）")
    void standardMode_noOpticalFlowSource() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("standard", BASE_PORT + 13))) {
            assertNull(drone.getOpticalFlowSource(),
                    "standard 模式保留 GPS 定位，不需要 OpticalFlowSource");
        }
    }

    @Test
    @DisplayName("standard 模式：setDepthSource 允许注入高端 DepthSource 作为补充（双冗余）")
    void standardMode_setDepthSource_allowed() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("standard", BASE_PORT + 14))) {
            // standard 模式允许注入高端 DepthSource 作为补充
            UltrasonicSource supplementary = new UltrasonicSource(6.0, 4);
            drone.setDepthSource(supplementary);
            assertSame(supplementary, drone.getDepthSource(),
                    "standard 模式下 setDepthSource 应允许注入（双冗余补充）");
            // ObstacleDetector 仍使用超声波（不被 setDepthSource 影响）
            assertNotNull(drone.getObstacleDetector());
        }
    }

    // ===== advanced 模式（既有行为不变）=====

    @Test
    @DisplayName("advanced 模式：不实例化任何丐版传感器")
    void advancedMode_noBudgetSensors() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("advanced", BASE_PORT + 20))) {
            assertEquals(BudgetMode.ADVANCED, drone.getBudgetMode());
            assertNull(drone.getUltrasonicSource(),
                    "advanced 模式不实例化 UltrasonicSource");
            assertNull(drone.getBudgetThermalSource(),
                    "advanced 模式不实例化 BudgetThermalSource");
            assertNull(drone.getOpticalFlowSource(),
                    "advanced 模式不实例化 OpticalFlowSource");
        }
    }

    @Test
    @DisplayName("advanced 模式：depthSource/obstacleDetector/thermalSource 默认 null（行为不变）")
    void advancedMode_sensorsDefaultNull() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("advanced", BASE_PORT + 21))) {
            assertNull(drone.getDepthSource(),
                    "advanced 模式 depthSource 默认 null（等待外部注入高端传感器）");
            assertNull(drone.getObstacleDetector(),
                    "advanced 模式 obstacleDetector 默认 null");
            assertNull(drone.getThermalSource(),
                    "advanced 模式 thermalSource 默认 null");
        }
    }

    @Test
    @DisplayName("advanced 模式：setDepthSource/setObstacleDetector 正常工作")
    void advancedMode_settersWorkNormally() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("advanced", BASE_PORT + 22))) {
            UltrasonicSource src = new UltrasonicSource();
            ObstacleDetector detector = new ObstacleDetector(src, 3.0, 1.0);
            drone.setDepthSource(src);
            drone.setObstacleDetector(detector);
            assertSame(src, drone.getDepthSource(),
                    "advanced 模式 setDepthSource 应正常工作");
            assertSame(detector, drone.getObstacleDetector(),
                    "advanced 模式 setObstacleDetector 应正常工作");
        }
    }

    // ===== null 模式（完整版，既有行为不变）=====

    @Test
    @DisplayName("null 模式（完整版）：不实例化任何丐版传感器")
    void nullMode_noBudgetSensors() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget(null, BASE_PORT + 30))) {
            assertNull(drone.getBudgetMode(), "null 模式 budgetMode 为 null");
            assertNull(drone.getUltrasonicSource());
            assertNull(drone.getBudgetThermalSource());
            assertNull(drone.getOpticalFlowSource());
            assertNull(drone.getDepthSource());
            assertNull(drone.getObstacleDetector());
            assertNull(drone.getThermalSource());
        }
    }

    @Test
    @DisplayName("null 模式（完整版）：setter 正常工作（既有行为不变）")
    void nullMode_settersWorkNormally() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget(null, BASE_PORT + 31))) {
            UltrasonicSource src = new UltrasonicSource();
            drone.setDepthSource(src);
            assertSame(src, drone.getDepthSource(),
                    "null 模式 setDepthSource 应正常工作");
        }
    }

    // ===== 丐版传感器在模拟循环中正常工作 =====

    @Test
    @DisplayName("toy 模式：超声波 + ObstacleDetector 避障检测正常工作")
    void toyMode_ultrasonicObstacleDetection_works() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("toy", BASE_PORT + 40))) {
            UltrasonicSource ultrasonic = drone.getUltrasonicSource();
            ObstacleDetector detector = drone.getObstacleDetector();
            assertNotNull(ultrasonic);
            assertNotNull(detector);

            // 前方 0.3m 有障碍 → CRITICAL（safety=2.0, emergency=0.5）
            ultrasonic.measure(new double[]{0.3, 10.0, 10.0, 10.0});
            ObstacleDetector.ObstacleReport report = detector.detect();
            assertEquals(ThreatLevel.CRITICAL, report.threat(),
                    "0.3m 障碍应为 CRITICAL");
            assertEquals(0.3, report.distance(), 0.01,
                    "距离应约等于 0.3m");

            // 无障碍 → NONE
            ultrasonic.measure(new double[]{10.0, 10.0, 10.0, 10.0});
            ObstacleDetector.ObstacleReport clearReport = detector.detect();
            assertEquals(ThreatLevel.NONE, clearReport.threat(),
                    "无障碍应为 NONE");
        }
    }

    @Test
    @DisplayName("toy 模式：BudgetThermalSource 热成像分析正常工作")
    void toyMode_budgetThermalAnalysis_works() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("toy", BASE_PORT + 41))) {
            BudgetThermalSource thermal = drone.getBudgetThermalSource();
            assertNotNull(thermal);

            // 生成温度帧并分析（AMG8833 8×8）
            double[][] heatSources = {{4.0, 4.0, 60.0, 2.0}};
            double[][] frame = thermal.generateFrame(25.0, heatSources);
            assertEquals(BudgetThermalSource.HEIGHT, frame.length, "帧高度应为 8");
            assertEquals(BudgetThermalSource.WIDTH, frame[0].length, "帧宽度应为 8");

            ThermalSource.ThermalResult result = thermal.analyze(frame);
            assertTrue(result.max() > 25.0, "有热源时最高温度应高于环境温度 25°C");
            assertTrue(result.mean() > 25.0, "有热源时均值应高于环境温度 25°C");
        }
    }

    @Test
    @DisplayName("toy 模式：OpticalFlowSource 光流定位正常工作")
    void toyMode_opticalFlowPositioning_works() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("toy", BASE_PORT + 42))) {
            OpticalFlowSource opticalFlow = drone.getOpticalFlowSource();
            assertNotNull(opticalFlow);

            // 计算位移：1 m/s × 1 s = 1m（量化到 0.1 网格）
            double[] disp = opticalFlow.computeDisplacement(1.0, 0.0, 1.0);
            assertEquals(1.0, disp[0], 0.001, "dx 应为 1.0m");
            assertEquals(0.0, disp[1], 0.001, "dy 应为 0.0m");

            // 速度范围检查
            assertFalse(opticalFlow.outOfRange(5.0), "5.0 m/s 在范围内");
            assertTrue(opticalFlow.outOfRange(10.0), "10.0 m/s 超出范围");
        }
    }

    @Test
    @DisplayName("standard 模式：超声波 + ObstacleDetector 避障检测正常工作")
    void standardMode_ultrasonicObstacleDetection_works() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("standard", BASE_PORT + 50))) {
            UltrasonicSource ultrasonic = drone.getUltrasonicSource();
            ObstacleDetector detector = drone.getObstacleDetector();
            assertNotNull(ultrasonic);
            assertNotNull(detector);

            // 右侧 1.0m 有障碍 → HIGH（safety=2.0, emergency=0.5, 1.0 ∈ [0.5, 2.0)）
            ultrasonic.measure(new double[]{10.0, 10.0, 10.0, 1.0});
            ObstacleDetector.ObstacleReport report = detector.detect();
            assertEquals(ThreatLevel.HIGH, report.threat(),
                    "1.0m 障碍应为 HIGH");
            assertEquals(270, report.directionDeg(),
                    "右侧障碍方向应为 270°");
        }
    }

    @Test
    @DisplayName("standard 模式：BudgetThermalSource 热点检测正常工作")
    void standardMode_budgetThermalHotspot_works() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("standard", BASE_PORT + 51))) {
            BudgetThermalSource thermal = drone.getBudgetThermalSource();
            assertNotNull(thermal);

            // 3×3 矩阵：中心 80°C 为局部极大值
            double[][] matrix = {{20, 20, 20}, {20, 80, 20}, {20, 20, 20}};
            var hotspots = thermal.detectHotspots(matrix, 50.0);
            assertEquals(1, hotspots.size(), "应检测到 1 个热点");
            assertEquals(80.0, hotspots.get(0).tempC(), 0.001, "热点温度应为 80°C");
        }
    }

    @Test
    @DisplayName("toy 模式：DepthSource 接口方法（depthMap/nearestObstacle/pointCloudStats）正常工作")
    void toyMode_depthSourceInterfaceMethods_work() throws Exception {
        try (VirtualDrone drone = new VirtualDrone(configWithBudget("toy", BASE_PORT + 60))) {
            DepthSource depthSource = drone.getDepthSource();
            assertNotNull(depthSource);

            // 先 measure（UltrasonicSource 需要先测量才能提供接口数据）
            UltrasonicSource ultrasonic = drone.getUltrasonicSource();
            ultrasonic.measure(new double[]{0.5, 4.0, 4.0, 4.0});

            // depthMap
            double[][] map = depthSource.depthMap();
            assertNotNull(map);
            assertEquals(4, map.length);
            assertEquals(4, map[0].length);

            // nearestObstacle
            DepthSource.NearestObstacle nearest = depthSource.nearestObstacle();
            assertEquals(0.5, nearest.distance(), 0.01, "最近障碍距离应约等于 0.5m");

            // pointCloudStats
            DepthSource.PointCloudStats stats = depthSource.pointCloudStats();
            assertTrue(stats.pointCount() > 0, "有障碍时点数应 > 0");
        }
    }
}