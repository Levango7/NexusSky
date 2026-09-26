package io.aerofleet.linksim;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 链路桥接节点单测（FR-01 异构链路桥接）。
 * <p>
 * 覆盖 LinkBridgeNode 的跨介质帧转发、延迟计算、统计记录与网段监听。
 */
@DisplayName("链路桥接节点 (FR-01)")
class LinkBridgeNodeTest {

    /** 构造测试用 MAVLink 帧 */
    private MavlinkFrame createFrame(int sysid, int msgId) {
        return MavlinkFrame.of(sysid, 1, 1, msgId, 0, new byte[]{0x01, 0x02, 0x03});
    }

    // ===== 构造与校验 =====

    @Test
    @DisplayName("构造桥接节点：至少 2 种介质，否则抛异常")
    void constructRequiresAtLeastTwoLinks() {
        assertThatThrownBy(() -> new LinkBridgeNode(1, new LinkType[]{LinkType.WIFI}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要支持 2 种通信介质");
    }

    @Test
    @DisplayName("构造桥接节点：支持介质集合不可变")
    void supportedLinksIsImmutable() {
        LinkBridgeNode node = new LinkBridgeNode(1, new LinkType[]{LinkType.WIFI, LinkType.LORA});
        assertThat(node.supportedLinks).containsExactlyInAnyOrder(LinkType.WIFI, LinkType.LORA);
        assertThatThrownBy(() -> node.supportedLinks.add(LinkType.LTE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("构造桥接节点：带网段信息")
    void constructWithSegments() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA},
                new String[]{"192.168.1.x", "10.0.0.x"});
        assertThat(node.listenedSegments).containsExactlyInAnyOrder("192.168.1.x", "10.0.0.x");
        assertThat(node.listensToSegment("192.168.1.x")).isTrue();
        assertThat(node.listensToSegment("172.16.0.x")).isFalse();
    }

    // ===== bridgeFrame 跨介质转发 =====

    @Test
    @DisplayName("bridgeFrame：WiFi→LoRa 转发成功，额外延迟 100ms（DFX 6.1.4）")
    void bridgeFrameWifiToLora() {
        LinkBridgeNode node = new LinkBridgeNode(1, new LinkType[]{LinkType.WIFI, LinkType.LORA});
        MavlinkFrame frame = createFrame(10, 100);
        LinkBridgeNode.BridgedFrame result = node.bridgeFrame(frame, LinkType.WIFI, LinkType.LORA);

        assertThat(result).isNotNull();
        assertThat(result.fromType).isEqualTo(LinkType.WIFI);
        assertThat(result.toType).isEqualTo(LinkType.LORA);
        assertThat(result.extraDelayMs).isEqualTo(100);
        assertThat(result.bridgeSysid).isEqualTo(1);
        assertThat(result.frame).isSameAs(frame);
    }

    @Test
    @DisplayName("bridgeFrame：WiFi→Satellite 转发成功，额外延迟 200ms（DFX 6.1.4）")
    void bridgeFrameWifiToSatellite() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.SATELLITE});
        MavlinkFrame frame = createFrame(10, 100);
        LinkBridgeNode.BridgedFrame result = node.bridgeFrame(frame, LinkType.WIFI, LinkType.SATELLITE);

        assertThat(result).isNotNull();
        assertThat(result.extraDelayMs).isEqualTo(200);
    }

    @Test
    @DisplayName("bridgeFrame：LoRa→WiFi 反向转发，延迟对称 100ms")
    void bridgeFrameLoraToWifi() {
        LinkBridgeNode node = new LinkBridgeNode(1, new LinkType[]{LinkType.WIFI, LinkType.LORA});
        MavlinkFrame frame = createFrame(10, 100);
        LinkBridgeNode.BridgedFrame result = node.bridgeFrame(frame, LinkType.LORA, LinkType.WIFI);

        assertThat(result).isNotNull();
        assertThat(result.extraDelayMs).isEqualTo(100);
    }

    @Test
    @DisplayName("bridgeFrame：Satellite→WiFi 反向转发，延迟对称 200ms")
    void bridgeFrameSatelliteToWifi() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.SATELLITE});
        MavlinkFrame frame = createFrame(10, 100);
        LinkBridgeNode.BridgedFrame result = node.bridgeFrame(frame, LinkType.SATELLITE, LinkType.WIFI);

        assertThat(result).isNotNull();
        assertThat(result.extraDelayMs).isEqualTo(200);
    }

    @Test
    @DisplayName("bridgeFrame：同介质转发返回 null（无需桥接）")
    void bridgeFrameSameMediumReturnsNull() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA});
        MavlinkFrame frame = createFrame(10, 100);
        LinkBridgeNode.BridgedFrame result = node.bridgeFrame(frame, LinkType.WIFI, LinkType.WIFI);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("bridgeFrame：源介质不支持返回 null")
    void bridgeFrameUnsupportedFromReturnsNull() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA});
        MavlinkFrame frame = createFrame(10, 100);
        LinkBridgeNode.BridgedFrame result = node.bridgeFrame(frame, LinkType.LTE, LinkType.WIFI);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("bridgeFrame：目标介质不支持返回 null")
    void bridgeFrameUnsupportedToReturnsNull() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA});
        MavlinkFrame frame = createFrame(10, 100);
        LinkBridgeNode.BridgedFrame result = node.bridgeFrame(frame, LinkType.WIFI, LinkType.LTE);

        assertThat(result).isNull();
    }

    // ===== 统计 =====

    @Test
    @DisplayName("转发统计：WiFi→LoRa 转发 3 次后计数为 3")
    void forwardStatsCount() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA, LinkType.LTE});
        MavlinkFrame frame = createFrame(10, 100);

        node.bridgeFrame(frame, LinkType.WIFI, LinkType.LORA);
        node.bridgeFrame(frame, LinkType.WIFI, LinkType.LORA);
        node.bridgeFrame(frame, LinkType.WIFI, LinkType.LORA);

        assertThat(node.getForwardCount(LinkType.WIFI, LinkType.LORA)).isEqualTo(3);
        assertThat(node.getForwardCount(LinkType.WIFI, LinkType.LTE)).isEqualTo(0);
    }

    @Test
    @DisplayName("延迟统计：WiFi→LoRa 3 次 × 100ms = 300ms 累计延迟")
    void delayStatsAccumulated() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA});
        MavlinkFrame frame = createFrame(10, 100);

        node.bridgeFrame(frame, LinkType.WIFI, LinkType.LORA);
        node.bridgeFrame(frame, LinkType.WIFI, LinkType.LORA);
        node.bridgeFrame(frame, LinkType.WIFI, LinkType.LORA);

        assertThat(node.getAccumulatedDelay(LinkType.WIFI, LinkType.LORA)).isEqualTo(300);
    }

    // ===== 转发队列 =====

    @Test
    @DisplayName("pollBridgedFrame：按入队顺序取出桥接帧")
    void pollBridgedFrameOrder() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA, LinkType.LTE});
        MavlinkFrame frame1 = createFrame(10, 100);
        MavlinkFrame frame2 = createFrame(11, 101);

        node.bridgeFrame(frame1, LinkType.WIFI, LinkType.LORA);
        node.bridgeFrame(frame2, LinkType.WIFI, LinkType.LTE);

        LinkBridgeNode.BridgedFrame first = node.pollBridgedFrame();
        LinkBridgeNode.BridgedFrame second = node.pollBridgedFrame();

        assertThat(first).isNotNull();
        assertThat(first.toType).isEqualTo(LinkType.LORA);
        assertThat(second).isNotNull();
        assertThat(second.toType).isEqualTo(LinkType.LTE);
        assertThat(node.pollBridgedFrame()).isNull();
    }

    // ===== supportsBridge & getBridgePaths =====

    @Test
    @DisplayName("supportsBridge：支持 WiFi→LoRa，不支持 WiFi→Satellite")
    void supportsBridgeCheck() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA});
        assertThat(node.supportsBridge(LinkType.WIFI, LinkType.LORA)).isTrue();
        assertThat(node.supportsBridge(LinkType.LORA, LinkType.WIFI)).isTrue();
        assertThat(node.supportsBridge(LinkType.WIFI, LinkType.SATELLITE)).isFalse();
        assertThat(node.supportsBridge(LinkType.WIFI, LinkType.WIFI)).isFalse();
    }

    @Test
    @DisplayName("getBridgePaths：3 种介质产生 6 条转换路径")
    void getBridgePathsCount() {
        LinkBridgeNode node = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA, LinkType.LTE});
        var paths = node.getBridgePaths();
        // 3 种介质 → 3×2=6 条路径
        assertThat(paths).hasSize(6);
    }

    // ===== LinkType.crossMediumDelay =====

    @Test
    @DisplayName("crossMediumDelay：WiFi→LoRa=100ms, WiFi→Satellite=200ms")
    void crossMediumDelaySpecValues() {
        assertThat(LinkType.crossMediumDelay(LinkType.WIFI, LinkType.LORA)).isEqualTo(100);
        assertThat(LinkType.crossMediumDelay(LinkType.WIFI, LinkType.SATELLITE)).isEqualTo(200);
        assertThat(LinkType.crossMediumDelay(LinkType.LORA, LinkType.WIFI)).isEqualTo(100);
        assertThat(LinkType.crossMediumDelay(LinkType.SATELLITE, LinkType.WIFI)).isEqualTo(200);
    }

    @Test
    @DisplayName("crossMediumDelay：同介质延迟为 0")
    void crossMediumDelaySameType() {
        assertThat(LinkType.crossMediumDelay(LinkType.WIFI, LinkType.WIFI)).isEqualTo(0);
        assertThat(LinkType.crossMediumDelay(LinkType.LORA, LinkType.LORA)).isEqualTo(0);
    }

    @Test
    @DisplayName("crossMediumDelay：其他组合至少 50ms")
    void crossMediumDelayMinValue() {
        long delay = LinkType.crossMediumDelay(LinkType.LORA, LinkType.SATELLITE);
        assertThat(delay).isGreaterThanOrEqualTo(50);
    }

    // ===== LinkType 特性 =====

    @Test
    @DisplayName("LinkType 特性：WiFi 高带宽短覆盖，LoRa 低带宽远距离")
    void linkTypeCharacteristics() {
        assertThat(LinkType.WIFI.bandwidthBytesPerSec).isGreaterThan(LinkType.LORA.bandwidthBytesPerSec);
        assertThat(LinkType.LORA.coverageRangeM).isGreaterThan(LinkType.WIFI.coverageRangeM);
        assertThat(LinkType.LORA.powerConsumptionMw).isLessThan(LinkType.WIFI.powerConsumptionMw);
    }

    @Test
    @DisplayName("isBridgeCapable：WiFi 和 LTE 可作桥接，LoRa 和 Satellite 不适合")
    void isBridgeCapable() {
        assertThat(LinkType.WIFI.isBridgeCapable()).isTrue();
        assertThat(LinkType.LTE.isBridgeCapable()).isTrue();
        assertThat(LinkType.LORA.isBridgeCapable()).isFalse();
        assertThat(LinkType.SATELLITE.isBridgeCapable()).isFalse();
    }
}