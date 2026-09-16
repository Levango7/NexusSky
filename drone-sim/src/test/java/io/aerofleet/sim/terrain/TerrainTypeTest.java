package io.aerofleet.sim.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerrainType 枚举单测（FR-01）。
 */
@DisplayName("TerrainType 枚举 (FR-01)")
class TerrainTypeTest {

    @Test
    @DisplayName("9 种地形类型 code 0-8 连续")
    void nineTypesWithSequentialCodes() {
        assertThat(TerrainType.values()).hasSize(9);
        for (int i = 0; i < 9; i++) {
            assertThat(TerrainType.fromCode(i).code).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("fromCode 合法值往返一致")
    void fromCodeRoundtrip() {
        assertThat(TerrainType.fromCode(0)).isEqualTo(TerrainType.MOUNTAIN);
        assertThat(TerrainType.fromCode(3)).isEqualTo(TerrainType.SWAMP);
        assertThat(TerrainType.fromCode(7)).isEqualTo(TerrainType.FLAT);
    }

    @Test
    @DisplayName("fromCode 非法值降级为 FLAT")
    void fromCodeIllegalDefaultsToFlat() {
        assertThat(TerrainType.fromCode(-1)).isEqualTo(TerrainType.FLAT);
        assertThat(TerrainType.fromCode(9)).isEqualTo(TerrainType.FLAT);
        assertThat(TerrainType.fromCode(255)).isEqualTo(TerrainType.FLAT);
    }

    @Test
    @DisplayName("每种类型关联 RF 特性参数非 null")
    void rfProfileNotNull() {
        for (TerrainType t : TerrainType.values()) {
            assertThat(t.rfProfile).isNotNull();
        }
        // 沼泽反射系数 0.8（范围 0.7-0.9）
        assertThat(TerrainType.SWAMP.rfProfile.groundReflectionCoeff()).isEqualTo(0.8);
        // 森林植被衰减系数 0.1
        assertThat(TerrainType.FOREST.rfProfile.vegetationAttenuationCoeff()).isEqualTo(0.1);
    }
}