package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.sim.BudgetMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BudgetMeshRouter 丐版 Mesh 路由器单测。
 * <p>
 * 覆盖 EMERGENCY_TOY（ESP-NOW 广播模式）和 EMERGENCY_STANDARD（LoRa 简化 AODV-lite）
 * 两种丐版模式的核心行为：
 * <ul>
 *   <li>EMERGENCY_TOY: hopCount 守卫、消息白名单过滤、节点数限制、邻居发现/丢失</li>
 *   <li>EMERGENCY_STANDARD: LoRa 分片/重组、HELLO 间隔放宽、邻居超时放宽</li>
 *   <li>委托模式: TOY/STANDARD/ADVANCED/FULL 委托给 MeshRouter</li>
 * </ul>
 */
@DisplayName("BudgetMeshRouter 丐版 Mesh 路由器")
class BudgetMeshRouterTest {

    /** 创建测试用 MavlinkFrame。 */
    private static MavlinkFrame createFrame(int sysid, int msgId) {
        return MavlinkFrame.of(sysid, 1, 0, msgId, 0, new byte[]{1, 2, 3});
    }

    // ===== EMERGENCY_TOY: ESP-NOW 广播模式测试 =====

    @Test
    @DisplayName("EMERGENCY_TOY 模式创建后 budgetMode 和 selfSysid 正确")
    void emergencyToyModeCreation() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);

        assertThat(router.budgetMode()).isEqualTo(BudgetMode.EMERGENCY_TOY);
        assertThat(router.selfSysid()).isEqualTo(1);
        assertThat(router.espNowTransport()).isNotNull();
        assertThat(router.loRaTransport()).isNull();
        assertThat(router.delegateRouter()).isNull();

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: 发送 HEARTBEAT(msgId=0) 白名单内返回 SENT")
    void espNowSendHeartbeatAllowed() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);
        MavlinkFrame heartbeat = createFrame(1, 0);

        BudgetMeshRouter.SendResult result = router.sendFrame(heartbeat);
        assertThat(result).isEqualTo(BudgetMeshRouter.SendResult.SENT);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: 发送 ATTITUDE(msgId=30) 白名单内返回 SENT")
    void espNowSendAttitudeAllowed() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);
        MavlinkFrame attitude = createFrame(1, 30);

        BudgetMeshRouter.SendResult result = router.sendFrame(attitude);
        assertThat(result).isEqualTo(BudgetMeshRouter.SendResult.SENT);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: 发送 COMMAND_LONG(msgId=76) 白名单内返回 SENT")
    void espNowSendCommandLongAllowed() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);
        MavlinkFrame command = createFrame(1, 76);

        BudgetMeshRouter.SendResult result = router.sendFrame(command);
        assertThat(result).isEqualTo(BudgetMeshRouter.SendResult.SENT);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: 发送非白名单消息返回 DROPPED_MSG_FILTER")
    void espNowSendNonWhitelistedMsgDropped() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);
        // msgId=10 (REQUEST_DATA_STREAM) 不在白名单
        MavlinkFrame nonWhitelisted = createFrame(1, 10);

        BudgetMeshRouter.SendResult result = router.sendFrame(nonWhitelisted);
        assertThat(result).isEqualTo(BudgetMeshRouter.SendResult.DROPPED_MSG_FILTER);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: forwardFrame hopCount=5 递减为 4 返回 SENT")
    void espNowForwardFrameHopCountDecrement() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);
        MavlinkFrame heartbeat = createFrame(2, 0);

        BudgetMeshRouter.SendResult result = router.forwardFrame(heartbeat, 5);
        assertThat(result).isEqualTo(BudgetMeshRouter.SendResult.SENT);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: forwardFrame hopCount=1 耗尽返回 DROPPED_HOP_LIMIT")
    void espNowForwardFrameHopCountExhausted() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);
        MavlinkFrame heartbeat = createFrame(2, 0);

        BudgetMeshRouter.SendResult result = router.forwardFrame(heartbeat, 1);
        assertThat(result).isEqualTo(BudgetMeshRouter.SendResult.DROPPED_HOP_LIMIT);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: forwardFrame hopCount=0 返回 DROPPED_HOP_LIMIT")
    void espNowForwardFrameHopCountZero() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);
        MavlinkFrame heartbeat = createFrame(2, 0);

        BudgetMeshRouter.SendResult result = router.forwardFrame(heartbeat, 0);
        assertThat(result).isEqualTo(BudgetMeshRouter.SendResult.DROPPED_HOP_LIMIT);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: setHopCount 设置初始 hopCount")
    void espNowSetHopCount() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);

        router.setHopCount(3);
        assertThat(router.currentHopCount()).isEqualTo(3);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: setHopCount 超范围抛 IllegalArgumentException")
    void espNowSetHopCountOutOfRange() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);

        assertThatThrownBy(() -> router.setHopCount(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.setHopCount(EspNowTransport.MAX_HOPS + 1))
                .isInstanceOf(IllegalArgumentException.class);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: 邻居发现与丢失")
    void espNowNeighborDiscoveryAndLoss() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);

        assertThat(router.neighborCount()).isZero();

        router.onNeighborDiscovered(2);
        router.onNeighborDiscovered(3);
        assertThat(router.neighborCount()).isEqualTo(2);
        assertThat(router.snapshotNeighbors()).contains(2, 3);

        // 自身 sysid 不应被添加为邻居
        router.onNeighborDiscovered(1);
        assertThat(router.neighborCount()).isEqualTo(2);

        router.onNeighborLost(2);
        assertThat(router.neighborCount()).isEqualTo(1);
        assertThat(router.snapshotNeighbors()).containsExactly(3);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_TOY: close 后 sendFrame 返回 DROPPED_CLOSED")
    void espNowCloseDropsFrames() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);
        router.close();

        MavlinkFrame heartbeat = createFrame(1, 0);
        assertThat(router.sendFrame(heartbeat)).isEqualTo(BudgetMeshRouter.SendResult.DROPPED_CLOSED);
    }

    @Test
    @DisplayName("EMERGENCY_TOY: close 后邻居集合清空")
    void espNowCloseClearsNeighbors() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyToy(1, channelKey);
        router.onNeighborDiscovered(2);
        router.onNeighborDiscovered(3);
        assertThat(router.neighborCount()).isEqualTo(2);

        router.close();
        assertThat(router.neighborCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("EMERGENCY_TOY: MAX_HOPS=5 常量正确")
    void espNowMaxHopsConstant() {
        assertThat(EspNowTransport.MAX_HOPS).isEqualTo(5);
    }

    @Test
    @DisplayName("EMERGENCY_TOY: MAX_NODES=8 常量正确")
    void espNowMaxNodesConstant() {
        assertThat(EspNowTransport.MAX_NODES).isEqualTo(8);
    }

    @Test
    @DisplayName("EMERGENCY_TOY: MIN_NODES=5 常量正确")
    void espNowMinNodesConstant() {
        assertThat(EspNowTransport.MIN_NODES).isEqualTo(5);
    }

    @Test
    @DisplayName("EspNowTransport.isAllowedMsgId: 白名单消息返回 true")
    void espNowAllowedMsgIdWhitelist() {
        assertThat(EspNowTransport.isAllowedMsgId(0)).isTrue();    // HEARTBEAT
        assertThat(EspNowTransport.isAllowedMsgId(30)).isTrue();   // ATTITUDE
        assertThat(EspNowTransport.isAllowedMsgId(76)).isTrue();   // COMMAND_LONG
        assertThat(EspNowTransport.isAllowedMsgId(477)).isTrue();  // AlarmTriggerMsg
        assertThat(EspNowTransport.isAllowedMsgId(478)).isTrue();  // AlarmAckMsg
        assertThat(EspNowTransport.isAllowedMsgId(479)).isTrue();  // SurveillanceStatusMsg
    }

    @Test
    @DisplayName("EspNowTransport.isAllowedMsgId: 非白名单消息返回 false")
    void espNowNonAllowedMsgIdRejected() {
        assertThat(EspNowTransport.isAllowedMsgId(10)).isFalse();  // REQUEST_DATA_STREAM
        assertThat(EspNowTransport.isAllowedMsgId(100)).isFalse();
        assertThat(EspNowTransport.isAllowedMsgId(255)).isFalse();
    }

    // ===== EMERGENCY_STANDARD: LoRa 简化 AODV-lite 测试 =====

    @Test
    @DisplayName("EMERGENCY_STANDARD 模式创建后 budgetMode 和 selfSysid 正确")
    void emergencyStandardModeCreation() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyStandard(1, channelKey);

        assertThat(router.budgetMode()).isEqualTo(BudgetMode.EMERGENCY_STANDARD);
        assertThat(router.selfSysid()).isEqualTo(1);
        assertThat(router.loRaTransport()).isNotNull();
        assertThat(router.espNowTransport()).isNull();
        assertThat(router.delegateRouter()).isNull();

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_STANDARD: 发送帧返回 SENT")
    void loraSendFrameReturnsSent() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyStandard(1, channelKey);
        MavlinkFrame heartbeat = createFrame(1, 0);

        BudgetMeshRouter.SendResult result = router.sendFrame(heartbeat);
        assertThat(result).isEqualTo(BudgetMeshRouter.SendResult.SENT);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_STANDARD: HELLO_INTERVAL_MS=5000 放宽至 5s")
    void loraHelloIntervalRelaxed() {
        assertThat(LoRaTransportAdapter.HELLO_INTERVAL_MS).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("EMERGENCY_STANDARD: NEIGHBOR_TIMEOUT_MS=15000 放宽至 15s")
    void loraNeighborTimeoutRelaxed() {
        assertThat(LoRaTransportAdapter.NEIGHBOR_TIMEOUT_MS).isEqualTo(15_000L);
    }

    @Test
    @DisplayName("EMERGENCY_STANDARD: MAX_HOPS=10")
    void loraMaxHopsConstant() {
        assertThat(LoRaTransportAdapter.MAX_HOPS).isEqualTo(10);
    }

    @Test
    @DisplayName("EMERGENCY_STANDARD: LORA_MAX_PAYLOAD_BYTES=50")
    void loraMaxPayloadConstant() {
        assertThat(LoRaTransportAdapter.LORA_MAX_PAYLOAD_BYTES).isEqualTo(50);
    }

    @Test
    @DisplayName("EMERGENCY_STANDARD: 邻居发现与丢失")
    void loraNeighborDiscoveryAndLoss() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyStandard(1, channelKey);

        assertThat(router.neighborCount()).isZero();

        router.onNeighborDiscovered(2);
        router.onNeighborDiscovered(3);
        assertThat(router.neighborCount()).isEqualTo(2);

        router.onNeighborLost(2);
        assertThat(router.neighborCount()).isEqualTo(1);

        router.close();
    }

    @Test
    @DisplayName("EMERGENCY_STANDARD: close 后 sendFrame 返回 DROPPED_CLOSED")
    void loraCloseDropsFrames() {
        Object channelKey = new Object();
        BudgetMeshRouter router = BudgetMeshRouter.emergencyStandard(1, channelKey);
        router.close();

        MavlinkFrame heartbeat = createFrame(1, 0);
        assertThat(router.sendFrame(heartbeat)).isEqualTo(BudgetMeshRouter.SendResult.DROPPED_CLOSED);
    }

    // ===== LoRaTransportAdapter 分片/重组测试 =====

    @Test
    @DisplayName("LoRaTransportAdapter: fragmentFrame 分片数量正确")
    void loraFragmentCount() {
        Object channelKey = new Object();
        LoRaTransportAdapter adapter = new LoRaTransportAdapter(channelKey, 1);

        // MavlinkFrame.of(1, 1, 0, 0, 0, new byte[]{1,2,3}) 编码后约 15 字节
        // chunkSize = 50 - 3 = 47，所以 15 字节只需 1 个分片
        MavlinkFrame smallFrame = createFrame(1, 0);
        List<LoRaTransportAdapter.Fragment> fragments = adapter.fragmentFrame(smallFrame);
        assertThat(fragments).hasSize(1);
        assertThat(fragments.get(0).totalFragments()).isEqualTo(1);
        assertThat(fragments.get(0).fragIndex()).isEqualTo(0);

        adapter.close();
    }

    @Test
    @DisplayName("LoRaTransportAdapter: reassembleFragments 重组完整帧")
    void loraReassembleComplete() {
        Object channelKey = new Object();
        LoRaTransportAdapter adapter = new LoRaTransportAdapter(channelKey, 1);

        MavlinkFrame frame = createFrame(1, 0);
        List<LoRaTransportAdapter.Fragment> fragments = adapter.fragmentFrame(frame);

        byte[] reassembled = adapter.reassembleFragments(fragments);
        assertThat(reassembled).isNotNull();
        // 重组后的字节应与原始编码一致
        byte[] original = frame.encodeV2();
        assertThat(reassembled).isEqualTo(original);

        adapter.close();
    }

    @Test
    @DisplayName("LoRaTransportAdapter: reassembleFragments 分片不完整返回 null")
    void loraReassembleIncomplete() {
        Object channelKey = new Object();
        LoRaTransportAdapter adapter = new LoRaTransportAdapter(channelKey, 1);

        // 构造一个 totalFragments=3 但只提供 2 个分片的情况
        LoRaTransportAdapter.Fragment f0 = new LoRaTransportAdapter.Fragment(1, 3, 0, new byte[]{1});
        LoRaTransportAdapter.Fragment f1 = new LoRaTransportAdapter.Fragment(1, 3, 1, new byte[]{2});
        List<LoRaTransportAdapter.Fragment> incomplete = List.of(f0, f1);

        byte[] result = adapter.reassembleFragments(incomplete);
        assertThat(result).isNull();

        adapter.close();
    }

    @Test
    @DisplayName("LoRaTransportAdapter: Fragment.toBytes/fromBytes 往返一致")
    void loraFragmentRoundTrip() {
        byte[] payload = {10, 20, 30, 40};
        LoRaTransportAdapter.Fragment original = new LoRaTransportAdapter.Fragment(42, 3, 1, payload);

        byte[] bytes = original.toBytes();
        LoRaTransportAdapter.Fragment restored = LoRaTransportAdapter.Fragment.fromBytes(bytes);

        assertThat(restored).isNotNull();
        assertThat(restored.frameId()).isEqualTo(42);
        assertThat(restored.totalFragments()).isEqualTo(3);
        assertThat(restored.fragIndex()).isEqualTo(1);
        assertThat(restored.payload()).isEqualTo(payload);
    }

    // ===== 委托模式测试 =====

    @Test
    @DisplayName("TOY 模式委托给 MeshRouter: budgetMode 正确")
    void delegatedToyMode() {
        MeshRouter delegate = new MeshRouter(1, MeshRouterConfig.defaults(),
                (io.aerofleet.mavlink.transport.UdpMavlinkTransport) null);
        BudgetMeshRouter router = BudgetMeshRouter.delegated(BudgetMode.TOY, 1, delegate);

        assertThat(router.budgetMode()).isEqualTo(BudgetMode.TOY);
        assertThat(router.delegateRouter()).isSameAs(delegate);
        assertThat(router.espNowTransport()).isNull();
        assertThat(router.loRaTransport()).isNull();

        router.close();
    }

    @Test
    @DisplayName("委托模式: null delegateRouter 时 sendFrame 返回 DROPPED_CLOSED")
    void delegatedNullRouter() {
        BudgetMeshRouter router = new BudgetMeshRouter(BudgetMode.STANDARD, 1, null, null);

        MavlinkFrame frame = createFrame(1, 0);
        assertThat(router.sendFrame(frame)).isEqualTo(BudgetMeshRouter.SendResult.DROPPED_CLOSED);

        router.close();
    }

    // ===== 构造参数验证测试 =====

    @Test
    @DisplayName("构造: budgetMode=null 抛 IllegalArgumentException")
    void constructNullBudgetMode() {
        assertThatThrownBy(() -> new BudgetMeshRouter(null, 1, new Object(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("构造: selfSysid=0 抛 IllegalArgumentException")
    void constructInvalidSysid() {
        assertThatThrownBy(() -> BudgetMeshRouter.emergencyToy(0, new Object()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetMeshRouter.emergencyToy(256, new Object()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}