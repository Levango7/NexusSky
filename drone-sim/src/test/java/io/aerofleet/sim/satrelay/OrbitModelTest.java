package io.aerofleet.sim.satrelay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * OrbitModel 轨道模型单测（M7 星-空-地多层级中继，FR-5.1）。
 */
@DisplayName("OrbitModel 轨道模型 (FR-5.1)")
class OrbitModelTest {

    @Test
    @DisplayName("轨道位置在合理范围内：高度 550km 时位置 ≈ 6921km")
    void positionWithinRange() {
        double[] pos = OrbitModel.positionAt(550.0, 53.0, 0.0, 0.0, 0L);
        double r = Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]);
        assertThat(r).isCloseTo(OrbitModel.EARTH_R_KM + 550.0, within(1.0));
    }

    @Test
    @DisplayName("平近点角演化：时间推进后平近点角增加")
    void meanAnomalyEvolution() {
        double m0 = OrbitModel.evolvedMeanAnomaly(0.0, 550.0, 0L);
        double m1 = OrbitModel.evolvedMeanAnomaly(0.0, 550.0, 60_000L);
        assertThat(m1).isGreaterThan(m0);
    }

    @Test
    @DisplayName("平近点角归一化到 [0, 360)")
    void meanAnomalyNormalized() {
        double m = OrbitModel.evolvedMeanAnomaly(350.0, 550.0, 600_000L);
        assertThat(m).isGreaterThanOrEqualTo(0.0);
        assertThat(m).isLessThan(360.0);
    }

    @Test
    @DisplayName("确定性：相同输入相同输出")
    void deterministic() {
        double[] pos1 = OrbitModel.positionAt(550.0, 53.0, 45.0, 90.0, 123456789L);
        double[] pos2 = OrbitModel.positionAt(550.0, 53.0, 45.0, 90.0, 123456789L);
        assertThat(pos1).containsExactly(pos2);
    }

    @Test
    @DisplayName("仰角计算：卫星正上方时仰角 ≈ 90°")
    void elevationAtZenith() {
        // 构造卫星在地面点正上方的 ECI 坐标
        double lat = 22.0;
        double lon = 114.0;
        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);
        double r = OrbitModel.EARTH_R_KM + 550.0;
        double[] satPos = {
                r * Math.cos(latRad) * Math.cos(lonRad),
                r * Math.cos(latRad) * Math.sin(lonRad),
                r * Math.sin(latRad)
        };
        double[] elAz = OrbitModel.elevationAzimuth(satPos, lat, lon);
        assertThat(elAz[0]).isCloseTo(90.0, within(0.1));
    }

    @Test
    @DisplayName("仰角计算：卫星在水平面时仰角 ≈ 0°")
    void elevationAtHorizon() {
        // 构造卫星在地面点水平方向的 ECI 坐标
        double lat = 0.0;
        double lon = 0.0;
        // 卫星在赤道上，位于地球切线方向（地平线），仰角应接近 0
        double r = OrbitModel.EARTH_R_KM + 550.0;
        double satX = OrbitModel.EARTH_R_KM;
        double satY = Math.sqrt(r * r - OrbitModel.EARTH_R_KM * OrbitModel.EARTH_R_KM);
        double[] satPos = {satX, satY, 0};
        double[] elAz = OrbitModel.elevationAzimuth(satPos, lat, lon);
        assertThat(elAz[0]).isCloseTo(0.0, within(1.0));
    }

    @Test
    @DisplayName("方位角范围 [0, 360)")
    void azimuthRange() {
        double[] pos = OrbitModel.positionAt(550.0, 53.0, 100.0, 200.0, 1000000L);
        double[] elAz = OrbitModel.elevationAzimuth(pos, 22.0, 114.0);
        assertThat(elAz[1]).isGreaterThanOrEqualTo(0.0);
        assertThat(elAz[1]).isLessThan(360.0);
    }

    @Test
    @DisplayName("奇异处理：卫星与地面点重合时仰角 = 90°")
    void singularHandling() {
        double lat = 22.0;
        double lon = 114.0;
        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);
        double[] groundPos = {
                OrbitModel.EARTH_R_KM * Math.cos(latRad) * Math.cos(lonRad),
                OrbitModel.EARTH_R_KM * Math.cos(latRad) * Math.sin(lonRad),
                OrbitModel.EARTH_R_KM * Math.sin(latRad)
        };
        double[] elAz = OrbitModel.elevationAzimuth(groundPos, lat, lon);
        assertThat(elAz[0]).isEqualTo(90.0);
    }
}