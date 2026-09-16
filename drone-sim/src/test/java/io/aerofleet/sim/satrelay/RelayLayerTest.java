package io.aerofleet.sim.satrelay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RelayLayer 枚举单测（M7 星-空-地多层级中继，FR-5.2）。
 */
@DisplayName("RelayLayer 层级枚举 (FR-5.2)")
class RelayLayerTest {

    @Test
    @DisplayName("层级枚举完备性：恰好 5 个层级 L0-L4")
    void enumCompleteness() {
        assertThat(RelayLayer.values()).hasSize(5);
        assertThat(RelayLayer.fromNumber(0)).isEqualTo(RelayLayer.L0);
        assertThat(RelayLayer.fromNumber(1)).isEqualTo(RelayLayer.L1);
        assertThat(RelayLayer.fromNumber(2)).isEqualTo(RelayLayer.L2);
        assertThat(RelayLayer.fromNumber(3)).isEqualTo(RelayLayer.L3);
        assertThat(RelayLayer.fromNumber(4)).isEqualTo(RelayLayer.L4);
    }

    @Test
    @DisplayName("层级高度区间不重叠：15km 不属于任何层级")
    void altitudeGapNotClassified() {
        assertThat(RelayLayer.fromAltitude(15_000.0)).isNull();
    }

    @Test
    @DisplayName("L1 高度 100-500m 判定正确")
    void l1AltitudeRange() {
        assertThat(RelayLayer.fromAltitude(100.0)).isEqualTo(RelayLayer.L1);
        assertThat(RelayLayer.fromAltitude(300.0)).isEqualTo(RelayLayer.L1);
        assertThat(RelayLayer.fromAltitude(500.0)).isEqualTo(RelayLayer.L1);
        assertThat(RelayLayer.fromAltitude(99.0)).isNull();
        assertThat(RelayLayer.fromAltitude(501.0)).isNull();
    }

    @Test
    @DisplayName("L2 高度 18-20km 判定正确")
    void l2AltitudeRange() {
        assertThat(RelayLayer.fromAltitude(18_000.0)).isEqualTo(RelayLayer.L2);
        assertThat(RelayLayer.fromAltitude(19_000.0)).isEqualTo(RelayLayer.L2);
        assertThat(RelayLayer.fromAltitude(20_000.0)).isEqualTo(RelayLayer.L2);
    }

    @Test
    @DisplayName("L3 高度 300-1200km 判定正确")
    void l3AltitudeRange() {
        assertThat(RelayLayer.fromAltitude(300_000.0)).isEqualTo(RelayLayer.L3);
        assertThat(RelayLayer.fromAltitude(550_000.0)).isEqualTo(RelayLayer.L3);
        assertThat(RelayLayer.fromAltitude(1_200_000.0)).isEqualTo(RelayLayer.L3);
    }

    @Test
    @DisplayName("层级覆盖递增：L1 < L2 < L3")
    void coverageRadiusIncreasing() {
        assertThat(RelayLayer.L1.coverageRadiusKm()).isLessThan(RelayLayer.L2.coverageRadiusKm());
        assertThat(RelayLayer.L2.coverageRadiusKm()).isLessThan(RelayLayer.L3.coverageRadiusKm());
    }

    @Test
    @DisplayName("L0/L4 为端点层，不承担中继")
    void endpointLayers() {
        assertThat(RelayLayer.L0.isEndpoint()).isTrue();
        assertThat(RelayLayer.L4.isEndpoint()).isTrue();
        assertThat(RelayLayer.L0.isRelay()).isFalse();
        assertThat(RelayLayer.L4.isRelay()).isFalse();
        assertThat(RelayLayer.L1.isRelay()).isTrue();
        assertThat(RelayLayer.L2.isRelay()).isTrue();
        assertThat(RelayLayer.L3.isRelay()).isTrue();
    }

    @Test
    @DisplayName("非法层级编号返回 null（FR-5.2.1.7）")
    void invalidLayerNumberReturnsNull() {
        assertThat(RelayLayer.fromNumber(5)).isNull();
        assertThat(RelayLayer.fromNumber(-1)).isNull();
        assertThat(RelayLayer.fromNumber(99)).isNull();
    }
}