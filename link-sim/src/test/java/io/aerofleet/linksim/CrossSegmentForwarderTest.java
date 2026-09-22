package io.aerofleet.linksim;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨网段转发器单测（FR-19 跨网段 NAT 穿透）。
 * <p>
 * 覆盖 CrossSegmentForwarder 的网段注册、桥接节点查找、
 * 跨网段帧转发与 NAT 穿透验证。
 */
@DisplayName("跨网段转发 (FR-19)")
class CrossSegmentForwarderTest {

    /** 构造测试用 MAVLink 帧 */
    private MavlinkFrame createFrame(int sysid, int msgId) {
        return MavlinkFrame.of(sysid, 1, 1, msgId, 0, new byte[]{0x01, 0x02, 0x03});
    }

    // ===== 网段注册 =====

    @Test
    @DisplayName("registerSegmentNode：注册节点网段后可查询")
    void registerSegmentNode() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        forwarder.registerSegmentNode(10, "192.168.1.x");
        forwarder.registerSegmentNode(20, "10.0.0.x");

        assertThat(forwarder.getNodeSegment(10)).isEqualTo("192.168.1.x");
        assertThat(forwarder.getNodeSegment(20)).isEqualTo("10.0.0.x");
        assertThat(forwarder.getNodeSegment(30)).isNull();
    }

    @Test
    @DisplayName("getNodesInSegment：查询网段内所有节点")
    void getNodesInSegment() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        forwarder.registerSegmentNode(10, "192.168.1.x");
        forwarder.registerSegmentNode(11, "192.168.1.x");
        forwarder.registerSegmentNode(20, "10.0.0.x");

        assertThat(forwarder.getNodesInSegment("192.168.1.x")).containsExactlyInAnyOrder(10, 11);
        assertThat(forwarder.getNodesInSegment("10.0.0.x")).containsExactly(20);
        assertThat(forwarder.getNodesInSegment("172.16.0.x")).isEmpty();
    }

    @Test
    @DisplayName("getAllSegments：获取所有已注册网段")
    void getAllSegments() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        forwarder.registerSegmentNode(10, "192.168.1.x");
        forwarder.registerSegmentNode(20, "10.0.0.x");

        assertThat(forwarder.getAllSegments()).containsExactlyInAnyOrder("192.168.1.x", "10.0.0.x");
    }

    // ===== 桥接节点注册 =====

    @Test
    @DisplayName("registerBridgeNode：双网段桥接节点注册成功")
    void registerBridgeNodeDualSegment() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        LinkBridgeNode bridge = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA},
                new String[]{"192.168.1.x", "10.0.0.x"});

        forwarder.registerBridgeNode(bridge);
        assertThat(forwarder.getBridgeNodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("registerBridgeNode：单网段桥接节点不注册（需至少 2 网段）")
    void registerBridgeNodeSingleSegmentNotRegistered() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        LinkBridgeNode bridge = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA},
                new String[]{"192.168.1.x"});

        forwarder.registerBridgeNode(bridge);
        assertThat(forwarder.getBridgeNodeCount()).isEqualTo(0);
    }

    // ===== findBridgeForSegments =====

    @Test
    @DisplayName("findBridgeForSegments：查找连接 192.168.1.x 和 10.0.0.x 的桥接节点")
    void findBridgeForSegments() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        LinkBridgeNode bridge1 = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA},
                new String[]{"192.168.1.x", "10.0.0.x"});
        LinkBridgeNode bridge2 = new LinkBridgeNode(2,
                new LinkType[]{LinkType.WIFI, LinkType.LTE},
                new String[]{"192.168.1.x", "172.16.0.x"});

        forwarder.registerBridgeNode(bridge1);
        forwarder.registerBridgeNode(bridge2);

        var bridges = forwarder.findBridgeForSegments("192.168.1.x", "10.0.0.x");
        assertThat(bridges).hasSize(1);
        assertThat(bridges.get(0).sysid).isEqualTo(1);
    }

    @Test
    @DisplayName("findBridgeForSegments：同网段返回空（无需桥接）")
    void findBridgeForSameSegment() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        LinkBridgeNode bridge = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA},
                new String[]{"192.168.1.x", "10.0.0.x"});
        forwarder.registerBridgeNode(bridge);

        var bridges = forwarder.findBridgeForSegments("192.168.1.x", "192.168.1.x");
        assertThat(bridges).isEmpty();
    }

    @Test
    @DisplayName("findBridgeForSegments：无匹配桥接返回空")
    void findBridgeNoMatch() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        LinkBridgeNode bridge = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA},
                new String[]{"192.168.1.x", "10.0.0.x"});
        forwarder.registerBridgeNode(bridge);

        var bridges = forwarder.findBridgeForSegments("192.168.1.x", "172.16.0.x");
        assertThat(bridges).isEmpty();
    }

    // ===== forwardToSegment =====

    @Test
    @DisplayName("forwardToSegment：跨网段 NAT 穿透成功（FR-19 验收条件）")
    void forwardToSegmentCrossNetwork() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        // 桥接节点同时监听 192.168.1.x 和 10.0.0.x
        LinkBridgeNode bridge = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA},
                new String[]{"192.168.1.x", "10.0.0.x"});
        forwarder.registerBridgeNode(bridge);

        // 节点 A 在 192.168.1.x
        forwarder.registerSegmentNode(10, "192.168.1.x");
        // 节点 B 在 10.0.0.x
        forwarder.registerSegmentNode(20, "10.0.0.x");

        // A 发帧到 B 所在网段
        MavlinkFrame frame = createFrame(10, 100);
        CrossSegmentForwarder.ForwardResult result = forwarder.forwardToSegment(frame, "10.0.0.x");

        assertThat(result).isNotNull();
        assertThat(result.reachable).isTrue();
        assertThat(result.sourceSegment).isEqualTo("192.168.1.x");
        assertThat(result.targetSegment).isEqualTo("10.0.0.x");
        assertThat(result.bridge).isNotNull();
        assertThat(result.bridge.sysid).isEqualTo(1);
    }

    @Test
    @DisplayName("forwardToSegment：同网段直达，无需桥接")
    void forwardToSegmentSameNetwork() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        forwarder.registerSegmentNode(10, "192.168.1.x");

        MavlinkFrame frame = createFrame(10, 100);
        CrossSegmentForwarder.ForwardResult result = forwarder.forwardToSegment(frame, "192.168.1.x");

        assertThat(result).isNotNull();
        assertThat(result.reachable).isTrue();
        assertThat(result.bridge).isNull();
        assertThat(result.pathDescription).contains("同网段直达");
    }

    @Test
    @DisplayName("forwardToSegment：源节点网段未注册返回 null")
    void forwardToSegmentSourceUnregistered() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();

        MavlinkFrame frame = createFrame(10, 100);
        CrossSegmentForwarder.ForwardResult result = forwarder.forwardToSegment(frame, "10.0.0.x");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("forwardToSegment：无桥接节点可达返回 null")
    void forwardToSegmentNoBridge() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        forwarder.registerSegmentNode(10, "192.168.1.x");
        // 无桥接节点注册

        MavlinkFrame frame = createFrame(10, 100);
        CrossSegmentForwarder.ForwardResult result = forwarder.forwardToSegment(frame, "10.0.0.x");

        assertThat(result).isNull();
    }

    // ===== 转发统计 =====

    @Test
    @DisplayName("getForwardCount：跨网段转发 2 次后计数为 2")
    void forwardCountStats() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        LinkBridgeNode bridge = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA},
                new String[]{"192.168.1.x", "10.0.0.x"});
        forwarder.registerBridgeNode(bridge);
        forwarder.registerSegmentNode(10, "192.168.1.x");

        MavlinkFrame frame1 = createFrame(10, 100);
        MavlinkFrame frame2 = createFrame(10, 101);

        forwarder.forwardToSegment(frame1, "10.0.0.x");
        forwarder.forwardToSegment(frame2, "10.0.0.x");

        assertThat(forwarder.getForwardCount("192.168.1.x", "10.0.0.x")).isEqualTo(2);
    }

    // ===== ForwardResult.toString =====

    @Test
    @DisplayName("ForwardResult.toString 包含源/目标网段与桥接节点")
    void forwardResultToString() {
        CrossSegmentForwarder forwarder = new CrossSegmentForwarder();
        LinkBridgeNode bridge = new LinkBridgeNode(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA},
                new String[]{"192.168.1.x", "10.0.0.x"});
        forwarder.registerBridgeNode(bridge);
        forwarder.registerSegmentNode(10, "192.168.1.x");

        MavlinkFrame frame = createFrame(10, 100);
        CrossSegmentForwarder.ForwardResult result = forwarder.forwardToSegment(frame, "10.0.0.x");

        assertThat(result.toString()).contains("192.168.1.x", "10.0.0.x", "bridge=1");
    }

    // ===== LinkQualityMonitor 基本测试 =====

    @Test
    @DisplayName("LinkQualityMonitor：默认质量等级评估")
    void linkQualityMonitorDefaultLevel() {
        LinkQualityMonitor monitor = new LinkQualityMonitor();
        // 默认 RSSI=-65, loss=2%, delay=50ms → GOOD
        LinkQualityMonitor.QualityLevel level = monitor.evaluateLevel(LinkType.WIFI);
        assertThat(level).isEqualTo(LinkQualityMonitor.QualityLevel.GOOD);
    }

    @Test
    @DisplayName("LinkQualityMonitor：EXCELLENT 质量等级")
    void linkQualityMonitorExcellentLevel() {
        LinkQualityMonitor monitor = new LinkQualityMonitor();
        monitor.updateQuality(LinkType.WIFI, -40, 0.001, 10);
        assertThat(monitor.evaluateLevel(LinkType.WIFI))
                .isEqualTo(LinkQualityMonitor.QualityLevel.EXCELLENT);
    }

    @Test
    @DisplayName("LinkQualityMonitor：BROKEN 质量等级")
    void linkQualityMonitorBrokenLevel() {
        LinkQualityMonitor monitor = new LinkQualityMonitor();
        monitor.updateQuality(LinkType.WIFI, -110, 0.60, 1500);
        assertThat(monitor.evaluateLevel(LinkType.WIFI))
                .isEqualTo(LinkQualityMonitor.QualityLevel.BROKEN);
    }

    @Test
    @DisplayName("LinkQualityMonitor：POOR 质量等级触发备用介质切换")
    void linkQualityMonitorNeedsFallback() {
        LinkQualityMonitor monitor = new LinkQualityMonitor();
        monitor.updateQuality(LinkType.WIFI, -95, 0.30, 500);
        assertThat(monitor.needsFallback(LinkType.WIFI)).isTrue();
        assertThat(monitor.getFallbackLink(LinkType.WIFI)).isEqualTo(LinkType.LTE);
    }

    @Test
    @DisplayName("LinkQualityMonitor：recommendLinkSwitch 降级时推荐备用介质")
    void linkQualityMonitorRecommendSwitch() {
        LinkQualityMonitor monitor = new LinkQualityMonitor();
        // WiFi 质量差，LTE 质量好
        monitor.updateQuality(LinkType.WIFI, -95, 0.30, 500);
        monitor.updateQuality(LinkType.LTE, -60, 0.01, 40);

        LinkType recommended = monitor.recommendLinkSwitch(LinkType.WIFI);
        assertThat(recommended).isEqualTo(LinkType.LTE);
    }

    @Test
    @DisplayName("LinkQualityMonitor：recommendLinkSwitch 正常时不切换")
    void linkQualityMonitorNoSwitchWhenGood() {
        LinkQualityMonitor monitor = new LinkQualityMonitor();
        monitor.updateQuality(LinkType.WIFI, -50, 0.001, 10);

        assertThat(monitor.recommendLinkSwitch(LinkType.WIFI)).isEqualTo(LinkType.WIFI);
    }

    @Test
    @DisplayName("LinkQualityMonitor：getAllLevels 返回所有介质等级")
    void linkQualityMonitorGetAllLevels() {
        LinkQualityMonitor monitor = new LinkQualityMonitor();
        var levels = monitor.getAllLevels();
        assertThat(levels).hasSize(4);
        assertThat(levels.keySet()).containsExactlyInAnyOrder(
                LinkType.WIFI, LinkType.LTE, LinkType.LORA, LinkType.SATELLITE);
    }
}