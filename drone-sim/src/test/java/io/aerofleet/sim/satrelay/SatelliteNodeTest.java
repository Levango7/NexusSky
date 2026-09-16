package io.aerofleet.sim.satrelay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SatelliteNode 卫星节点单测（M7 星-空-地多层级中继，FR-5.1）。
 */
@DisplayName("SatelliteNode 卫星节点 (FR-5.1)")
class SatelliteNodeTest {

    @Test
    @DisplayName("合法参数构造成功")
    void validConstruction() {
        SatelliteNode sat = new SatelliteNode(1, 550.0, 53.0, 45.0, 90.0);
        assertThat(sat.satId()).isEqualTo(1);
        assertThat(sat.orbitAltitudeKm()).isEqualTo(550.0);
        assertThat(sat.inclinationDeg()).isEqualTo(53.0);
        assertThat(sat.raanDeg()).isEqualTo(45.0);
        assertThat(sat.initialMeanAnomalyDeg()).isEqualTo(90.0);
    }

    @Test
    @DisplayName("satId 非正数拒绝（FR-5.1.1.1）")
    void invalidSatId() {
        assertThatThrownBy(() -> new SatelliteNode(0, 550.0, 53.0, 45.0, 90.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SatelliteNode(-1, 550.0, 53.0, 45.0, 90.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("轨道高度越界拒绝（FR-5.1.1.2）：250km 和 1300km")
    void altitudeOutOfRange() {
        assertThatThrownBy(() -> new SatelliteNode(1, 250.0, 53.0, 45.0, 90.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orbitAltitudeKm");
        assertThatThrownBy(() -> new SatelliteNode(1, 1300.0, 53.0, 45.0, 90.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orbitAltitudeKm");
    }

    @Test
    @DisplayName("倾角越界拒绝（FR-5.1.1.8）：200°")
    void inclinationOutOfRange() {
        assertThatThrownBy(() -> new SatelliteNode(1, 550.0, 200.0, 45.0, 90.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inclinationDeg");
        assertThatThrownBy(() -> new SatelliteNode(1, 550.0, -10.0, 45.0, 90.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RAAN 越界拒绝")
    void raanOutOfRange() {
        assertThatThrownBy(() -> new SatelliteNode(1, 550.0, 53.0, 360.0, 90.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SatelliteNode(1, 550.0, 53.0, -1.0, 90.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("平近点角越界拒绝")
    void meanAnomalyOutOfRange() {
        assertThatThrownBy(() -> new SatelliteNode(1, 550.0, 53.0, 45.0, 360.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SatelliteNode(1, 550.0, 53.0, 45.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("updatePosition 更新平近点角与 ECI 坐标")
    void updatePosition() {
        SatelliteNode sat = new SatelliteNode(1, 550.0, 53.0, 0.0, 0.0);
        sat.updatePosition(0L);
        double m0 = sat.currentMeanAnomalyDeg();
        sat.updatePosition(60_000L);
        double m1 = sat.currentMeanAnomalyDeg();
        assertThat(m1).isGreaterThan(m0);
        assertThat(sat.eciPositionKm()).hasSize(3);
    }

    @Test
    @DisplayName("updateVisibility 更新可见性状态")
    void updateVisibility() {
        SatelliteNode sat = new SatelliteNode(1, 550.0, 53.0, 0.0, 0.0);
        assertThat(sat.isVisible()).isFalse();

        sat.updateVisibility(true, 45.0, 180.0);
        assertThat(sat.isVisible()).isTrue();
        assertThat(sat.elevationDeg()).isEqualTo(45.0);
        assertThat(sat.azimuthDeg()).isEqualTo(180.0);

        sat.updateVisibility(false, 0.0, 90.0);
        assertThat(sat.isVisible()).isFalse();
        assertThat(sat.elevationDeg()).isEqualTo(0.0);
    }
}