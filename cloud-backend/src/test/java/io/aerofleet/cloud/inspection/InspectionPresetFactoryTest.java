package io.aerofleet.cloud.inspection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InspectionPresetFactory} 单测。
 * <p>
 * 验证 4 种行业模板创建成功，参数符合预设规范。
 */
@DisplayName("InspectionPresetFactory 预设模板 (P1-1)")
class InspectionPresetFactoryTest {

    private InspectionPresetFactory factory;

    @BeforeEach
    void setUp() {
        factory = new InspectionPresetFactory();
    }

    @Test
    @DisplayName("电力线路预设模板创建成功")
    void powerLinePresetCreated() {
        InspectionTemplate tpl = factory.powerLinePreset();

        assertThat(tpl.id()).isEqualTo("preset-power-line");
        assertThat(tpl.name()).isEqualTo("电力线路巡检");
        assertThat(tpl.industryType()).isEqualTo(IndustryType.POWER_LINE);
        assertThat(tpl.routeType()).isEqualTo(RouteType.LINEAR_GRID);
        assertThat(tpl.altitudeM()).isEqualTo(80.0);
        assertThat(tpl.overlapPct()).isEqualTo(80.0);
        assertThat(tpl.speedMps()).isEqualTo(8.0);
        assertThat(tpl.cameraAngleDeg()).isEqualTo(90.0);
        assertThat(tpl.pointsOfInterest()).hasSize(3);
        assertThat(tpl.totalDistanceKm()).isEqualTo(5.0);
        assertThat(tpl.estimatedDurationMin()).isEqualTo(12.0);
        assertThat(tpl.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("油气管道预设模板创建成功")
    void oilGasPipePresetCreated() {
        InspectionTemplate tpl = factory.oilGasPipePreset();

        assertThat(tpl.id()).isEqualTo("preset-oil-gas-pipe");
        assertThat(tpl.name()).isEqualTo("油气管道巡检");
        assertThat(tpl.industryType()).isEqualTo(IndustryType.OIL_GAS_PIPE);
        assertThat(tpl.routeType()).isEqualTo(RouteType.LINEAR_GRID);
        assertThat(tpl.altitudeM()).isEqualTo(50.0);
        assertThat(tpl.overlapPct()).isEqualTo(70.0);
        assertThat(tpl.speedMps()).isEqualTo(6.0);
        assertThat(tpl.cameraAngleDeg()).isEqualTo(60.0);
        assertThat(tpl.pointsOfInterest()).hasSize(2);
        assertThat(tpl.totalDistanceKm()).isEqualTo(8.0);
        assertThat(tpl.estimatedDurationMin()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("铁路沿线预设模板创建成功")
    void railwayPresetCreated() {
        InspectionTemplate tpl = factory.railwayPreset();

        assertThat(tpl.id()).isEqualTo("preset-railway");
        assertThat(tpl.name()).isEqualTo("铁路沿线巡检");
        assertThat(tpl.industryType()).isEqualTo(IndustryType.RAILWAY);
        assertThat(tpl.routeType()).isEqualTo(RouteType.CROSS_GRID);
        assertThat(tpl.altitudeM()).isEqualTo(100.0);
        assertThat(tpl.overlapPct()).isEqualTo(60.0);
        assertThat(tpl.speedMps()).isEqualTo(10.0);
        assertThat(tpl.cameraAngleDeg()).isEqualTo(75.0);
        assertThat(tpl.pointsOfInterest()).hasSize(2);
        assertThat(tpl.totalDistanceKm()).isEqualTo(12.0);
        assertThat(tpl.estimatedDurationMin()).isEqualTo(22.0);
    }

    @Test
    @DisplayName("光伏电站预设模板创建成功")
    void solarFarmPresetCreated() {
        InspectionTemplate tpl = factory.solarFarmPreset();

        assertThat(tpl.id()).isEqualTo("preset-solar-farm");
        assertThat(tpl.name()).isEqualTo("光伏电站巡检");
        assertThat(tpl.industryType()).isEqualTo(IndustryType.SOLAR_FARM);
        assertThat(tpl.routeType()).isEqualTo(RouteType.ORBIT);
        assertThat(tpl.altitudeM()).isEqualTo(60.0);
        assertThat(tpl.overlapPct()).isEqualTo(65.0);
        assertThat(tpl.speedMps()).isEqualTo(5.0);
        assertThat(tpl.cameraAngleDeg()).isEqualTo(90.0);
        assertThat(tpl.pointsOfInterest()).hasSize(2);
        assertThat(tpl.totalDistanceKm()).isEqualTo(3.0);
        assertThat(tpl.estimatedDurationMin()).isEqualTo(15.0);
    }

    @Test
    @DisplayName("allPresets 返回 4 种预设模板")
    void allPresetsReturnsFour() {
        var presets = factory.allPresets();

        assertThat(presets).hasSize(4);
        // 行业类型应各不相同
        var industries = presets.stream().map(InspectionTemplate::industryType).toList();
        assertThat(industries).containsExactlyInAnyOrder(
                IndustryType.POWER_LINE,
                IndustryType.OIL_GAS_PIPE,
                IndustryType.RAILWAY,
                IndustryType.SOLAR_FARM);
    }

    @Test
    @DisplayName("findById 按 ID 查找预设模板")
    void findByIdReturnsTemplate() {
        InspectionTemplate tpl = factory.findById("preset-power-line");
        assertThat(tpl).isNotNull();
        assertThat(tpl.industryType()).isEqualTo(IndustryType.POWER_LINE);
    }

    @Test
    @DisplayName("findById 不存在的 ID 返回 null")
    void findByIdUnknownReturnsNull() {
        InspectionTemplate tpl = factory.findById("nonexistent");
        assertThat(tpl).isNull();
    }

    @Test
    @DisplayName("findById null 返回 null")
    void findByIdNullReturnsNull() {
        InspectionTemplate tpl = factory.findById(null);
        assertThat(tpl).isNull();
    }
}