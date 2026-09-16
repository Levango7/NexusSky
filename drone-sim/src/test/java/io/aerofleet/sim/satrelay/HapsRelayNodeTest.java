package io.aerofleet.sim.satrelay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HapsRelayNode HAPS 中继节点单测（M7 星-空-地多层级中继，FR-5.2）。
 */
@DisplayName("HapsRelayNode HAPS 中继节点 (FR-5.2)")
class HapsRelayNodeTest {

    @Test
    @DisplayName("合法参数构造成功")
    void validConstruction() {
        HapsRelayNode haps = new HapsRelayNode(200, 22.0, 114.0, 19_000.0, 200.0);
        assertThat(haps.nodeId()).isEqualTo(200);
        assertThat(haps.latDeg()).isEqualTo(22.0);
        assertThat(haps.lonDeg()).isEqualTo(114.0);
        assertThat(haps.altitudeM()).isEqualTo(19_000.0);
        assertThat(haps.coverageRadiusKm()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("atDefault 工厂创建默认 HAPS")
    void atDefaultFactory() {
        HapsRelayNode haps = HapsRelayNode.atDefault(201, 22.0, 114.0);
        assertThat(haps.altitudeM()).isEqualTo(19_000.0);
        assertThat(haps.coverageRadiusKm()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("nodeId 非正数拒绝")
    void invalidNodeId() {
        assertThatThrownBy(() -> new HapsRelayNode(0, 22.0, 114.0, 19_000.0, 200.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("高度越界拒绝：<18000 或 >20000")
    void altitudeOutOfRange() {
        assertThatThrownBy(() -> new HapsRelayNode(200, 22.0, 114.0, 17_000.0, 200.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HapsRelayNode(200, 22.0, 114.0, 21_000.0, 200.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("覆盖半径 <=0 拒绝")
    void invalidCoverageRadius() {
        assertThatThrownBy(() -> new HapsRelayNode(200, 22.0, 114.0, 19_000.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("covers 判定：同位置覆盖")
    void coversSamePosition() {
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.0, 114.0);
        assertThat(haps.covers(22.0, 114.0)).isTrue();
    }

    @Test
    @DisplayName("covers 判定：近距离覆盖")
    void coversNearby() {
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.0, 114.0);
        // 0.1 度 ≈ 11km，在 200km 覆盖内
        assertThat(haps.covers(22.1, 114.1)).isTrue();
    }

    @Test
    @DisplayName("covers 判定：远距离不覆盖")
    void coversFaraway() {
        HapsRelayNode haps = HapsRelayNode.atDefault(200, 22.0, 114.0);
        // 5 度 ≈ 500km，超出 200km 覆盖
        assertThat(haps.covers(27.0, 119.0)).isFalse();
    }

    @Test
    @DisplayName("大圆距离计算正确")
    void greatCircleDistance() {
        double dist = HapsRelayNode.greatCircleDistanceKm(0.0, 0.0, 0.0, 0.0);
        assertThat(dist).isZero();

        // 同纬度 1 度经度差在赤道 ≈ 111km
        double dist1 = HapsRelayNode.greatCircleDistanceKm(0.0, 0.0, 0.0, 1.0);
        assertThat(dist1).isBetween(100.0, 120.0);
    }
}