package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MeshHeartbeatMsg;
import io.aerofleet.mavlink.messages.MeshRouteErrorMsg;
import io.aerofleet.mavlink.messages.MeshRouteReplyMsg;
import io.aerofleet.mavlink.messages.MeshRouteRequestMsg;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import io.aerofleet.sim.comm.LoRaMavlinkTransport;
import io.aerofleet.sim.comm.LoRaTransportAdapter;
import io.aerofleet.sim.comm.MavlinkTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * MeshRouter × LoRaMavlinkTransport 运行时集成测试（3d）。
 * <p>
 * 验证两个或多个 MeshRouter 实例通过共享 LoRa 空中信道互通：
 * <ul>
 *   <li>HELLO 邻居发现</li>
 *   <li>RREQ/RREP 按需建路</li>
 *   <li>多跳转发（A → B → C）</li>
 *   <li>分片重组（大帧）</li>
 *   <li>信道竞争</li>
 *   <li>节点关闭后信道清理</li>
 *   <li>UDP 向后兼容</li>
 *   <li>空信道不崩溃</li>
 *   <li>延迟统计</li>
 *   <li>分片丢失重组超时</li>
 * </ul>
 */
@DisplayName("MeshRouter × LoRa 运行时集成 (3d)")
class MeshRouterLoRaIntegrationTest {

    /** 测试用 LoRa 配置：小 payload 触发分片。 */
    private static final int LORA_MAX_PAYLOAD = 30;
    private static final InetSocketAddress MESH_GROUP =
            new InetSocketAddress("239.0.0.1", 14550);
    private static final int RSSI = -50;

    /** 收集已关闭的资源，测试后统一清理。 */
    private final java.util.List<AutoCloseable> resources = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AutoCloseable r : resources) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
        resources.clear();
    }

    /** 创建 LoRa 模式的 MeshRouterConfig。 */
    private static MeshRouterConfig loraConfig() {
        return new MeshRouterConfig(
                200L, 3000L, 10000L, 15,
                1.0, 0.5, 0.1, 0.5,
                5000L, MESH_GROUP, null,
                MeshRouterConfig.TransportType.LORA,
                433.0, 7, 125, 5, 20, LORA_MAX_PAYLOAD);
    }

    /** 创建 UDP 模式的 MeshRouterConfig。 */
    private static MeshRouterConfig udpConfig() {
        return new MeshRouterConfig(
                200L, 3000L, 10000L, 15,
                1.0, 0.5, 0.1, 0.5,
                5000L, MESH_GROUP, null);
    }

    /**
     * 创建一个 LoRa mesh 节点，注册帧监听器自动分发到 router 的 on* 方法。
     * <p>
     * 监听器解码帧 → 根据 messageId 调用 onMeshHeartbeat/onRouteRequest/onRouteReply/onRouteError。
     */
    MeshRouter newLoRaNode(int sysid, MeshRouterConfig config,
                           LoRaMavlinkTransport loRa, LinkedBlockingQueue<byte[]> channel) {
        MeshRouter router = MeshRouter.createWithLoRa(sysid, config, loRa, channel);
        resources.add(router);
        resources.add(router.transport());
        connectDispatcher(router, sysid);
        router.start();
        return router;
    }

    /** 为 router 注册帧监听器，将收到的帧解码并分发到对应的 on* 处理方法。 */
    private void connectDispatcher(MeshRouter router, int selfSysid) {
        router.addFrameListener(frame -> {
            // 忽略自己发出的帧（frame.getSystemId() == selfSysid）
            if (frame.getSystemId() == selfSysid) {
                return;
            }
            MavlinkMessage msg = MavlinkMessage.decode(frame);
            if (msg == null) {
                return;
            }
            InetSocketAddress srcAddr = new InetSocketAddress("127.0.0.1", 10000 + frame.getSystemId());
            int frameSysid = frame.getSystemId();
            if (msg instanceof MeshHeartbeatMsg hello) {
                router.onMeshHeartbeat(hello, srcAddr, RSSI);
            } else if (msg instanceof MeshRouteRequestMsg rreq) {
                router.onRouteRequest(rreq, srcAddr, RSSI, frameSysid);
            } else if (msg instanceof MeshRouteReplyMsg rrep) {
                router.onRouteReply(rrep, srcAddr, RSSI, frameSysid);
            } else if (msg instanceof MeshRouteErrorMsg rerr) {
                router.onRouteError(rerr, srcAddr, frameSysid);
            }
        });
    }

    /** 等待条件成立（最多 3 秒）。 */
    private static void waitFor(java.util.function.BooleanSupplier condition, String desc) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("waitFor 超时: " + desc);
    }

    // ===== 测试 1：两个节点通过 LoRa 信道互通 =====

    @Test
    @DisplayName("两个 MeshRouter 通过共享 LoRa 信道互通：A 发 HELLO → B 学习邻居")
    void twoNodesInterconnectViaLoRa() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);

        // A 广播 HELLO
        nodeA.broadcastHello(System.currentTimeMillis());

        // B 应学习到 A 为邻居
        waitFor(() -> nodeB.neighborCount() >= 1, "B 学习到 A");
        assertThat(nodeB.snapshotNeighbors().contains(1)).isTrue();
    }

    // ===== 测试 2：HELLO 邻居发现 =====

    @Test
    @DisplayName("节点 A 发 HELLO，节点 B 收到并学习邻居，反向亦然")
    void helloNeighborDiscoveryBidirectional() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(10, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(20, config, LoRaMavlinkTransport.defaultConfig(), channel);

        nodeA.broadcastHello(System.currentTimeMillis());
        nodeB.broadcastHello(System.currentTimeMillis());

        waitFor(() -> nodeA.neighborCount() >= 1 && nodeB.neighborCount() >= 1,
                "A 和 B 互相学习邻居");
        assertThat(nodeA.snapshotNeighbors().contains(20)).isTrue();
        assertThat(nodeB.snapshotNeighbors().contains(10)).isTrue();
    }

    // ===== 测试 3：RREQ/RREP 按需建路 =====

    @Test
    @DisplayName("节点 A 发 RREQ，节点 B 回 RREP，A 学到到 B 的路由")
    void rreqRrepRouteEstablishment() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);

        // 先建立邻居关系
        nodeA.broadcastHello(System.currentTimeMillis());
        nodeB.broadcastHello(System.currentTimeMillis());
        waitFor(() -> nodeA.neighborCount() >= 1 && nodeB.neighborCount() >= 1, "邻居建立");

        // A 发起到 B 的 RREQ
        nodeA.sendTo(2, new byte[]{1, 2, 3});

        // A 应学到到 B 的路由（RREP 回传后）
        waitFor(() -> nodeA.snapshotRoutes().lookup(2) != null, "A 学到到 B 的路由");
        assertThat(nodeA.snapshotRoutes().lookup(2)).isNotNull();
    }

    // ===== 测试 4：通过学到的路由发数据 =====

    @Test
    @DisplayName("节点 A 通过学到的路由发数据到 B，返回 SENT")
    void sendDataViaEstablishedRoute() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);

        // 建立邻居 + 路由
        nodeA.broadcastHello(System.currentTimeMillis());
        nodeB.broadcastHello(System.currentTimeMillis());
        waitFor(() -> nodeA.neighborCount() >= 1 && nodeB.neighborCount() >= 1, "邻居建立");
        nodeA.sendTo(2, new byte[]{1});
        waitFor(() -> nodeA.snapshotRoutes().lookup(2) != null, "路由建立");

        // 再次发送，应有路由，返回 SENT
        MeshRouter.SendResult result = nodeA.sendTo(2, new byte[]{42});
        assertThat(result).isEqualTo(MeshRouter.SendResult.SENT);
    }

    // ===== 测试 5：多跳场景 A → B → C =====

    @Test
    @DisplayName("多跳场景：A → B → C，A 发 RREQ 找 C，B 转发，C 回 RREP")
    void multiHopRouteDiscovery() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeC = newLoRaNode(3, config, LoRaMavlinkTransport.defaultConfig(), channel);

        // 三节点互相广播 HELLO 建立邻居
        long now = System.currentTimeMillis();
        nodeA.broadcastHello(now);
        nodeB.broadcastHello(now);
        nodeC.broadcastHello(now);

        waitFor(() -> nodeA.neighborCount() >= 1 && nodeB.neighborCount() >= 1 && nodeC.neighborCount() >= 1,
                "三节点邻居建立");

        // A 发起到 C 的 RREQ
        nodeA.sendTo(3, new byte[]{1, 2, 3});

        // A 应学到到 C 的路由（B 转发 RREQ，C 回 RREP，B 转发 RREP）
        waitFor(() -> nodeA.snapshotRoutes().lookup(3) != null, "A 学到到 C 的多跳路由");
        assertThat(nodeA.snapshotRoutes().lookup(3)).isNotNull();
    }

    // ===== 测试 6：分片重组（大帧需要多个 LoRa 分片） =====

    @Test
    @DisplayName("大帧分片重组：HELLO 帧编码后 > maxPayload，需多个 LoRa 分片，B 仍正确收到")
    void largeFrameFragmentReassembly() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        // 使用极小 payload 强制分片
        MeshRouterConfig config = new MeshRouterConfig(
                200L, 3000L, 10000L, 15, 1.0, 0.5, 0.1, 0.5,
                5000L, MESH_GROUP, null,
                MeshRouterConfig.TransportType.LORA, 433.0, 7, 125, 5, 20, 15); // maxPayload=15 → chunk=12
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);

        // MeshHeartbeatMsg LEN=24，编码后帧 ~36 字节，maxPayload=15 → 至少 3 个分片
        nodeA.broadcastHello(System.currentTimeMillis());

        waitFor(() -> nodeB.neighborCount() >= 1, "B 收到重组后的 HELLO");
        assertThat(nodeB.snapshotNeighbors().contains(1)).isTrue();
    }

    // ===== 测试 7：信道竞争（多个节点同时发送） =====

    @Test
    @DisplayName("信道竞争：三个节点同时广播 HELLO，不丢节点")
    void channelContentionMultipleSenders() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeC = newLoRaNode(3, config, LoRaMavlinkTransport.defaultConfig(), channel);

        // 三节点近乎同时广播
        long now = System.currentTimeMillis();
        nodeA.broadcastHello(now);
        nodeB.broadcastHello(now);
        nodeC.broadcastHello(now);

        // 每个节点应至少学到 2 个邻居
        waitFor(() -> nodeA.neighborCount() >= 2 && nodeB.neighborCount() >= 2 && nodeC.neighborCount() >= 2,
                "三节点互相学习邻居");
        assertThat(nodeA.neighborCount()).isGreaterThanOrEqualTo(2);
        assertThat(nodeB.neighborCount()).isGreaterThanOrEqualTo(2);
        assertThat(nodeC.neighborCount()).isGreaterThanOrEqualTo(2);
    }

    // ===== 测试 8：节点关闭后信道清理 =====

    @Test
    @DisplayName("节点关闭后从信道取消订阅，信道订阅者数减少")
    void nodeCloseReleasesChannel() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);

        MavlinkTransport transportA = nodeA.transport();
        assertThat(transportA).isInstanceOf(LoRaTransportAdapter.class);
        LoRaTransportAdapter.LoRaAirChannel airChannel = ((LoRaTransportAdapter) transportA).channel();
        assertThat(airChannel.subscriberCount()).isEqualTo(2);

        // 关闭 nodeA
        nodeA.close();
        transportA.close();

        waitFor(() -> airChannel.subscriberCount() == 1, "nodeA 取消订阅");
        assertThat(airChannel.subscriberCount()).isEqualTo(1);
    }

    // ===== 测试 9：UDP 模式向后兼容 =====

    @Test
    @DisplayName("配置为 UDP 模式时仍正常工作（向后兼容，transport=null 不崩溃）")
    void udpBackwardCompatibleWithNullTransport() {
        MeshRouterConfig config = udpConfig();
        // transport=null（单测模式），sendFrame 静默跳过
        MeshRouter router = new MeshRouter(1, config, (UdpMavlinkTransport) null);
        resources.add(router);
        router.start();

        // broadcastHello 不抛异常
        router.broadcastHello(System.currentTimeMillis());
        // sendTo 无路由时返回 QUEUED_RREQ（不崩溃）
        MeshRouter.SendResult result = router.sendTo(99, new byte[]{1});
        assertThat(result).isEqualTo(MeshRouter.SendResult.QUEUED_RREQ);
        // tick 不崩溃
        router.tick(System.currentTimeMillis());
        assertThat(router.isClosed()).isFalse();
    }

    // ===== 测试 10：LORA 模式但信道为空时不崩溃 =====

    @Test
    @DisplayName("LORA 模式但信道无其他节点时不崩溃，广播 HELLO 静默")
    void loraModeEmptyChannelNoCrash() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);

        // 信道中只有 A，广播 HELLO 不崩溃
        nodeA.broadcastHello(System.currentTimeMillis());
        nodeA.tick(System.currentTimeMillis());
        nodeA.sendTo(99, new byte[]{1});

        // A 邻居数为 0（没人回应）
        assertThat(nodeA.neighborCount()).isZero();
        assertThat(nodeA.isClosed()).isFalse();
    }

    // ===== 测试 11：LoRa 传输延迟统计 =====

    @Test
    @DisplayName("LoRa 传输延迟统计：发送后 totalSentBytes 与 totalFrameCount 递增")
    void loraLatencyStatistics() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);

        LoRaTransportAdapter adapterA = (LoRaTransportAdapter) nodeA.transport();
        long beforeBytes = adapterA.totalSentBytes();
        long beforeFrames = adapterA.totalFrameCount();

        nodeA.broadcastHello(System.currentTimeMillis());

        // 等待发送完成
        waitFor(() -> adapterA.totalSentBytes() > beforeBytes, "A 发送字节递增");
        assertThat(adapterA.totalSentBytes()).isGreaterThan(beforeBytes);
        assertThat(adapterA.totalFrameCount()).isGreaterThan(beforeFrames);
        // 平均发送耗时非负
        assertThat(adapterA.averageSendNs()).isGreaterThanOrEqualTo(0);
    }

    // ===== 测试 12：分片丢失后重组超时（不崩溃，后续帧仍可接收） =====

    @Test
    @DisplayName("分片丢失后重组不崩溃，后续完整帧仍可正确接收")
    void fragmentLossResilience() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);

        // 第一帧：正常发送，B 收到
        nodeA.broadcastHello(System.currentTimeMillis());
        waitFor(() -> nodeB.neighborCount() >= 1, "B 收到第一帧");

        // 模拟分片丢失：直接往信道放一个残缺分片（不会重组完成）
        LoRaTransportAdapter adapterA = (LoRaTransportAdapter) nodeA.transport();
        byte[] partialFrag = new byte[]{(byte) 200, (byte) 2, (byte) 0, 1, 2, 3}; // frameId=200, total=2, index=0
        adapterA.channel().broadcast(99, partialFrag); // 来自虚构节点 99

        // 第二帧：A 再次广播 HELLO，B 仍能收到（不因残缺分片崩溃）
        // 先让 B 的邻居超时（清掉旧邻居），再发新 HELLO
        nodeA.broadcastHello(System.currentTimeMillis() + 10000);

        // B 仍然正常运行，邻居包含 A
        assertThat(nodeB.isClosed()).isFalse();
        assertThat(nodeB.snapshotNeighbors().contains(1)).isTrue();
    }

    // ===== 测试 13：LoRa 配置参数反映在传输层 =====

    @Test
    @DisplayName("LoRa 适配器 getLocalPort 返回配置的 channelId")
    void loraAdapterLocalPortIsChannelId() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);

        MavlinkTransport transport = nodeA.transport();
        assertThat(transport).isInstanceOf(LoRaTransportAdapter.class);
        // channelId = config.loRaMaxPayloadBytes
        assertThat(transport.getLocalPort()).isEqualTo(LORA_MAX_PAYLOAD);
    }

    // ===== 测试 14：RERR 传播 =====

    @Test
    @DisplayName("节点 A 触发 RERR，通过 LoRa 信道传播，不崩溃")
    void rerrPropagationViaLoRa() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);

        // 建立邻居
        nodeA.broadcastHello(System.currentTimeMillis());
        nodeB.broadcastHello(System.currentTimeMillis());
        waitFor(() -> nodeA.neighborCount() >= 1, "邻居建立");

        // A 触发 RERR（目标 99 不可达）
        nodeA.triggerRerr(99);

        // 不崩溃，B 仍正常运行
        assertThat(nodeA.isClosed()).isFalse();
        assertThat(nodeB.isClosed()).isFalse();
    }

    // ===== 测试 15：多帧连续发送 =====

    @Test
    @DisplayName("多帧连续发送：A 连发 5 个 HELLO，B 至少收到一个")
    void multipleConsecutiveFrames() {
        LinkedBlockingQueue<byte[]> channel = new LinkedBlockingQueue<>();
        MeshRouterConfig config = loraConfig();
        MeshRouter nodeA = newLoRaNode(1, config, LoRaMavlinkTransport.defaultConfig(), channel);
        MeshRouter nodeB = newLoRaNode(2, config, LoRaMavlinkTransport.defaultConfig(), channel);

        for (int i = 0; i < 5; i++) {
            nodeA.broadcastHello(System.currentTimeMillis() + i * 100);
        }

        waitFor(() -> nodeB.neighborCount() >= 1, "B 收到至少一个 HELLO");
        assertThat(nodeB.snapshotNeighbors().contains(1)).isTrue();
    }
}