package io.aerofleet.sim.terrain;

import io.aerofleet.sim.TerrainModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TerrainGrid 单测（FR-03/FR-05）。
 */
@DisplayName("TerrainGrid 地形分区图 (FR-03/05)")
class TerrainGridTest {

    private TerrainGrid makeGrid3x3() {
        TerrainType[] cells = {
                TerrainType.FLAT, TerrainType.FOREST, TerrainType.SWAMP,
                TerrainType.HILL, TerrainType.MOUNTAIN, TerrainType.OLD_CITY_DENSE,
                TerrainType.NATURE_RESERVE, TerrainType.SUPER_HIGH_RISE, TerrainType.MIXED,
        };
        return new TerrainGrid(cells, 3, 3, 100, 30.0, 120.0, TerrainModel.flat());
    }

    @Test
    @DisplayName("构造器校验 cells 长度 = mapWidth × mapHeight")
    void constructorValidatesCellCount() {
        assertThatThrownBy(() -> new TerrainGrid(new TerrainType[3], 2, 2, 10, 0, 0, TerrainModel.flat()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerrainGrid(new TerrainType[4], 2, 2, 0, 0, 0, TerrainModel.flat()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("typeAtCell 查询 + 越界返回 FLAT")
    void typeAtCellAndOutOfBounds() {
        TerrainGrid grid = makeGrid3x3();
        // cells 按 row-major 存储：cells[gridY * mapWidth + gridX]
        // row 0: FLAT(0,0) FOREST(1,0) SWAMP(2,0)
        // row 1: HILL(0,1) MOUNTAIN(1,1) OLD_CITY_DENSE(2,1)
        assertThat(grid.typeAtCell(0, 0)).isEqualTo(TerrainType.FLAT);
        assertThat(grid.typeAtCell(1, 1)).isEqualTo(TerrainType.MOUNTAIN);
        assertThat(grid.typeAtCell(-1, 0)).isEqualTo(TerrainType.FLAT);
        assertThat(grid.typeAtCell(3, 0)).isEqualTo(TerrainType.FLAT);
    }

    @Test
    @DisplayName("updateCell 递增版本号")
    void updateCellIncrementsVersion() {
        TerrainGrid grid = makeGrid3x3();
        assertThat(grid.version()).isZero();
        long v1 = grid.updateCell(0, TerrainType.SWAMP);
        assertThat(v1).isEqualTo(1);
        assertThat(grid.version()).isEqualTo(1);
        assertThat(grid.typeAtCell(0, 0)).isEqualTo(TerrainType.SWAMP);
        long v2 = grid.updateCell(4, TerrainType.FLAT);
        assertThat(v2).isEqualTo(2);
    }

    @Test
    @DisplayName("updateCell 越界索引忽略，版本不变")
    void updateCellOutOfBoundsIgnored() {
        TerrainGrid grid = makeGrid3x3();
        long v = grid.updateCell(100, TerrainType.SWAMP);
        assertThat(v).isZero();
        assertThat(grid.version()).isZero();
    }

    @Test
    @DisplayName("typesAlongPath 返回 samples+1 个点")
    void typesAlongPathSampleCount() {
        TerrainGrid grid = makeGrid3x3();
        var types = grid.typesAlongPath(30.0, 120.0, 30.001, 120.001, 4);
        assertThat(types).hasSize(5);
    }
}