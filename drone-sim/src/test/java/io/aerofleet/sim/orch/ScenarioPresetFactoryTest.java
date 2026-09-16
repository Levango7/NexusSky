package io.aerofleet.sim.orch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ScenarioPresetFactory 场景预设工厂单测（M9 应急任务编排，T4 场景预设）。
 * <p>
 * 覆盖：4 种内置预设参数正确、越界抛异常、all() 返回 4 种、权重和约为 1.0。
 */
@DisplayName("ScenarioPresetFactory 场景预设工厂 (T4)")
class ScenarioPresetFactoryTest {

    @Test
    @DisplayName("get(0) 返回地震预设，参数正确")
    void getEarthquakePreset() {
        ScenarioPreset p = ScenarioPresetFactory.get(0);

        assertThat(p.type).isEqualTo(0);
        assertThat(p.name).isEqualTo("地震");
        assertThat(p.typicalRadiusKm).isCloseTo(5.0, within(1e-9));
        assertThat(p.recommendedCellType).isEqualTo(1);   // LTE
        assertThat(p.recommendedRelayLayers).isEqualTo(2); // HAPS+mesh
        assertThat(p.minDrones).isEqualTo(8);
        assertThat(p.maxDrones).isEqualTo(15);
        assertThat(p.searchRescueWeight).isCloseTo(0.6, within(1e-9));
        assertThat(p.commandWeight).isCloseTo(0.3, within(1e-9));
        assertThat(p.mappingWeight).isCloseTo(0.1, within(1e-9));
        assertThat(p.buildingDamageRate).isCloseTo(0.45, within(1e-9));
        assertThat(p.terrainChangeLevel).isEqualTo(1); // 中等
    }

    @Test
    @DisplayName("get(1) 返回泥石流预设，参数正确")
    void getMudslidePreset() {
        ScenarioPreset p = ScenarioPresetFactory.get(1);

        assertThat(p.type).isEqualTo(1);
        assertThat(p.name).isEqualTo("泥石流");
        assertThat(p.typicalRadiusKm).isCloseTo(5.0, within(1e-9)); // 2x10km 用 5km 近似
        assertThat(p.recommendedCellType).isEqualTo(3);   // LoRa+LTE
        assertThat(p.recommendedRelayLayers).isEqualTo(3); // LEO+HAPS+mesh
        assertThat(p.minDrones).isEqualTo(5);
        assertThat(p.maxDrones).isEqualTo(10);
        assertThat(p.searchRescueWeight).isCloseTo(0.3, within(1e-9));
        assertThat(p.commandWeight).isCloseTo(0.5, within(1e-9));
        assertThat(p.mappingWeight).isCloseTo(0.2, within(1e-9));
        assertThat(p.buildingDamageRate).isCloseTo(0.20, within(1e-9));
        assertThat(p.terrainChangeLevel).isEqualTo(2); // 严重
    }

    @Test
    @DisplayName("get(2) 返回火灾预设，参数正确")
    void getFirePreset() {
        ScenarioPreset p = ScenarioPresetFactory.get(2);

        assertThat(p.type).isEqualTo(2);
        assertThat(p.name).isEqualTo("火灾");
        assertThat(p.typicalRadiusKm).isCloseTo(3.0, within(1e-9));
        assertThat(p.recommendedCellType).isEqualTo(2);   // WiFi+LTE
        assertThat(p.recommendedRelayLayers).isEqualTo(2); // HAPS+mesh
        assertThat(p.minDrones).isEqualTo(6);
        assertThat(p.maxDrones).isEqualTo(12);
        assertThat(p.searchRescueWeight).isCloseTo(0.2, within(1e-9));
        assertThat(p.commandWeight).isCloseTo(0.3, within(1e-9));
        assertThat(p.mappingWeight).isCloseTo(0.5, within(1e-9));
        assertThat(p.buildingDamageRate).isCloseTo(0.10, within(1e-9));
        assertThat(p.terrainChangeLevel).isEqualTo(0); // 轻微
    }

    @Test
    @DisplayName("get(3) 返回自定义预设")
    void getCustomPreset() {
        ScenarioPreset p = ScenarioPresetFactory.get(3);

        assertThat(p.type).isEqualTo(3);
        assertThat(p.name).isEqualTo("自定义");
        assertThat(p.typicalRadiusKm).isCloseTo(5.0, within(1e-9));
        assertThat(p.recommendedCellType).isEqualTo(1);
        assertThat(p.recommendedRelayLayers).isEqualTo(2);
        assertThat(p.minDrones).isEqualTo(5);
        assertThat(p.maxDrones).isEqualTo(20);
        assertThat(p.searchRescueWeight).isCloseTo(0.25, within(1e-9));
        assertThat(p.commandWeight).isCloseTo(0.25, within(1e-9));
        assertThat(p.mappingWeight).isCloseTo(0.25, within(1e-9));
        assertThat(p.buildingDamageRate).isCloseTo(0.20, within(1e-9));
        assertThat(p.terrainChangeLevel).isEqualTo(1);
    }

    @Test
    @DisplayName("get(99) 抛 IllegalArgumentException")
    void getUnknownTypeThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ScenarioPresetFactory.get(99));
    }

    @Test
    @DisplayName("get(-1) 抛 IllegalArgumentException")
    void getNegativeTypeThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ScenarioPresetFactory.get(-1));
    }

    @Test
    @DisplayName("all() 返回 4 种预设")
    void allReturnsFourPresets() {
        List<ScenarioPreset> all = ScenarioPresetFactory.all();

        assertThat(all).hasSize(4);
        assertThat(all.get(0).type).isEqualTo(0);
        assertThat(all.get(1).type).isEqualTo(1);
        assertThat(all.get(2).type).isEqualTo(2);
        assertThat(all.get(3).type).isEqualTo(3);
        assertThat(all).isUnmodifiable();
    }

    @Test
    @DisplayName("地震预设权重和约为 1.0")
    void earthquakeWeightsSumToOne() {
        ScenarioPreset p = ScenarioPresetFactory.get(0);
        double sum = p.searchRescueWeight + p.commandWeight + p.mappingWeight;
        assertThat(sum).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("泥石流预设权重和约为 1.0")
    void mudslideWeightsSumToOne() {
        ScenarioPreset p = ScenarioPresetFactory.get(1);
        double sum = p.searchRescueWeight + p.commandWeight + p.mappingWeight;
        assertThat(sum).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("火灾预设权重和约为 1.0")
    void fireWeightsSumToOne() {
        ScenarioPreset p = ScenarioPresetFactory.get(2);
        double sum = p.searchRescueWeight + p.commandWeight + p.mappingWeight;
        assertThat(sum).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("自定义预设权重和约为 0.75（均匀 0.25×3，预留 0.25 给常规）")
    void customWeightsSum() {
        ScenarioPreset p = ScenarioPresetFactory.get(3);
        double sum = p.searchRescueWeight + p.commandWeight + p.mappingWeight;
        // 自定义场景三项均匀 0.25，和为 0.75（剩余 0.25 预留给常规巡检）
        assertThat(sum).isCloseTo(0.75, within(1e-9));
    }

    @Test
    @DisplayName("所有预设 toString 包含名称")
    void toStringContainsName() {
        for (ScenarioPreset p : ScenarioPresetFactory.all()) {
            assertThat(p.toString()).contains(p.name);
        }
    }
}