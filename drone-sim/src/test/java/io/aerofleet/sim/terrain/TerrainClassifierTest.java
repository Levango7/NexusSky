package io.aerofleet.sim.terrain;

import io.aerofleet.sim.TerrainModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TerrainClassifier 单测（FR-06）。
 */
@DisplayName("TerrainClassifier 分类器 (FR-06)")
class TerrainClassifierTest {

    @Test
    @DisplayName("平坦地形 + 无地物 → 全 FLAT")
    void flatTerrainClassifiesAsFlat() {
        TerrainGrid grid = TerrainClassifier.classify(
                30.0, 120.0, 200, 200, 100,
                TerrainModel.flat(), null);
        assertThat(grid.mapWidth()).isEqualTo(2);
        assertThat(grid.mapHeight()).isEqualTo(2);
        for (int i = 0; i < grid.cellCount(); i++) {
            int gx = grid.gridXOf(i), gy = grid.gridYOf(i);
            assertThat(grid.typeAtCell(gx, gy)).isEqualTo(TerrainType.FLAT);
        }
    }

    @Test
    @DisplayName("地物数据源驱动分类：森林地物 → FOREST")
    void featureSourceDrivesClassification() {
        TerrainClassifier.TerrainFeatureSource forestSource = new TerrainClassifier.TerrainFeatureSource() {
            @Override
            public TerrainClassifier.TerrainFeature featureAt(double northM, double eastM) {
                return TerrainClassifier.TerrainFeature.FOREST;
            }
            @Override
            public double maxBuildingHeight(double northM, double eastM) {
                return 0;
            }
        };
        TerrainGrid grid = TerrainClassifier.classify(
                30.0, 120.0, 100, 100, 100,
                TerrainModel.flat(), forestSource);
        assertThat(grid.typeAtCell(0, 0)).isEqualTo(TerrainType.FOREST);
    }

    @Test
    @DisplayName("网格数超限 → IllegalArgumentException")
    void gridCountExceedsMaxThrows() {
        // 1000m × 1000m / 1m = 1000000 cells > 65535
        assertThatThrownBy(() -> TerrainClassifier.classify(
                30.0, 120.0, 1000, 1000, 1,
                TerrainModel.flat(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds max");
    }
}