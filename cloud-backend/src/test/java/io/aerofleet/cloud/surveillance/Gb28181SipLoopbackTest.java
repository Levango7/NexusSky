package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GB28181 SIP 传输真链路测试：{@link Gb28181SipTransport} ↔ {@link Gb28181DeviceSimulator}。
 *
 * <p>**为什么这样测**：SIP 传输此前被记作"生产阶段"，于是「帧形状对不对」这件事
 * **没有任何执行面能证伪**——拼出来的字符串哪怕 Content-Length 是错的，单测也只断言
 * "包含某几个头"。本测试把两边都跑在**真 UDP socket** 上（127.0.0.1 随机端口），
 * 事务按 Call-ID + CSeq + Via branch 真实关联。
 *
 * <p>对端是**进程内的 GB28181 设备替身**，不是大疆官方模拟器；它证明的是
 * "我们的传输与帧能被一个独立 SIP 栈收发"，**不证明真机联调**——那仍需硬件
 * （见 {@link Gb28181Adapter#streamUrlOrigin()} 的 DESCRIPTIVE_ONLY 口径）。
 *
 * <p>纪律：无匹配响应 ⇒ {@link Gb28181SipTransport.SipTimeoutException}，**不返回假成功**。
 */
@DisplayName("GB28181 SIP 真链路：transport ↔ 设备侧对端（真 UDP 回环）")
class Gb28181SipLoopbackTest {

    /**
     * 事务等待上限。
     *
     * <p>**为什么是 3s 而不是更大**：PIT 的 per-test 超时默认 **4s**。
     * 正断言等的是"必然会到的响应"，正常情况下几毫秒就返回——这个上限只在
     * **对端真的坏了**（或变异体把传输破坏了）时才被等满。等满时：
     * < 4s ⇒ 测试跑完并**失败** ⇒ 变异体被**杀掉**（KILLED）；
     * ≥ 4s ⇒ PIT 判 **TIMED_OUT** ⇒ 变异体既没被杀也不计入存活，
     * 白白压低变异分数（实测：放到 15s 后本地 TIMED_OUT 从 12 跳到 31、
     * 分数从 0.9354 掉到 0.9209）。
     *
     * <p>所以这里**不能**为了"CI 慢机器更稳"而放大窗口：放得越大，
     * 越多本该被杀的变异体变成超时。CI 上那 0.88pp 的落差来自别处
     * （已定位并修掉：{@code Gb28181SipTransport} 那个没有用途的
     * {@code setSoTimeout}，见变异文档第六轮 b）。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private Gb28181Signaling sig;
    private Gb28181DeviceSimulator device;
    private Gb28181SipTransport transport;
    private Gb28181DeviceId cameraId;

    @BeforeEach
    void boot() throws Exception {
        cameraId = Gb28181DeviceId.of("34010000001", "10", "132", "0001");
        device = new Gb28181DeviceSimulator(0, cameraId, "127.0.0.1:5060", 4000, 2);
        device.start();
        transport = new Gb28181SipTransport(0);
        transport.start();
        // signaling 的 Via 要写真实源端口——transport 起好了再建
        bindSignalingToTransport();
    }

    @AfterEach
    void shutdown() {
        if (transport != null) {
            transport.close();
        }
        if (device != null) {
            device.close();
        }
    }

    /** transport 的本地端口确定后重建 signaling（SIP 的 Via 要写真实源端口）。 */
    private void bindSignalingToTransport() {
        sig = new Gb28181Signaling("34010000002000000001",
                "127.0.0.1:" + transport.localPort(), "cli-1");
    }

    private Gb28181SipMessage transact(Gb28181SipMessage req) throws IOException {
        return transport.transact(device.address(), req, TIMEOUT);
    }

    // ------------------------------------------------------------------
    // 注册握手（401 challenge → 200 OK）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REGISTER 首帧 → 401 + WWW-Authenticate（真 UDP 上的挑战）")
    void registerGetsChallenge() throws Exception {
        bindSignalingToTransport();
        Gb28181SipMessage reg = sig.register(cameraId, 3600, 1);

        Gb28181SipMessage resp = transact(reg);

        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(resp.firstHeader("WWW-Authenticate")).contains("Digest").contains("nonce=");
        // 事务键必须对得上：Call-ID + CSeq + 方法（否则收到也归属不了）
        assertThat(resp.transactionKey()).isEqualTo(reg.transactionKey());
    }

    @Test
    @DisplayName("带 Authorization 再 REGISTER → 200 OK（握手完成）")
    void registerWithAuthorizationOk() throws Exception {
        bindSignalingToTransport();
        Gb28181SipMessage first = sig.register(cameraId, 3600, 1);
        Gb28181SipMessage challenge = transact(first);
        assertThat(challenge.statusCode()).isEqualTo(401);

        Gb28181SipMessage second = sig.register(cameraId, 3600, 2)
                .header("Authorization", "Digest username=\"" + cameraId.value()
                        + "\", realm=\"127.0.0.1:5060\", nonce=\"sim-nonce\", uri=\"sip:"
                        + sig.sipDomain() + "\", response=\"deadbeef\"");
        Gb28181SipMessage ok = transact(second);

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.transactionKey()).isEqualTo(second.transactionKey());
    }

    // ------------------------------------------------------------------
    // 目录查询（MANSCDP XML 应答真被解析成设备条目）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Catalog 查询 → 200 OK + MANSCDP，解析出两条通道")
    void catalogQueryYieldsChannels() throws Exception {
        bindSignalingToTransport();
        Gb28181SipMessage resp = transact(sig.catalogQuery(cameraId, 1));

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.bodyText()).contains("<CmdType>Catalog</CmdType>");

        Gb28181CatalogParser.CatalogResult catalog =
                new Gb28181CatalogParser().parse(resp.bodyText());
        assertThat(catalog.items()).hasSize(2);
        assertThat(catalog.items().get(0).deviceId()).isEqualTo("34010000001101320001");
    }

    // ------------------------------------------------------------------
    // 点播（100 Trying → 200 OK + 设备侧 SDP）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("INVITE → 100 Trying 与 200 OK+SDP 都到齐，SDP 可解析出媒体参数")
    void inviteGetsProvisionalAndOkWithSdp() throws Exception {
        bindSignalingToTransport();
        Gb28181SipMessage invite = sig.inviteStream(cameraId, 1, "127.0.0.1", 20000, 0x11223344);

        Gb28181SipMessage ok = transact(invite);

        // transact 返回首条匹配响应；两条都到（100 + 200），第二条按同样事务键续取
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.bodyText()).contains("m=video");
        assertThat(ok.firstHeader("Content-Type")).isEqualTo("application/sdp");

        Gb28181Sdp deviceSdp = Gb28181Sdp.parse(ok.bodyText());
        assertThat(deviceSdp.receiveHost()).isEqualTo("127.0.0.1");
        assertThat(deviceSdp.receivePort()).isEqualTo(4000);
        assertThat(Gb28181Sdp.rtpmapOf(ok.bodyText()))
                .containsEntry(Gb28181Sdp.PAYLOAD_TYPE_PS, "PS/90000");

        // 100 Trying 也真的到了（按同一事务键在已收队列里）
        boolean sawTrying = transport.received().stream()
                .anyMatch(m -> m.statusCode() == 100 && m.transactionKey().equals(invite.transactionKey()));
        assertThat(sawTrying).isTrue();
    }

    @Test
    @DisplayName("INVITE→200 OK→ACK→BYE→200 OK：dialog 完整收尾")
    void fullInviteDialogCompletes() throws Exception {
        bindSignalingToTransport();
        Gb28181SipMessage invite = sig.inviteStream(cameraId, 1, "127.0.0.1", 20001, 0x0A0B0C0D);
        Gb28181SipMessage ok = transact(invite);
        assertThat(ok.statusCode()).isEqualTo(200);

        // ACK 不发响应：设备侧记到流水即可
        transport.send(device.address(), sig.ack(invite, ok));
        awaitDeviceSaw("ACK");

        // BYE → 200 OK（新事务，新 branch）
        Gb28181SipMessage bye = sig.bye(invite, ok);
        Gb28181SipMessage byeOk = transact(bye);
        assertThat(byeOk.statusCode()).isEqualTo(200);
        assertThat(byeOk.transactionKey()).isEqualTo(bye.transactionKey());
        // 事务流水是诊断面：dialog 走完必须留下 ACK/BYE 痕迹（不是空表冒充"跑过了"）
        assertThat(device.transactionLog()).contains("ACK");
    }

    // ------------------------------------------------------------------
    // 无假成功
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未知方法：设备不回，事务按超时收尾（不做假成功）")
    void unknownMethodTimesOutWithoutFakeSuccess() throws Exception {
        bindSignalingToTransport();
        Gb28181SipMessage bogus = Gb28181SipMessage.request(
                "OPTIONS sip:x@y SIP/2.0", null)
                .header("Via", "SIP/2.0/UDP 127.0.0.1:" + transport.localPort()
                        + ";branch=" + Gb28181Signaling.newBranch())
                .header("Call-ID", "bogus-1")
                .header("CSeq", "1 OPTIONS");

        assertThatThrownBy(() -> transact(bogus))
                .isInstanceOf(Gb28181SipTransport.SipTimeoutException.class)
                .hasMessageContaining("no final response matched");
    }

    @Test
    @DisplayName("对端不可达（端口无人听）：同样超时，不造成功")
    void unreachablePeerTimesOut() throws Exception {
        bindSignalingToTransport();
        java.net.InetSocketAddress dead = new java.net.InetSocketAddress("127.0.0.1", 1);
        Gb28181SipMessage ping = sig.register(cameraId, 3600, 1);

        assertThatThrownBy(() -> transport.transact(dead, ping, Duration.ofMillis(600)))
                .isInstanceOf(Gb28181SipTransport.SipTimeoutException.class);
    }

    // ------------------------------------------------------------------
    // 帧纪律在真链路上的投影
    // ------------------------------------------------------------------

    @Test
    @DisplayName("链路两端的帧都能按 Content-Length 完整读出（无挂起/截断）")
    void contentLengthDisciplineHoldsOnTheWire() throws Exception {
        bindSignalingToTransport();
        Gb28181SipMessage invite = sig.inviteStream(cameraId, 1, "127.0.0.1", 20002, 12345);

        Gb28181SipMessage ok = transact(invite);
        assertThat(ok.statusCode()).isEqualTo(200);
        // 设备回给我们的帧：body 必须**完整**（SDP 收尾齐 v= 起点与 y= 终点，
        // 少一个字节都说明 Content-Length 与真实长度不一致）
        assertThat(ok.bodyText()).startsWith("v=0").endsWith("y=168496141\r\n\r\n");
        assertThat(Gb28181Sdp.parse(ok.bodyText()).ssrc()).isEqualTo(0x0A0B0C0D);

        // 我们发给设备的帧：设备侧收到的 body 也完整
        awaitDeviceSaw("INVITE");
        List<Gb28181SipMessage> got = device.received().stream()
                .filter(m -> "INVITE".equals(m.method())).toList();
        assertThat(got).hasSize(1);
        assertThat(got.get(0).bodyText()).startsWith("v=0").endsWith("y=12345\r\n\r\n");
        assertThat(Gb28181Sdp.parse(got.get(0).bodyText()).ssrc()).isEqualTo(12345);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void awaitDeviceSaw(String method) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            boolean saw = device.received().stream().anyMatch(m -> method.equals(m.method()));
            if (saw) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("device never saw method " + method);
    }
}
