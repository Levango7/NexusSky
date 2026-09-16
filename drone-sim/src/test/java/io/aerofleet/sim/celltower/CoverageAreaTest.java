package io.aerofleet.sim.celltower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CoverageArea 值类单测（M6 移动基站载荷抽象，FR-COV-01~03）。
 */
@DisplayName("CoverageArea (FR-COV-01~03)")
class CoverageAreaTest {

    private static final int CENTER_LAT = 399000000;
    private static final int CENTER_LON = 1163000000;

    @Test
    @DisplayName("circle 工厂创建圆形覆盖")
    void circleFactory() {
        CoverageArea area = CoverageArea.circle(CENTER_LAT, CENTER_LON, 2000, false);
        assertThat(area.shape).isEqualTo(CoverageArea.Shape.CIRCLE);
        assertThat(area.radiusM).isEqualTo(2000);
        assertThat(area.occluded).isFalse();
        assertThat(area.beamWidthDeg).isEqualTo(360);
    }

    @Test
    @DisplayName("contains 判定圆内点为 true")
    void containsInside() {
        CoverageArea area = CoverageArea.circle(CENTER_LAT, CENTER_LON, 2000, false);
        // 偏移约 100m（~0.0009 deg）
        int nearbyLat = CENTER_LAT + 900;
        assertThat(area.contains(nearbyLat, CENTER_LON)).isTrue();
    }

    @Test
    @DisplayName("contains 判定圆外点为 false")
    void containsOutside() {
        CoverageArea area = CoverageArea.circle(CENTER_LAT, CENTER_LON, 1000, false);
        // 偏移约 10km（~0.09 deg）
        int farLat = CENTER_LAT + 900000;
        assertThat(area.contains(farLat, CENTER_LON)).isFalse();
    }

    @Test
    @DisplayName("contains 判定圆心为 true")
    void containsCenter() {
        CoverageArea area = CoverageArea.circle(CENTER_LAT, CENTER_LON, 500, false);
        assertThat(area.contains(CENTER_LAT, CENTER_LON)).isTrue();
    }

    @Test
    @DisplayName("sector 工厂创建扇形覆盖")
    void sectorFactory() {
        CoverageArea area = CoverageArea.sector(CENTER_LAT, CENTER_LON, 3000, 90, 60, false);
        assertThat(area.shape).isEqualTo(CoverageArea.Shape.SECTOR);
        assertThat(area.azimuthDeg).isEqualTo(90);
        assertThat(area.beamWidthDeg).isEqualTo(60);
    }
}