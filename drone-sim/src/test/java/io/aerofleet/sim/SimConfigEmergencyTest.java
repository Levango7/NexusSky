package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SimConfig 灾害应急丐版模式（--budget=emergency-toy / emergency-standard）单测。
 * <p>
 * 验证灾害应急模式的参数解析、EmergencyBudgetConfig 配置内容、
 * 传感器降级映射、以及与既有模式的向下兼容性。
 */
class SimConfigEmergencyTest {

    // ===== BudgetMode 枚举测试 =====

    @Nested
    @DisplayName("BudgetMode 枚举")
    class BudgetModeEnumTest {

        @Test
        @DisplayName("fromCliValue 解析所有有效 CLI 值")
        void fromCliValue_allValidModes() {
            assertEquals(BudgetMode.TOY, BudgetMode.fromCliValue("toy"));
            assertEquals(BudgetMode.STANDARD, BudgetMode.fromCliValue("standard"));
            assertEquals(BudgetMode.ADVANCED, BudgetMode.fromCliValue("advanced"));
            assertEquals(BudgetMode.EMERGENCY_TOY, BudgetMode.fromCliValue("emergency-toy"));
            assertEquals(BudgetMode.EMERGENCY_STANDARD, BudgetMode.fromCliValue("emergency-standard"));
        }

        @Test
        @DisplayName("fromCliValue 无效值返回 null")
        void fromCliValue_invalidReturnsNull() {
            assertNull(BudgetMode.fromCliValue("invalid"));
            assertNull(BudgetMode.fromCliValue(""));
            assertNull(BudgetMode.fromCliValue(null));
        }

        @Test
        @DisplayName("isEmergency 灾害应急模式返回 true，非应急模式返回 false")
        void isEmergency_correctClassification() {
            assertFalse(BudgetMode.TOY.isEmergency());
            assertFalse(BudgetMode.STANDARD.isEmergency());
            assertFalse(BudgetMode.ADVANCED.isEmergency());
            assertTrue(BudgetMode.EMERGENCY_TOY.isEmergency());
            assertTrue(BudgetMode.EMERGENCY_STANDARD.isEmergency());
        }

        @Test
        @DisplayName("cliValue 返回正确的 CLI 字符串")
        void cliValue_correctStrings() {
            assertEquals("toy", BudgetMode.TOY.cliValue());
            assertEquals("standard", BudgetMode.STANDARD.cliValue());
            assertEquals("advanced", BudgetMode.ADVANCED.cliValue());
            assertEquals("emergency-toy", BudgetMode.EMERGENCY_TOY.cliValue());
            assertEquals("emergency-standard", BudgetMode.EMERGENCY_STANDARD.cliValue());
        }
    }

    // ===== Emergency-Toy 模式解析测试 =====

    @Nested
    @DisplayName("Emergency-Toy 模式（百元级灾害应急 ~74元）")
    class EmergencyToyTest {

        @Test
        @DisplayName("--budget=emergency-toy 解析为 BudgetMode.EMERGENCY_TOY")
        void emergencyToy_parsedCorrectly() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            assertEquals(BudgetMode.EMERGENCY_TOY, cfg.budgetMode);
        }

        @Test
        @DisplayName("--budget emergency-toy（空格形式）也正确解析")
        void emergencyToy_spaceForm() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget", "emergency-toy"});
            assertEquals(BudgetMode.EMERGENCY_TOY, cfg.budgetMode);
        }

        @Test
        @DisplayName("emergency-toy 模式下 emergencyBudgetConfig 非 null")
        void emergencyToy_configNotNull() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            assertNotNull(cfg.emergencyBudgetConfig);
        }

        @Test
        @DisplayName("emergency-toy 通信类型为 WiFi ESP-NOW")
        void emergencyToy_commType() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            assertEquals(EmergencyBudgetConfig.CommType.WIFI_ESP_NOW,
                    cfg.emergencyBudgetConfig.commType);
        }

        @Test
        @DisplayName("emergency-toy 避障传感器为 HC-SR04 超声波，量程 2m")
        void emergencyToy_obstacleSensor() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            assertEquals(EmergencyBudgetConfig.ObstacleSensorType.HC_SR04_ULTRASONIC,
                    cfg.emergencyBudgetConfig.obstacleSensorType);
            assertEquals(2.0, cfg.emergencyBudgetConfig.obstacleRangeM, 0.001);
        }

        @Test
        @DisplayName("emergency-toy 无 GPS（GpsType.NONE，精度 0）")
        void emergencyToy_noGps() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            assertEquals(EmergencyBudgetConfig.GpsType.NONE,
                    cfg.emergencyBudgetConfig.gpsType);
            assertEquals(0.0, cfg.emergencyBudgetConfig.gpsAccuracyM, 0.001);
        }

        @Test
        @DisplayName("emergency-toy 无热成像（ThermalType.NONE）")
        void emergencyToy_noThermal() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            assertEquals(EmergencyBudgetConfig.ThermalType.NONE,
                    cfg.emergencyBudgetConfig.thermalType);
        }

        @Test
        @DisplayName("emergency-toy 摄像头为 ESP32-CAM")
        void emergencyToy_cameraType() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            assertEquals(EmergencyBudgetConfig.CameraType.ESP32_CAM,
                    cfg.emergencyBudgetConfig.cameraType);
        }

        @Test
        @DisplayName("emergency-toy MAX_HOPS=5，最多 8 节点")
        void emergencyToy_meshParams() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            assertEquals(5, cfg.emergencyBudgetConfig.maxHops);
            assertEquals(8, cfg.emergencyBudgetConfig.maxNodes);
        }

        @Test
        @DisplayName("emergency-toy LED 搜救信号灯和蜂鸣器均启用")
        void emergencyToy_ledAndBuzzer() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            assertTrue(cfg.emergencyBudgetConfig.ledEnabled);
            assertTrue(cfg.emergencyBudgetConfig.buzzerEnabled);
        }

        @Test
        @DisplayName("emergency-toy 预估成本约 74 元")
        void emergencyToy_estimatedCost() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            assertEquals(74.0, cfg.emergencyBudgetConfig.estimatedCostYuan, 0.1);
        }
    }

    // ===== Emergency-Standard 模式解析测试 =====

    @Nested
    @DisplayName("Emergency-Standard 模式（千元级灾害应急 ~429元）")
    class EmergencyStandardTest {

        @Test
        @DisplayName("--budget=emergency-standard 解析为 BudgetMode.EMERGENCY_STANDARD")
        void emergencyStandard_parsedCorrectly() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            assertEquals(BudgetMode.EMERGENCY_STANDARD, cfg.budgetMode);
        }

        @Test
        @DisplayName("--budget emergency-standard（空格形式）也正确解析")
        void emergencyStandard_spaceForm() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget", "emergency-standard"});
            assertEquals(BudgetMode.EMERGENCY_STANDARD, cfg.budgetMode);
        }

        @Test
        @DisplayName("emergency-standard 模式下 emergencyBudgetConfig 非 null")
        void emergencyStandard_configNotNull() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            assertNotNull(cfg.emergencyBudgetConfig);
        }

        @Test
        @DisplayName("emergency-standard 通信类型为 LoRa Mesh")
        void emergencyStandard_commType() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            assertEquals(EmergencyBudgetConfig.CommType.LORA_MESH,
                    cfg.emergencyBudgetConfig.commType);
        }

        @Test
        @DisplayName("emergency-standard 避障传感器为 VL53L0X ToF，量程 4m")
        void emergencyStandard_obstacleSensor() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            assertEquals(EmergencyBudgetConfig.ObstacleSensorType.VL53L0X_TOF,
                    cfg.emergencyBudgetConfig.obstacleSensorType);
            assertEquals(4.0, cfg.emergencyBudgetConfig.obstacleRangeM, 0.001);
        }

        @Test
        @DisplayName("emergency-standard GPS 为 NEO-M8N，精度 2-3m")
        void emergencyStandard_gps() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            assertEquals(EmergencyBudgetConfig.GpsType.NEO_M8N,
                    cfg.emergencyBudgetConfig.gpsType);
            assertTrue(cfg.emergencyBudgetConfig.gpsAccuracyM >= 2.0
                    && cfg.emergencyBudgetConfig.gpsAccuracyM <= 3.0);
        }

        @Test
        @DisplayName("emergency-standard 热成像为 AMG8833 8×8")
        void emergencyStandard_thermal() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            assertEquals(EmergencyBudgetConfig.ThermalType.AMG8833_8X8,
                    cfg.emergencyBudgetConfig.thermalType);
        }

        @Test
        @DisplayName("emergency-standard 摄像头为 5.8G FPV")
        void emergencyStandard_cameraType() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            assertEquals(EmergencyBudgetConfig.CameraType.FPV_5_8G,
                    cfg.emergencyBudgetConfig.cameraType);
        }

        @Test
        @DisplayName("emergency-standard MAX_HOPS=10，最多 20 节点")
        void emergencyStandard_meshParams() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            assertEquals(10, cfg.emergencyBudgetConfig.maxHops);
            assertEquals(20, cfg.emergencyBudgetConfig.maxNodes);
        }

        @Test
        @DisplayName("emergency-standard LED 搜救信号灯和蜂鸣器均启用")
        void emergencyStandard_ledAndBuzzer() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            assertTrue(cfg.emergencyBudgetConfig.ledEnabled);
            assertTrue(cfg.emergencyBudgetConfig.buzzerEnabled);
        }

        @Test
        @DisplayName("emergency-standard 预估成本约 429 元")
        void emergencyStandard_estimatedCost() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            assertEquals(429.0, cfg.emergencyBudgetConfig.estimatedCostYuan, 0.1);
        }
    }

    // ===== 传感器降级映射对比测试 =====

    @Nested
    @DisplayName("传感器降级映射")
    class SensorDegradationTest {

        @Test
        @DisplayName("Emergency-Toy vs Emergency-Standard 避障传感器降级")
        void obstacleSensor_degradation() {
            SimConfig toyCfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            SimConfig stdCfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            // Emergency-Toy: HC-SR04 超声波 2m → Emergency-Standard: VL53L0X ToF 4m
            assertEquals(EmergencyBudgetConfig.ObstacleSensorType.HC_SR04_ULTRASONIC,
                    toyCfg.emergencyBudgetConfig.obstacleSensorType);
            assertEquals(EmergencyBudgetConfig.ObstacleSensorType.VL53L0X_TOF,
                    stdCfg.emergencyBudgetConfig.obstacleSensorType);
            assertTrue(toyCfg.emergencyBudgetConfig.obstacleRangeM
                    < stdCfg.emergencyBudgetConfig.obstacleRangeM);
        }

        @Test
        @DisplayName("Emergency-Toy vs Emergency-Standard GPS 降级")
        void gps_degradation() {
            SimConfig toyCfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            SimConfig stdCfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            // Emergency-Toy: 无 GPS → Emergency-Standard: NEO-M8N 2-3m
            assertEquals(EmergencyBudgetConfig.GpsType.NONE,
                    toyCfg.emergencyBudgetConfig.gpsType);
            assertEquals(EmergencyBudgetConfig.GpsType.NEO_M8N,
                    stdCfg.emergencyBudgetConfig.gpsType);
        }

        @Test
        @DisplayName("Emergency-Toy vs Emergency-Standard 热成像降级")
        void thermal_degradation() {
            SimConfig toyCfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            SimConfig stdCfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            // Emergency-Toy: 无热成像 → Emergency-Standard: AMG8833 8×8
            assertEquals(EmergencyBudgetConfig.ThermalType.NONE,
                    toyCfg.emergencyBudgetConfig.thermalType);
            assertEquals(EmergencyBudgetConfig.ThermalType.AMG8833_8X8,
                    stdCfg.emergencyBudgetConfig.thermalType);
        }

        @Test
        @DisplayName("Emergency-Toy vs Emergency-Standard 通信类型降级")
        void commType_degradation() {
            SimConfig toyCfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            SimConfig stdCfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            // Emergency-Toy: WiFi ESP-NOW → Emergency-Standard: LoRa Mesh
            assertEquals(EmergencyBudgetConfig.CommType.WIFI_ESP_NOW,
                    toyCfg.emergencyBudgetConfig.commType);
            assertEquals(EmergencyBudgetConfig.CommType.LORA_MESH,
                    stdCfg.emergencyBudgetConfig.commType);
        }

        @Test
        @DisplayName("Emergency-Toy vs Emergency-Standard Mesh 跳数/节点数差异")
        void meshParams_degradation() {
            SimConfig toyCfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});
            SimConfig stdCfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});
            // Emergency-Toy: MAX_HOPS=5, 最多8节点 → Emergency-Standard: MAX_HOPS=10, 最多20节点
            assertTrue(toyCfg.emergencyBudgetConfig.maxHops
                    < stdCfg.emergencyBudgetConfig.maxHops);
            assertTrue(toyCfg.emergencyBudgetConfig.maxNodes
                    < stdCfg.emergencyBudgetConfig.maxNodes);
        }
    }

    // ===== EmergencyBudgetConfig 工厂方法测试 =====

    @Nested
    @DisplayName("EmergencyBudgetConfig 工厂方法")
    class EmergencyBudgetConfigFactoryTest {

        @Test
        @DisplayName("emergencyToy() 返回百元级灾害应急配置")
        void emergencyToy_factory() {
            EmergencyBudgetConfig config = EmergencyBudgetConfig.emergencyToy();
            assertEquals(BudgetMode.EMERGENCY_TOY, config.budgetMode);
            assertEquals(EmergencyBudgetConfig.CommType.WIFI_ESP_NOW, config.commType);
            assertEquals(EmergencyBudgetConfig.ObstacleSensorType.HC_SR04_ULTRASONIC,
                    config.obstacleSensorType);
            assertEquals(2.0, config.obstacleRangeM, 0.001);
            assertEquals(EmergencyBudgetConfig.GpsType.NONE, config.gpsType);
            assertEquals(EmergencyBudgetConfig.ThermalType.NONE, config.thermalType);
            assertEquals(EmergencyBudgetConfig.CameraType.ESP32_CAM, config.cameraType);
            assertEquals(5, config.maxHops);
            assertEquals(8, config.maxNodes);
            assertTrue(config.ledEnabled);
            assertTrue(config.buzzerEnabled);
            assertEquals(74.0, config.estimatedCostYuan, 0.1);
        }

        @Test
        @DisplayName("emergencyStandard() 返回千元级灾害应急配置")
        void emergencyStandard_factory() {
            EmergencyBudgetConfig config = EmergencyBudgetConfig.emergencyStandard();
            assertEquals(BudgetMode.EMERGENCY_STANDARD, config.budgetMode);
            assertEquals(EmergencyBudgetConfig.CommType.LORA_MESH, config.commType);
            assertEquals(EmergencyBudgetConfig.ObstacleSensorType.VL53L0X_TOF,
                    config.obstacleSensorType);
            assertEquals(4.0, config.obstacleRangeM, 0.001);
            assertEquals(EmergencyBudgetConfig.GpsType.NEO_M8N, config.gpsType);
            assertEquals(EmergencyBudgetConfig.ThermalType.AMG8833_8X8, config.thermalType);
            assertEquals(EmergencyBudgetConfig.CameraType.FPV_5_8G, config.cameraType);
            assertEquals(10, config.maxHops);
            assertEquals(20, config.maxNodes);
            assertTrue(config.ledEnabled);
            assertTrue(config.buzzerEnabled);
            assertEquals(429.0, config.estimatedCostYuan, 0.1);
        }

        @Test
        @DisplayName("forMode(EMERGENCY_TOY) 返回 emergencyToy 配置")
        void forMode_emergencyToy() {
            EmergencyBudgetConfig config = EmergencyBudgetConfig.forMode(BudgetMode.EMERGENCY_TOY);
            assertNotNull(config);
            assertEquals(BudgetMode.EMERGENCY_TOY, config.budgetMode);
        }

        @Test
        @DisplayName("forMode(EMERGENCY_STANDARD) 返回 emergencyStandard 配置")
        void forMode_emergencyStandard() {
            EmergencyBudgetConfig config = EmergencyBudgetConfig.forMode(BudgetMode.EMERGENCY_STANDARD);
            assertNotNull(config);
            assertEquals(BudgetMode.EMERGENCY_STANDARD, config.budgetMode);
        }

        @Test
        @DisplayName("forMode 非应急模式返回 null")
        void forMode_nonEmergencyReturnsNull() {
            assertNull(EmergencyBudgetConfig.forMode(BudgetMode.TOY));
            assertNull(EmergencyBudgetConfig.forMode(BudgetMode.STANDARD));
            assertNull(EmergencyBudgetConfig.forMode(BudgetMode.ADVANCED));
            assertNull(EmergencyBudgetConfig.forMode(null));
        }
    }

    // ===== 向下兼容性测试 =====

    @Nested
    @DisplayName("向下兼容性")
    class BackwardCompatibilityTest {

        @Test
        @DisplayName("非应急模式下 emergencyBudgetConfig 为 null")
        void nonEmergency_nullConfig() {
            SimConfig toyCfg = SimConfig.parse(new String[]{"--budget=toy"});
            SimConfig stdCfg = SimConfig.parse(new String[]{"--budget=standard"});
            SimConfig advCfg = SimConfig.parse(new String[]{"--budget=advanced"});
            SimConfig fullCfg = SimConfig.parse(new String[]{});

            assertNull(toyCfg.emergencyBudgetConfig);
            assertNull(stdCfg.emergencyBudgetConfig);
            assertNull(advCfg.emergencyBudgetConfig);
            assertNull(fullCfg.emergencyBudgetConfig);
        }

        @Test
        @DisplayName("defaults() 的 budgetMode 和 emergencyBudgetConfig 均为 null")
        void defaults_bothNull() {
            SimConfig cfg = SimConfig.defaults();
            assertNull(cfg.budgetMode);
            assertNull(cfg.emergencyBudgetConfig);
        }

        @Test
        @DisplayName("emergency-toy 与其他参数共存，全部正确解析")
        void emergencyToy_withOtherArgs() {
            SimConfig cfg = SimConfig.parse(new String[]{
                    "--budget=emergency-toy", "--port=14542", "--sysid=3", "--speed=5.0"
            });
            assertEquals(BudgetMode.EMERGENCY_TOY, cfg.budgetMode);
            assertEquals(14542, cfg.port);
            assertEquals(3, cfg.sysid);
            assertEquals(5.0, cfg.speed, 0.0001);
            assertNotNull(cfg.emergencyBudgetConfig);
        }

        @Test
        @DisplayName("emergency-standard 与其他参数共存，全部正确解析")
        void emergencyStandard_withOtherArgs() {
            SimConfig cfg = SimConfig.parse(new String[]{
                    "--budget=emergency-standard", "--port=14543", "--sysid=4", "--speed=12.0"
            });
            assertEquals(BudgetMode.EMERGENCY_STANDARD, cfg.budgetMode);
            assertEquals(14543, cfg.port);
            assertEquals(4, cfg.sysid);
            assertEquals(12.0, cfg.speed, 0.0001);
            assertNotNull(cfg.emergencyBudgetConfig);
        }

        @Test
        @DisplayName("既有 toy/standard/advanced 模式不受 emergency 扩展影响")
        void existingModes_unaffected() {
            SimConfig toyCfg = SimConfig.parse(new String[]{"--budget=toy"});
            SimConfig stdCfg = SimConfig.parse(new String[]{"--budget=standard"});
            SimConfig advCfg = SimConfig.parse(new String[]{"--budget=advanced"});

            assertEquals(BudgetMode.TOY, toyCfg.budgetMode);
            assertEquals(BudgetMode.STANDARD, stdCfg.budgetMode);
            assertEquals(BudgetMode.ADVANCED, advCfg.budgetMode);

            assertNull(toyCfg.emergencyBudgetConfig);
            assertNull(stdCfg.emergencyBudgetConfig);
            assertNull(advCfg.emergencyBudgetConfig);
        }

        @Test
        @DisplayName("无效 budget 值仍被忽略，budgetMode 保持 null")
        void invalidBudget_ignored() {
            SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-invalid"});
            assertNull(cfg.budgetMode);
            assertNull(cfg.emergencyBudgetConfig);
        }
    }

    // ===== AssertJ 风格综合验证 =====

    @Test
    @DisplayName("Emergency-Toy 完整配置 AssertJ 综合验证")
    void emergencyToy_assertJComprehensive() {
        SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-toy"});

        assertThat(cfg.budgetMode).isEqualTo(BudgetMode.EMERGENCY_TOY);
        assertThat(cfg.budgetMode.isEmergency()).isTrue();
        assertThat(cfg.emergencyBudgetConfig).isNotNull();
        assertThat(cfg.emergencyBudgetConfig.commType)
                .isEqualTo(EmergencyBudgetConfig.CommType.WIFI_ESP_NOW);
        assertThat(cfg.emergencyBudgetConfig.obstacleSensorType)
                .isEqualTo(EmergencyBudgetConfig.ObstacleSensorType.HC_SR04_ULTRASONIC);
        assertThat(cfg.emergencyBudgetConfig.obstacleRangeM).isEqualTo(2.0);
        assertThat(cfg.emergencyBudgetConfig.gpsType)
                .isEqualTo(EmergencyBudgetConfig.GpsType.NONE);
        assertThat(cfg.emergencyBudgetConfig.thermalType)
                .isEqualTo(EmergencyBudgetConfig.ThermalType.NONE);
        assertThat(cfg.emergencyBudgetConfig.cameraType)
                .isEqualTo(EmergencyBudgetConfig.CameraType.ESP32_CAM);
        assertThat(cfg.emergencyBudgetConfig.maxHops).isEqualTo(5);
        assertThat(cfg.emergencyBudgetConfig.maxNodes).isEqualTo(8);
        assertThat(cfg.emergencyBudgetConfig.ledEnabled).isTrue();
        assertThat(cfg.emergencyBudgetConfig.buzzerEnabled).isTrue();
        assertThat(cfg.emergencyBudgetConfig.estimatedCostYuan).isEqualTo(74.0);
    }

    @Test
    @DisplayName("Emergency-Standard 完整配置 AssertJ 综合验证")
    void emergencyStandard_assertJComprehensive() {
        SimConfig cfg = SimConfig.parse(new String[]{"--budget=emergency-standard"});

        assertThat(cfg.budgetMode).isEqualTo(BudgetMode.EMERGENCY_STANDARD);
        assertThat(cfg.budgetMode.isEmergency()).isTrue();
        assertThat(cfg.emergencyBudgetConfig).isNotNull();
        assertThat(cfg.emergencyBudgetConfig.commType)
                .isEqualTo(EmergencyBudgetConfig.CommType.LORA_MESH);
        assertThat(cfg.emergencyBudgetConfig.obstacleSensorType)
                .isEqualTo(EmergencyBudgetConfig.ObstacleSensorType.VL53L0X_TOF);
        assertThat(cfg.emergencyBudgetConfig.obstacleRangeM).isEqualTo(4.0);
        assertThat(cfg.emergencyBudgetConfig.gpsType)
                .isEqualTo(EmergencyBudgetConfig.GpsType.NEO_M8N);
        assertThat(cfg.emergencyBudgetConfig.gpsAccuracyM).isBetween(2.0, 3.0);
        assertThat(cfg.emergencyBudgetConfig.thermalType)
                .isEqualTo(EmergencyBudgetConfig.ThermalType.AMG8833_8X8);
        assertThat(cfg.emergencyBudgetConfig.cameraType)
                .isEqualTo(EmergencyBudgetConfig.CameraType.FPV_5_8G);
        assertThat(cfg.emergencyBudgetConfig.maxHops).isEqualTo(10);
        assertThat(cfg.emergencyBudgetConfig.maxNodes).isEqualTo(20);
        assertThat(cfg.emergencyBudgetConfig.ledEnabled).isTrue();
        assertThat(cfg.emergencyBudgetConfig.buzzerEnabled).isTrue();
        assertThat(cfg.emergencyBudgetConfig.estimatedCostYuan).isEqualTo(429.0);
    }
}