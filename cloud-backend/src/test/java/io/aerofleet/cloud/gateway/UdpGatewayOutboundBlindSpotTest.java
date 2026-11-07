package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Heartbeat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * P2-F6 ②「未知 sysid 出站」语义测试（{@code docs/sitl-integration.md} §12.4 表②取证，
 * 2026-11-06 取证；2026-11-07 修复）。
 *
 * <h2>本类钉住什么</h2>
 * 出站 {@code UdpGateway#send(int, MavlinkFrame)} 有三条路：
 * <ol>
 *   <li>路由表学到的地址 → <b>定向单发</b>（单机语义，逐字节不变）；</li>
 *   <li><b>未学到路由</b>且已有其他已知实例 → <b>定向广播</b>给
 *       {@link RouteTable#addresses()} 全部已知实例（P2-F6 ②；受每-sysid 出站预算限制，
 *       预算按实例摊分 ⇒ 总帧数不放大）；</li>
 *   <li>一个已知实例也没有 → {@code transport.sendToLastPeer} 兜底（可能静默丢弃）。</li>
 * </ol>
 *
 * <p><b>历史口径（2026-11-06 的取证版）</b>：当时第 2 条路也是 {@code sendToLastPeer}，
 * 即「最后说话者拿走命令」——多实例时其他实例静默失联。那时这些测试
 * <b>是取证钉住、不是产品意图</b>，注释写明「修复②到达判据时它们会红，须回读 §12.4」。
 * 2026-11-07 修复落地（判据 2 的「定向广播 + 摊分」两半都实现并有专测），
 * 本类随之改为钉<b>新行为</b>；「零入站静默丢弃」那条形态今天仍成立。
 *
 * <h2>手法</h2>
 * 与 {@link UdpGatewayTest} 同款：transport 无法注入（构造内 new），故用真实 ephemeral
 * UDP 端口 + Mockito mock 下游；另起绑定随机端口的 DatagramSocket 充当「机载实例」，
 * 以收帧归属验证出站落点。签名默认关闭，出站帧字节与入参帧逐字节一致，可直接比对。
 */
@DisplayName("UdpGateway P2-F6②：未知 sysid 出站定向广播 + 摊分预算（零入站仍静默丢弃）")
class UdpGatewayOutboundBlindSpotTest {

    /** drone-port：无人监听的固定端口（仅作为 discovery 目标；若出站兜底落向它，收帧断言必超时红）。 */
    private static final int UNLISTENED_DRONE_PORT = 24699;

    private TelemetryIngestService ingest;
    private PendingAcks pendings;
    private UdpGateway gateway;

    @BeforeEach
    void setUp() {
        ingest = mock(TelemetryIngestService.class);
        pendings = mock(PendingAcks.class);
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.shutdown();
            gateway = null;
        }
    }

    /**
     * drone-host 用默认值语义（127.0.0.1，「未配置」时的实际取值）构造。
     * discovery 心跳将发往无人监听的 UNLISTENED_DRONE_PORT，发送失败仅记日志。
     */
    private UdpGateway newGateway() throws Exception {
        gateway = new UdpGateway(0, "127.0.0.1", UNLISTENED_DRONE_PORT, "", "0.0.0.0",
                false, 100, ingest, pendings);
        return gateway;
    }

    private MavlinkFrame droneHeartbeat(int sysid, int seq) {
        Heartbeat hb = new Heartbeat(0, 2, 6, 0, MavEnums.MAV_STATE_ACTIVE);
        return hb.toFrame(sysid, 1, seq);
    }

    /** 绑定随机端口的模拟机载实例（收包超时 2.5s，超时由 tryReceive 转为 null）。 */
    private DatagramSocket newDrone() throws Exception {
        DatagramSocket s = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        s.setSoTimeout(2500);
        return s;
    }

    /** 机载实例向网关端口发一帧心跳（入站 ⇒ transport 刷新 lastPeer、网关 learn 路由）。 */
    private void droneSaysHeartbeat(DatagramSocket drone, int sysid, int seq) throws Exception {
        byte[] data = droneHeartbeat(sysid, seq).encodeV2();
        drone.send(new DatagramPacket(data, data.length,
                new InetSocketAddress("127.0.0.1", gateway.getLocalPort())));
    }

    /** 等待 ingest.handle 被调 ≥n 次（入站处理完毕 ⇒ learn 已发生）。 */
    private void awaitHandleCount(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline
                && mockingDetails(ingest).getInvocations().size() < n) {
            Thread.sleep(20);
        }
    }

    /** 尝试收一帧；超时/异常返回 null（未收到）。 */
    private byte[] tryReceive(DatagramSocket drone) {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
            drone.receive(p);
            byte[] out = new byte[p.getLength()];
            System.arraycopy(p.getData(), p.getOffset(), out, 0, p.getLength());
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    @DisplayName("learn 过的 sysid：出站定向到该实例（路由表正常职责——基线）")
    void learnedSysidSendsDirectlyToLearnedAddress() throws Exception {
        gateway = newGateway();
        try (DatagramSocket peerA = newDrone()) {
            droneSaysHeartbeat(peerA, 7, 1);
            awaitHandleCount(1);

            gateway.send(7, droneHeartbeat(7, 2));

            assertThat(gateway.routeOf(7)).as("入站心跳已把 peerA 学进路由表").isNotNull();
            byte[] got = tryReceive(peerA);
            assertThat(got).as("learn 过的 sysid 出站必须定向到 peerA")
                    .isEqualTo(droneHeartbeat(7, 2).encodeV2());
        }
    }

    @Test
    @DisplayName("未 learn 的 sysid：出站**定向广播**给所有已知实例，不再是「最后说话者独占」")
    void unknownSysidBroadcastsToAllKnownInstances() throws Exception {
        gateway = newGateway();
        try (DatagramSocket peerA = newDrone(); DatagramSocket peerB = newDrone()) {
            droneSaysHeartbeat(peerA, 7, 1);
            droneSaysHeartbeat(peerB, 8, 1);
            awaitHandleCount(2);

            // sysid=9 从未入站：send(9,…) 查不到路由 ⇒ 定向广播给两个已 learn 实例
            gateway.send(9, droneHeartbeat(9, 2));

            byte[] atA = tryReceive(peerA);
            assertThat(atA).as("广播必须送达 peerA（旧行为只有最后说话者收到）")
                    .isEqualTo(droneHeartbeat(9, 2).encodeV2());

            byte[] atB = tryReceive(peerB);
            assertThat(atB).as("广播必须送达 peerB").isEqualTo(droneHeartbeat(9, 2).encodeV2());
        }
    }

    @Test
    @DisplayName("广播路径受每-sysid 出站预算约束：预算=1 时只广播 1 帧，其余走兜底（摊分）")
    void broadcastIsBudgetCappedPerSysid() throws Exception {
        // 主预算=1：sysid=9 的广播窗口首帧即用掉配额
        gateway = new UdpGateway(0, "127.0.0.1", UNLISTENED_DRONE_PORT, "", "0.0.0.0",
                false, 1, ingest, pendings);
        try (DatagramSocket peerA = newDrone(); DatagramSocket peerB = newDrone()) {
            droneSaysHeartbeat(peerA, 7, 1);
            droneSaysHeartbeat(peerB, 8, 1);
            awaitHandleCount(2);

            // 同一秒内连发 6 帧未知 sysid：第 1 帧广播给 2 个实例，后 5 帧预算耗尽 ⇒ 兜底
            for (int i = 0; i < 6; i++) {
                gateway.send(9, droneHeartbeat(9, 10 + i));
            }
            Thread.sleep(250);

            // peerA 不是 lastSpeaker：它收到的帧**全部来自广播** ⇒ 收到的包数 == 被放行广播的帧数
            assertThat(countDelivered(peerA))
                    .as("广播被预算限制为 1 帧（不是 6 帧都广播 ⇒ 不随实例数翻倍）")
                    .isEqualTo(1);
            // peerB 同时是 lastPeer：1 次广播 + 5 次兜底
            assertThat(countDelivered(peerB)).isEqualTo(6);
        }
    }

    @Test
    @DisplayName("预算充足时广播完整送达每个已知实例（3 帧 × 2 实例）")
    void broadcastDeliversToEveryInstanceWhenBudgetAllows() throws Exception {
        gateway = new UdpGateway(0, "127.0.0.1", UNLISTENED_DRONE_PORT, "", "0.0.0.0",
                false, 100, ingest, pendings);
        try (DatagramSocket peerA = newDrone(); DatagramSocket peerB = newDrone()) {
            droneSaysHeartbeat(peerA, 7, 1);
            droneSaysHeartbeat(peerB, 8, 1);
            awaitHandleCount(2);

            for (int i = 0; i < 3; i++) {
                gateway.send(9, droneHeartbeat(9, 10 + i));
            }
            Thread.sleep(250);

            assertThat(countDelivered(peerA)).isEqualTo(3);
            assertThat(countDelivered(peerB)).isEqualTo(3);
        }
    }

    /** 把 socket 缓冲里能收到的包数取出（非阻塞式：接收超时 150ms 后停）。 */
    private int countDelivered(DatagramSocket drone) {
        int n = 0;
        try {
            drone.setSoTimeout(150);
            while (n < 50) {
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                drone.receive(p);
                n++;
            }
        } catch (Exception e) {
            // 超时/关闭即为读完
        }
        return n;
    }

    @Test
    @DisplayName("learn 过的 sysid 仍是单发（不因广播改动而重复投递）")
    void learnedSysidStaysSingleTargeted() throws Exception {
        gateway = newGateway();
        try (DatagramSocket peerA = newDrone(); DatagramSocket peerB = newDrone()) {
            droneSaysHeartbeat(peerA, 7, 1);
            droneSaysHeartbeat(peerB, 8, 1);
            awaitHandleCount(2);

            gateway.send(7, droneHeartbeat(7, 2));

            // peerA 收到 1 帧，peerB 一帧都不收（sysid=7 有定向路由）
            drain(peerA);
            assertThat(countDelivered(peerB)).as("有定向路由的 sysid 不得广播").isZero();
        }
    }

    private void drain(DatagramSocket drone) {
        try {
            drone.setSoTimeout(150);
            while (true) {
                byte[] buf = new byte[2048];
                drone.receive(new DatagramPacket(buf, buf.length));
            }
        } catch (Exception e) {
            // 读完
        }
    }

    @Test
    @DisplayName("零入站：出站静默丢弃——无已知实例、lastPeer 仍为 null（表②最强形态不变）")
    void zeroInboundSilentlyDropsOutboundDespiteDroneHost() throws Exception {
        gateway = newGateway();

        // 无人入站：lastPeer 从未被设置（drone-host 的 discovery 地址不充当 lastPeer），
        // sendToLastPeer 对 null 静默丢弃——不落 discovery 目标、不抛异常。
        assertThat(gateway.getLastPeer()).as("drone-host 配置不得预置 lastPeer").isNull();
        assertThatCode(() -> gateway.send(9, droneHeartbeat(9, 1)))
                .as("零入站的未知 sysid 出站静默丢弃（transport 对 null peer 不发包）")
                .doesNotThrowAnyException();
        assertThat(gateway.getLastPeer())
                .as("出站动作不得反向制造 lastPeer").isNull();
    }
}
