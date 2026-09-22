package io.aerofleet.linksim;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异构链路管理器单测（FR-01 异构链路桥接）。
 * <p>
 * 覆盖 HeterogeneousLinkManager 的桥接节点注册、跨链路路由、
 * 单跳/两跳桥接路径选择与不可达判定。
 */
@DisplayName("异构链路管理器 (FR-01)")
class HeterogeneousLinkManagerTest {

    /** 构造测试用 MAVLink 帧 */
    private MavlinkFrame createFrame(int sysid, int msgId) {
        return MavlinkFrame.of(sysid, 1, 1, msgId, 0, new byte[]{0x01, 0x02, 0x03});
    }

    // ===== 注册 =====

    @Test
    @DisplayName("registerBridge：注册桥接节点后可查询")
    void registerBridge() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        LinkBridgeNode node = manager.registerBridge(1, new LinkType[]{LinkType.WIFI, LinkType.LORA});

        assertThat(node).isNotNull();
        assertThat(node.sysid).isEqualTo(1);
        assertThat(manager.getBridgeCount()).isEqualTo(1);
        assertThat(manager.getAllBridges()).hasSize(1);
    }

    @Test
    @DisplayName("registerBridge：带网段注册（FR-19）")
    void registerBridgeWithSegments() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        LinkBridgeNode node = manager.registerBridge(1,
                new LinkType[]{LinkType.WIFI, LinkType.LORA},
                new String[]{"192.168.1.x", "10.0.0.x"});

        assertThat(node.listenedSegments).containsExactlyInAnyOrder("192.168.1.x", "10.0.0.x");
    }

    @Test
    @DisplayName("unregisterBridge：注销后不可查询")
    void unregisterBridge() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        manager.registerBridge(1, new LinkType[]{LinkType.WIFI, LinkType.LORA});

        LinkBridgeNode removed = manager.unregisterBridge(1);
        assertThat(removed).isNotNull();
        assertThat(manager.getBridgeCount()).isEqualTo(0);
        assertThat(manager.unregisterBridge(1)).isNull();
    }

    @Test
    @DisplayName("registerNodeLink / getNodeLinkType：注册与查询节点介质")
    void registerAndQueryNodeLink() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        manager.registerNodeLink(10, LinkType.WIFI);
        manager.registerNodeLink(20, LinkType.LORA);

        assertThat(manager.getNodeLinkType(10)).isEqualTo(LinkType.WIFI);
        assertThat(manager.getNodeLinkType(20)).isEqualTo(LinkType.LORA);
        assertThat(manager.getNodeLinkType(30)).isNull();
    }

    // ===== getBridgeNodes =====

    @Test
    @DisplayName("getBridgeNodes：查询支持 WiFi→LoRa 的桥接节点")
    void getBridgeNodesForWifiToLora() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        manager.registerBridge(1, new LinkType[]{LinkType.WIFI, LinkType.LORA});
        manager.registerBridge(2, new LinkType[]{LinkType.WIFI, LinkType.LTE});
        manager.registerBridge(3, new LinkType[]{LinkType.WIFI, LinkType.LORA, LinkType.LTE});

        var bridges = manager.getBridgeNodes(LinkType.WIFI, LinkType.LORA);
        assertThat(bridges).hasSize(2);
        assertThat(bridges.get(0).sysid).isIn(1, 3);
    }

    @Test
    @DisplayName("getBridgeNodes：无匹配桥接节点返回空列表")
    void getBridgeNodesNoMatch() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        manager.registerBridge(1, new LinkType[]{LinkType.WIFI, LinkType.LORA});

        var bridges = manager.getBridgeNodes(LinkType.LTE, LinkType.SATELLITE);
        assertThat(bridges).isEmpty();
    }

    // ===== routeAcrossLinks：同介质直达 =====

    @Test
    @DisplayName("routeAcrossLinks：同介质直达，无需桥接")
    void routeSameLinkDirect() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        manager.registerNodeLink(10, LinkType.WIFI);
        manager.registerNodeLink(20, LinkType.WIFI);

        MavlinkFrame frame = createFrame(10, 100);
        HeterogeneousLinkManager.RouteResult result = manager.routeAcrossLinks(10, 20, frame);

        assertThat(result).isNotNull();
        assertThat(result.reachable).isTrue();
        assertThat(result.hopCount()).isEqualTo(0);
        assertThat(result.totalDelayMs).isEqualTo(0);
        assertThat(result.sourceLink).isEqualTo(LinkType.WIFI);
        assertThat(result.targetLink).isEqualTo(LinkType.WIFI);
    }

    // ===== routeAcrossLinks：单跳桥接 =====

    @Test
    @DisplayName("routeAcrossLinks：WiFi→LoRa 单跳桥接，延迟 100ms")
    void routeSingleHopBridge() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        manager.registerBridge(1, new LinkType[]{LinkType.WIFI, LinkType.LORA});
        manager.registerNodeLink(10, LinkType.WIFI);
        manager.registerNodeLink(20, LinkType.LORA);

        MavlinkFrame frame = createFrame(10, 100);
        HeterogeneousLinkManager.RouteResult result = manager.routeAcrossLinks(10, 20, frame);

        assertThat(result).isNotNull();
        assertThat(result.reachable).isTrue();
        assertThat(result.hopCount()).isEqualTo(1);
        assertThat(result.totalDelayMs).isEqualTo(100);
        assertThat(result.hops.get(0).fromType).isEqualTo(LinkType.WIFI);
        assertThat(result.hops.get(0).toType).isEqualTo(LinkType.LORA);
    }

    @Test
    @DisplayName("routeAcrossLinks：WiFi→Satellite 单跳桥接，延迟 200ms")
    void routeSingleHopWifiToSatellite() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        manager.registerBridge(1, new LinkType[]{LinkType.WIFI, LinkType.SATELLITE});
        manager.registerNodeLink(10, LinkType.WIFI);
        manager.registerNodeLink(20, LinkType.SATELLITE);

        MavlinkFrame frame = createFrame(10, 100);
        HeterogeneousLinkManager.RouteResult result = manager.routeAcrossLinks(10, 20, frame);

        assertThat(result).isNotNull();
        assertThat(result.reachable).isTrue();
        assertThat(result.hopCount()).isEqualTo(1);
        assertThat(result.totalDelayMs).isEqualTo(200);
    }

    // ===== routeAcrossLinks：两跳桥接 =====

    @Test
    @DisplayName("routeAcrossLinks：两跳桥接 WiFi→LTE→LoRa")
    void routeTwoHopBridge() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        // 桥接节点1：WiFi+LTE（可做 WiFi→LTE）
        manager.registerBridge(1, new LinkType[]{LinkType.WIFI, LinkType.LTE});
        // 桥接节点2：LTE+LoRa（可做 LTE→LoRa）
        manager.registerBridge(2, new LinkType[]{LinkType.LTE, LinkType.LORA});
        manager.registerNodeLink(10, LinkType.WIFI);
        manager.registerNodeLink(20, LinkType.LORA);

        MavlinkFrame frame = createFrame(10, 100);
        HeterogeneousLinkManager.RouteResult result = manager.routeAcrossLinks(10, 20, frame);

        assertThat(result).isNotNull();
        assertThat(result.reachable).isTrue();
        assertThat(result.hopCount()).isEqualTo(2);
        // 第一跳 WiFi→LTE，第二跳 LTE→LoRa
        assertThat(result.hops.get(0).fromType).isEqualTo(LinkType.WIFI);
        assertThat(result.hops.get(0).toType).isEqualTo(LinkType.LTE);
        assertThat(result.hops.get(1).fromType).isEqualTo(LinkType.LTE);
        assertThat(result.hops.get(1).toType).isEqualTo(LinkType.LORA);
    }

    // ===== routeAcrossLinks：不可达 =====

    @Test
    @DisplayName("routeAcrossLinks：源/目标介质未注册返回 null")
    void routeUnregisteredLinkType() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        manager.registerNodeLink(10, LinkType.WIFI);
        // 目标节点 20 未注册介质

        MavlinkFrame frame = createFrame(10, 100);
        HeterogeneousLinkManager.RouteResult result = manager.routeAcrossLinks(10, 20, frame);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("routeAcrossLinks：无桥接节点可达返回 null")
    void routeNoBridgeAvailable() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        manager.registerNodeLink(10, LinkType.WIFI);
        manager.registerNodeLink(20, LinkType.SATELLITE);
        // 无桥接节点注册

        MavlinkFrame frame = createFrame(10, 100);
        HeterogeneousLinkManager.RouteResult result = manager.routeAcrossLinks(10, 20, frame);

        assertThat(result).isNull();
    }

    // ===== RouteResult =====

    @Test
    @DisplayName("RouteResult.toString 包含源/目标介质与跳数")
    void routeResultToString() {
        HeterogeneousLinkManager manager = new HeterogeneousLinkManager();
        manager.registerBridge(1, new LinkType[]{LinkType.WIFI, LinkType.LORA});
        manager.registerNodeLink(10, LinkType.WIFI);
        manager.registerNodeLink(20, LinkType.LORA);

        MavlinkFrame frame = createFrame(10, 100);
        HeterogeneousLinkManager.RouteResult result = manager.routeAcrossLinks(10, 20, frame);

        assertThat(result.toString()).contains("WIFI", "LORA", "hops=1", "delay=100");
    }
}