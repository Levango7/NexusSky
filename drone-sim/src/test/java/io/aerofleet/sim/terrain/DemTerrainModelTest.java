package io.aerofleet.sim.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("DemTerrainModel DEM数字高程模型")
class DemTerrainModelTest {

    @Test
    @DisplayName("bilinear插值：网格中心点应返回四角平均值")
    void testBilinearInterpolationCenter() {
        DemTerrainModel model = new DemTerrainModel(3, 3, 0.01);
        model.elevations[0][0] = 100;
        model.elevations[0][1] = 200;
        model.elevations[1][0] = 150;
        model.elevations[1][1] = 250;
        double e = model.getElevation(0.005, 0.005);
        assertThat(e).isCloseTo(175.0, within(1.0));
    }

    @Test
    @DisplayName("bilinear插值：精确网格点应返回该点高程值")
    void testBilinearInterpolationAtGridPoint() {
        DemTerrainModel model = new DemTerrainModel(3, 3, 0.01);
        model.elevations[0][0] = 100;
        model.elevations[0][1] = 200;
        model.elevations[1][0] = 150;
        model.elevations[1][1] = 250;
        assertThat(model.getElevation(0.0, 0.0)).isCloseTo(100, within(0.001));
        assertThat(model.getElevation(0.0, 0.01)).isCloseTo(200, within(0.001));
        assertThat(model.getElevation(0.01, 0.0)).isCloseTo(150, within(0.001));
        assertThat(model.getElevation(0.01, 0.01)).isCloseTo(250, within(0.001));
    }

    @Test
    @DisplayName("bilinear插值：越界查询应返回0")
    void testBilinearInterpolationOutOfBounds() {
        DemTerrainModel model = new DemTerrainModel(3, 3, 0.01);
        model.elevations[0][0] = 100;
        assertThat(model.getElevation(-0.01, 0.0)).isCloseTo(0.0, within(0.001));
        assertThat(model.getElevation(0.05, 0.0)).isCloseTo(0.0, within(0.001));
    }

    @Test
    @DisplayName("合成丘陵地形：应生成有效高程数据")
    void testSyntheticHills() {
        DemTerrainModel model = DemTerrainModel.syntheticHills(10, 10, 0.01);
        assertThat(model).isNotNull();
        assertThat(model.rows).isEqualTo(10);
        assertThat(model.cols).isEqualTo(10);
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 10; c++) {
                assertThat(model.elevations[r][c]).isFinite();
            }
        }
    }

    @Test
    @DisplayName("合成山地地形：中心高程应高于边缘")
    void testSyntheticMountain() {
        DemTerrainModel model = DemTerrainModel.syntheticMountain(20, 20, 0.01);
        assertThat(model).isNotNull();
        double centerElev = model.elevations[10][10];
        double cornerElev = model.elevations[0][0];
        assertThat(centerElev).isGreaterThan(cornerElev);
    }

    @Test
    @DisplayName("合成平原地形：高程应在10~15范围内")
    void testSyntheticPlain() {
        DemTerrainModel model = DemTerrainModel.syntheticPlain(5, 5, 0.01);
        assertThat(model).isNotNull();
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                assertThat(model.elevations[r][c]).isBetween(10.0, 15.0);
            }
        }
    }

    @Test
    @DisplayName("合成峡谷地形：中心高程应低于边缘")
    void testSyntheticCanyon() {
        DemTerrainModel model = DemTerrainModel.syntheticCanyon(10, 20, 0.01);
        assertThat(model).isNotNull();
        double centerElev = model.elevations[5][10];
        double edgeElev = model.elevations[5][0];
        assertThat(centerElev).isLessThan(edgeElev);
    }

    @Test
    @DisplayName("坡度计算：平坦地形坡度应接近0")
    void testSlopeFlatTerrain() {
        DemTerrainModel model = new DemTerrainModel(5, 5, 0.001);
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                model.elevations[r][c] = 100;
            }
        }
        double slope = model.getSlope(0.002, 0.002);
        assertThat(slope).isCloseTo(0.0, within(0.001));
    }

    @Test
    @DisplayName("坡度计算：倾斜地形坡度应大于0")
    void testSlopeTiltedTerrain() {
        DemTerrainModel model = new DemTerrainModel(5, 5, 0.001);
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                model.elevations[r][c] = c * 100;
            }
        }
        double slope = model.getSlope(0.002, 0.002);
        assertThat(slope).isGreaterThan(0);
    }

    @Test
    @DisplayName("ASCII导入：应正确解析网格数据")
    void testAsciiImport() {
        String content = "3 3 0.01\n100 200 300\n150 250 350\n200 300 400";
        DemTerrainModel model = DemTerrainModel.loadAscii(content);
        assertThat(model).isNotNull();
        assertThat(model.rows).isEqualTo(3);
        assertThat(model.cols).isEqualTo(3);
        assertThat(model.resolution).isCloseTo(0.01, within(0.001));
        assertThat(model.elevations[0][0]).isCloseTo(100, within(0.001));
        assertThat(model.elevations[0][2]).isCloseTo(300, within(0.001));
        assertThat(model.elevations[2][0]).isCloseTo(200, within(0.001));
        assertThat(model.elevations[2][2]).isCloseTo(400, within(0.001));
    }

    @Test
    @DisplayName("ASCII导入：默认分辨率应为1.0")
    void testAsciiImportDefaultResolution() {
        String content = "2 2\n10 20\n30 40";
        DemTerrainModel model = DemTerrainModel.loadAscii(content);
        assertThat(model.resolution).isCloseTo(1.0, within(0.001));
        assertThat(model.elevations[0][0]).isCloseTo(10, within(0.001));
        assertThat(model.elevations[1][1]).isCloseTo(40, within(0.001));
    }
}