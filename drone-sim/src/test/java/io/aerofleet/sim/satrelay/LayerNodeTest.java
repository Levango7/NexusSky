package io.aerofleet.sim.satrelay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LayerNode 层级节点单测（M7 星-空-地多层级中继，FR-5.2）。
 */
@DisplayName("LayerNode 层级节点 (FR-5.2)")
class LayerNodeTest {

    @Test
    @DisplayName("L1 层合法构造：高度在 100-500m 区间内")
    void validL1Construction() {
        LayerNode node = new LayerNode(10, RelayLayer.L1, 300.0, 5.0);
        assertThat(node.nodeId()).isEqualTo(10);
        assertThat(node.layer()).isEqualTo(RelayLayer.L1);
        assertThat(node.altitudeM()).isEqualTo(300.0);
        assertThat(node.coverageRadiusKm()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("L2 层合法构造：高度在 18000-20000m 区间内")
    void validL2Construction() {
        LayerNode node = new LayerNode(20, RelayLayer.L2, 19_000.0, 200.0);
        assertThat(node.layer()).isEqualTo(RelayLayer.L2);
        assertThat(node.isEndpoint()).isFalse();
    }

    @Test
    @DisplayName("L3 层合法构造：高度在 300000-1200000m 区间内")
    void validL3Construction() {
        LayerNode node = new LayerNode(30, RelayLayer.L3, 550_000.0, 1000.0);
        assertThat(node.layer()).isEqualTo(RelayLayer.L3);
        assertThat(node.isEndpoint()).isFalse();
    }

    @Test
    @DisplayName("L0 端点层高度不校验")
    void l0EndpointNoAltitudeCheck() {
        LayerNode node = new LayerNode(1, RelayLayer.L0, 0.0, 1.0);
        assertThat(node.layer()).isEqualTo(RelayLayer.L0);
        assertThat(node.isEndpoint()).isTrue();
    }

    @Test
    @DisplayName("L4 端点层高度不校验")
    void l4EndpointNoAltitudeCheck() {
        LayerNode node = new LayerNode(99, RelayLayer.L4, 999_999.0, 1.0);
        assertThat(node.layer()).isEqualTo(RelayLayer.L4);
        assertThat(node.isEndpoint()).isTrue();
    }

    @Test
    @DisplayName("layer 为 null 拒绝")
    void nullLayerRejected() {
        assertThatThrownBy(() -> new LayerNode(1, null, 100.0, 5.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layer must not be null");
    }

    @Test
    @DisplayName("L1 高度越界拒绝：低于 100m")
    void l1AltitudeTooLow() {
        assertThatThrownBy(() -> new LayerNode(10, RelayLayer.L1, 50.0, 5.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in layer");
    }

    @Test
    @DisplayName("L2 高度越界拒绝：高于 20000m")
    void l2AltitudeTooHigh() {
        assertThatThrownBy(() -> new LayerNode(20, RelayLayer.L2, 25_000.0, 200.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("覆盖半径 <=0 拒绝")
    void invalidCoverageRadius() {
        assertThatThrownBy(() -> new LayerNode(10, RelayLayer.L1, 300.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverageRadiusKm must be > 0");
        assertThatThrownBy(() -> new LayerNode(10, RelayLayer.L1, 300.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reachable 默认 false，setReachable 可切换")
    void reachableDefaultAndSet() {
        LayerNode node = new LayerNode(10, RelayLayer.L1, 300.0, 5.0);
        assertThat(node.isReachable()).isFalse();

        node.setReachable(true);
        assertThat(node.isReachable()).isTrue();

        node.setReachable(false);
        assertThat(node.isReachable()).isFalse();
    }

    @Test
    @DisplayName("isEndpoint 语义：L0/L4 为端点，L1/L2/L3 非端点")
    void isEndpointSemantics() {
        assertThat(new LayerNode(1, RelayLayer.L0, 0, 1).isEndpoint()).isTrue();
        assertThat(new LayerNode(4, RelayLayer.L4, 0, 1).isEndpoint()).isTrue();
        assertThat(new LayerNode(1, RelayLayer.L1, 200, 5).isEndpoint()).isFalse();
        assertThat(new LayerNode(2, RelayLayer.L2, 19_000, 200).isEndpoint()).isFalse();
        assertThat(new LayerNode(3, RelayLayer.L3, 550_000, 1000).isEndpoint()).isFalse();
    }

    @Test
    @DisplayName("toString 包含关键字段信息")
    void toStringContainsFields() {
        LayerNode node = new LayerNode(42, RelayLayer.L2, 19_500.0, 200.0);
        String s = node.toString();
        assertThat(s).contains("id=42").contains("L2").contains("alt=19500.0");
    }
}