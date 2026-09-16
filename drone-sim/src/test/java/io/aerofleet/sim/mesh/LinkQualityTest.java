package io.aerofleet.sim.mesh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LinkQuality 枚举单测（M5 应急 mesh，FR-12）。
 * <p>
 * 覆盖 fromRssi 各级阈值边界、越界值、fromCode 反查、color/code 值。
 */
@DisplayName("LinkQuality RSSI 分级 (FR-12)")
class LinkQualityTest {

    @Test
    @DisplayName("RSSI > -50 → EXCELLENT")
    void fromRssiExcellent() {
        assertThat(LinkQuality.fromRssi(-40)).isEqualTo(LinkQuality.EXCELLENT);
        assertThat(LinkQuality.fromRssi(-1)).isEqualTo(LinkQuality.EXCELLENT);
        assertThat(LinkQuality.fromRssi(0)).isEqualTo(LinkQuality.EXCELLENT);
    }

    @Test
    @DisplayName("-50 ≥ RSSI > -70 → GOOD")
    void fromRssiGood() {
        assertThat(LinkQuality.fromRssi(-50)).isEqualTo(LinkQuality.GOOD);
        assertThat(LinkQuality.fromRssi(-60)).isEqualTo(LinkQuality.GOOD);
        assertThat(LinkQuality.fromRssi(-69)).isEqualTo(LinkQuality.GOOD);
    }

    @Test
    @DisplayName("-70 ≥ RSSI > -85 → FAIR")
    void fromRssiFair() {
        assertThat(LinkQuality.fromRssi(-70)).isEqualTo(LinkQuality.FAIR);
        assertThat(LinkQuality.fromRssi(-80)).isEqualTo(LinkQuality.FAIR);
        assertThat(LinkQuality.fromRssi(-84)).isEqualTo(LinkQuality.FAIR);
    }

    @Test
    @DisplayName("-85 ≥ RSSI ≥ -120 → POOR")
    void fromRssiPoor() {
        assertThat(LinkQuality.fromRssi(-85)).isEqualTo(LinkQuality.POOR);
        assertThat(LinkQuality.fromRssi(-100)).isEqualTo(LinkQuality.POOR);
        assertThat(LinkQuality.fromRssi(-120)).isEqualTo(LinkQuality.POOR);
    }

    @Test
    @DisplayName("越界值（>0 或 <-120）按 POOR 处理")
    void fromRssiOutOfRange() {
        assertThat(LinkQuality.fromRssi(1)).isEqualTo(LinkQuality.POOR);
        assertThat(LinkQuality.fromRssi(50)).isEqualTo(LinkQuality.POOR);
        assertThat(LinkQuality.fromRssi(-121)).isEqualTo(LinkQuality.POOR);
        assertThat(LinkQuality.fromRssi(-200)).isEqualTo(LinkQuality.POOR);
    }

    @Test
    @DisplayName("fromCode 序数反查：0→EXCELLENT 1→GOOD 2→FAIR 其他→POOR")
    void fromCodeMapping() {
        assertThat(LinkQuality.fromCode(0)).isEqualTo(LinkQuality.EXCELLENT);
        assertThat(LinkQuality.fromCode(1)).isEqualTo(LinkQuality.GOOD);
        assertThat(LinkQuality.fromCode(2)).isEqualTo(LinkQuality.FAIR);
        assertThat(LinkQuality.fromCode(3)).isEqualTo(LinkQuality.POOR);
        assertThat(LinkQuality.fromCode(99)).isEqualTo(LinkQuality.POOR);
    }

    @Test
    @DisplayName("code() 与 color() 返回稳定值")
    void colorAndCodeValues() {
        assertThat(LinkQuality.EXCELLENT.code()).isEqualTo(0);
        assertThat(LinkQuality.GOOD.code()).isEqualTo(1);
        assertThat(LinkQuality.FAIR.code()).isEqualTo(2);
        assertThat(LinkQuality.POOR.code()).isEqualTo(3);
        assertThat(LinkQuality.EXCELLENT.color()).isEqualTo("#2ECC71");
        assertThat(LinkQuality.GOOD.color()).isEqualTo("#3498DB");
        assertThat(LinkQuality.FAIR.color()).isEqualTo("#F39C12");
        assertThat(LinkQuality.POOR.color()).isEqualTo("#E74C3C");
    }

    @Test
    @DisplayName("阈值边界 -50/-70/-85 精确归属")
    void boundaryValuesExact() {
        // -50 恰好归 GOOD（> -50 才 EXCELLENT）
        assertThat(LinkQuality.fromRssi(-50)).isEqualTo(LinkQuality.GOOD);
        // -70 恰好归 FAIR
        assertThat(LinkQuality.fromRssi(-70)).isEqualTo(LinkQuality.FAIR);
        // -85 恰好归 POOR
        assertThat(LinkQuality.fromRssi(-85)).isEqualTo(LinkQuality.POOR);
    }
}