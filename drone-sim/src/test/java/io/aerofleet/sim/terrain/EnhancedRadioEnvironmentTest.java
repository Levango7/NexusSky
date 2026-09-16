package io.aerofleet.sim.terrain;

import io.aerofleet.sim.RadioEnvironment;
import io.aerofleet.sim.TerrainModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EnhancedRadioEnvironment 单测（FR-12/13/14）。
 */
@DisplayName("EnhancedRadioEnvironment 增强 RF 模型 (FR-12/13/14)")
class EnhancedRadioEnvironmentTest {

    private static final double GCS_LAT = 30.0;
    private static final double GCS_LON = 120.0;
    private static final double GCS_ANTENNA_M = 2.0;

    private RadioEnvironment legacyEnv() {
        return new RadioEnvironment(TerrainModel.flat(), 0, 0, GCS_ANTENNA_M);
    }

    @Test
    @DisplayName("退化模式：terrainGrid=null 时 RSSI 与 legacy 一致 (FR-14)")
    void degradationMatchesLegacy() {
        RadioEnvironment legacy = legacyEnv();
        EnhancedRadioEnvironment enhanced = new EnhancedRadioEnvironment(
                legacy, null, RadioEnvironment.FREQ_MHZ, GCS_LAT, GCS_LON, GCS_ANTENNA_M);

        // 平坦地形下，north/east 偏移 100m, 高度 50m
        double rssiLegacy = legacy.rssiDbm(100, 0, 50);
        double rssiEnhanced = enhanced.rssiDbm(100, 0, 50);
        assertThat(rssiEnhanced).isEqualTo(rssiLegacy, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("退化模式：los 与 legacy.occluded 一致")
    void degradationLosConsistent() {
        RadioEnvironment legacy = legacyEnv();
        EnhancedRadioEnvironment enhanced = new EnhancedRadioEnvironment(
                legacy, null, RadioEnvironment.FREQ_MHZ, GCS_LAT, GCS_LON, GCS_ANTENNA_M);
        // 平坦地形无遮挡
        assertThat(enhanced.los(100, 0, 50)).isTrue();
        assertThat(enhanced.los(100, 0, 50)).isEqualTo(!legacy.occluded(100, 0, 50));
    }

    @Test
    @DisplayName("增强模式：森林地形 RSSI 低于平地（植被衰减 > 0）")
    void forestTerrainLowerRssiThanFlat() {
        RadioEnvironment legacy = legacyEnv();
        // 森林地形网格
        TerrainType[] cells = {TerrainType.FOREST};
        TerrainGrid forestGrid = new TerrainGrid(cells, 1, 1, 1000, GCS_LAT, GCS_LON, TerrainModel.flat());

        EnhancedRadioEnvironment flatEnv = new EnhancedRadioEnvironment(
                legacy, null, RadioEnvironment.FREQ_MHZ, GCS_LAT, GCS_LON, GCS_ANTENNA_M);
        EnhancedRadioEnvironment forestEnv = new EnhancedRadioEnvironment(
                legacy, forestGrid, RadioEnvironment.FREQ_MHZ, GCS_LAT, GCS_LON, GCS_ANTENNA_M);

        double rssiFlat = flatEnv.rssiDbm(100, 0, 50);
        double rssiForest = forestEnv.rssiDbm(100, 0, 50);
        assertThat(rssiForest).isLessThan(rssiFlat);
    }

    @Test
    @DisplayName("增强模式：建筑穿透损耗降低 RSSI（老城密集区）")
    void buildingPenetrationReducesRssi() {
        RadioEnvironment legacy = legacyEnv();
        TerrainType[] cells = {TerrainType.OLD_CITY_DENSE};
        TerrainGrid cityGrid = new TerrainGrid(cells, 1, 1, 1000, GCS_LAT, GCS_LON, TerrainModel.flat());

        EnhancedRadioEnvironment env = new EnhancedRadioEnvironment(
                legacy, cityGrid, RadioEnvironment.FREQ_MHZ, GCS_LAT, GCS_LON, GCS_ANTENNA_M);
        RssiDecomposed decomposed = env.rssiDecomposed(100, 0, 50);
        // 老城密集区建筑穿透损耗 20 dB
        assertThat(decomposed.buildingLossDb()).isGreaterThan(0);
    }

    @Test
    @DisplayName("增强模式：沼泽多径损耗 NLOS 时 > 0，LOS 时为 0")
    void swampMultipathLoss() {
        // MultipathModel: NLOS 时多径损耗 = multipathFactor × 10 dB
        // 沼泽 multipathFactor=0.9，NLOS 时损耗 = 9 dB
        assertThat(MultipathModel.lossDb(TerrainType.SWAMP, true)).isGreaterThan(0);
        assertThat(MultipathModel.lossDb(TerrainType.SWAMP, false)).isZero();
        // 沼泽反射系数在 [0.7, 0.9] 范围
        assertThat(MultipathModel.isSwampReflection(TerrainType.SWAMP)).isTrue();
    }

    @Test
    @DisplayName("RssiDecomposed 5 项分量齐全")
    void rssiDecomposedHasAllComponents() {
        RadioEnvironment legacy = legacyEnv();
        EnhancedRadioEnvironment env = new EnhancedRadioEnvironment(
                legacy, null, RadioEnvironment.FREQ_MHZ, GCS_LAT, GCS_LON, GCS_ANTENNA_M);
        RssiDecomposed d = env.rssiDecomposed(100, 0, 50);
        // 退化模式：veg/bld/multipath 均为 0
        assertThat(d.vegetationLossDb()).isZero();
        assertThat(d.buildingLossDb()).isZero();
        assertThat(d.multipathLossDb()).isZero();
        assertThat(d.freeSpaceLossDb()).isGreaterThan(0);
    }

    @Test
    @DisplayName("频率默认值 = RadioEnvironment.FREQ_MHZ")
    void defaultFrequency() {
        EnhancedRadioEnvironment env = new EnhancedRadioEnvironment(
                legacyEnv(), null, 0, GCS_LAT, GCS_LON, GCS_ANTENNA_M);
        assertThat(env.freqMhz()).isEqualTo(RadioEnvironment.FREQ_MHZ);
    }

    @Test
    @DisplayName("近距离 RSSI 高于远距离")
    void closerDistanceHigherRssi() {
        RadioEnvironment legacy = legacyEnv();
        EnhancedRadioEnvironment env = new EnhancedRadioEnvironment(
                legacy, null, RadioEnvironment.FREQ_MHZ, GCS_LAT, GCS_LON, GCS_ANTENNA_M);
        double rssiNear = env.rssiDbm(50, 0, 50);
        double rssiFar = env.rssiDbm(500, 0, 50);
        assertThat(rssiNear).isGreaterThan(rssiFar);
    }
}