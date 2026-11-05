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
 * P2-F6 ②「drone-host 未设时出站盲发」的现状钉住测试（{@code docs/sitl-integration.md}
 * §12.4 表②取证，2026-11-06）。
 *
 * <h2>本类钉住什么（以及它不是什么）</h2>
 * 出站 {@code UdpGateway#send(int, MavlinkFrame)} 只有两条路：路由表学到的地址（定向），
 * 或 {@code transport.sendToLastPeer} 兜底。而 {@code lastPeer} 的<b>唯一写点</b>是
 * transport 收包线程（{@code UdpMavlinkTransport} 收包循环），discovery 心跳与定向出站
 * 都<b>不</b>刷新它；从未收到过任何包时 {@code sendToLastPeer} <b>静默丢弃</b>。
 * {@code drone-host} 配置只进 {@code enablePeerDiscovery}（GCS 式发现握手），
 * <b>不参与 send 任何一条路径</b>——即 §12.4 表②的「入站靠学、出站才盲」：
 * 未 learn 的 sysid 的命令帧落在「最后说话者」头上，与 drone-host 配成什么无关；
 * 两个机载实例同机时，只有最后入站的那个收得到未知 sysid 的命令。
 *
 * <p><b>这是取证钉住，不是产品意图。</b>修复②（未知 sysid 对所有已知实例定向广播 +
 * 帧率预算按实例摊分）须与修①一起按 §12.4「三条判据」在真机双链路验收；在那之前
 * 这些测试红了 = 出站行为被改动，须回读 §12.4 判断是修复到达判据、还是误动。
 *
 * <h2>手法</h2>
 * 与 {@link UdpGatewayTest} 同款：transport 无法注入（构造内 new），故用真实 ephemeral
 * UDP 端口 + Mockito mock 下游；另起绑定随机端口的 DatagramSocket 充当「机载实例」，
 * 以收帧归属验证出站落点。签名默认关闭，出站帧字节与入参帧逐字节一致，可直接比对。
 */
@DisplayName("UdpGateway P2-F6②：出站兜底=lastPeer 的取证钉住（drone-host 不参与 send 路径）")
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
    @DisplayName("未 learn 的 sysid：出站落 lastPeer——最后说话者拿走命令，不是广播（§12.4 表②取证）")
    void unknownSysidFallsToLastPeerWhichIsTheLastSpeaker() throws Exception {
        gateway = newGateway();
        try (DatagramSocket peerA = newDrone(); DatagramSocket peerB = newDrone()) {
            droneSaysHeartbeat(peerA, 7, 1);
            droneSaysHeartbeat(peerB, 8, 1);
            awaitHandleCount(2);

            // sysid=9 从未入站：send(9,…) 查不到路由 ⇒ sendToLastPeer（=peerB，最后入站者）
            gateway.send(9, droneHeartbeat(9, 2));

            byte[] atB = tryReceive(peerB);
            assertThat(atB).as("未知 sysid 的命令帧落在最后说话者（peerB）")
                    .isEqualTo(droneHeartbeat(9, 2).encodeV2());

            byte[] atA = tryReceive(peerA);
            assertThat(atA).as("不得广播给所有已 learn 实例——只有最后说话者收到").isNull();
        }
    }

    @Test
    @DisplayName("零入站：出站静默丢弃——drone-host 地址不兜底、lastPeer 保持 null（表②的最强形态）")
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
