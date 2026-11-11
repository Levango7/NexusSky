package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GB28181 适配器**变异门禁收口**用例（第二轮）。
 *
 * <h2>为什么单独一个类</h2>
 *
 * {@link Gb28181AdapterWiringTest} 钉的是"功能对不对"；本类钉的是"**行为有没有被盯住**"。
 * PIT 在 surveillance 包上跑出 28 个存活变异体落在本次接线的新代码上，其中最要紧的一类是
 * <b>事件订阅的真路径从来没被执行过</b>：此前只测了 {@code deliverNotify()} 这个接缝，
 * 而生产路径是「datagram → transport 收流线程 → onMessage 回调 → dispatchNotify → 订阅回调」。
 * {@code transport()} 里那两行（{@code t.onMessage(...)} / {@code t.start()}）被改坏，
 * 没有任何用例会红——**这不是"没测到"，是"测了没盯住"**。
 *
 * <h2>本类钉住的不变式</h2>
 *
 * <ol>
 *   <li>SSRC 是**已知答案**（测试内用独立实现重算 + 钉死具体常量）：哈希常量或
 *       通道混合被改，SDP 的 {@code y=} 与 URI 的 {@code ssrc=} 立刻对不上；</li>
 *   <li>真实 socket 进来的 NOTIFY **必须**触发订阅回调（杀 transport 的两处 + dispatchNotify）；</li>
 *   <li>退订**必须**真发 Expires:0 的 SUBSCRIBE（不是只清本地状态）；</li>
 *   <li>close() **必须**真的关掉传输层、清掉订阅（否则 close 之后的 NOTIFY 仍会回调）；</li>
 *   <li>越界/空参这类边界必须被拒或返回 null，而不是抛或越界。</li>
 * </ol>
 */
@DisplayName("gb28181 adapter mutation closure: 行为必须被盯住")
class Gb28181AdapterMutationClosureTest {

    private static final String DEVICE_ID = "34010000001101320001";

    private Gb28181DeviceSimulator device;
    private Gb28181Adapter adapter;

    @BeforeEach
    void boot() throws Exception {
        device = new Gb28181DeviceSimulator(0, Gb28181DeviceId.parse(DEVICE_ID),
                "127.0.0.1:5060", 0, 2);
        device.start();
        adapter = new Gb28181Adapter(new Gb28181CatalogParser(),
                "34010000002000000001", "127.0.0.1:5060");
    }

    @AfterEach
    void stop() {
        if (adapter != null) {
            adapter.close();
        }
        if (device != null) {
            device.close();
        }
    }

    /** 等设备模拟器收到满足条件的 SIP 消息（它的读线程是异步的，不能当即断言）。 */
    private java.util.List<Gb28181SipMessage> awaitDeviceMsgs(
            java.util.function.Predicate<Gb28181SipMessage> p, int n, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        java.util.List<Gb28181SipMessage> got = java.util.List.of();
        while (System.currentTimeMillis() < deadline) {
            got = device.received().stream().filter(p).toList();
            if (got.size() >= n) {
                return got;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return got;
            }
        }
        return got;
    }

    private SurveillanceDevice gbDevice() {
        return new SurveillanceDevice(DEVICE_ID, "测试相机",
                SurveillanceDevice.Vendor.GB28181, "127.0.0.1",
                device.port(), "sip", "none");
    }

    // ==================================================================
    // ① SSRC 已知答案（杀 ssrcOf 的 4 个 Math + 1 个 Negate）
    // ==================================================================

    /**
     * 独立重算 SSRC 的参考实现（FNV-1a + 通道混合）——与生产实现**分开写**，
     * 任何一侧的算法漂移都会立刻对不上。
     */
    private static int expectedSsrc(String deviceId, int channel) {
        int h = 0x811C9DC5;
        for (int i = 0; i < deviceId.length(); i++) {
            h ^= deviceId.charAt(i);
            h *= 0x01000193;
        }
        return (h ^ (channel * 0x9E3779B1)) & 0x7FFFFFFF;
    }

    @Test
    @DisplayName("SSRC 是已知答案：哈希常量或通道混合被改 ⇒ 立刻对不上")
    void ssrcIsKnownAnswer() {
        // 钉死的具体常量（与独立实现一致）
        assertThat(expectedSsrc(DEVICE_ID, 1)).isEqualTo(345860835);
        assertThat(expectedSsrc(DEVICE_ID, 2)).isEqualTo(918872112);

        // 生产实现必须与参考实现逐值相等（3 个通道全查）
        adapter.learnContact(DEVICE_ID, device.address());
        for (int ch = 1; ch <= 3; ch++) {
            String uri = adapter.getStreamUrl(gbDevice(), ch);
            int actual = Integer.parseInt(uri.substring(uri.indexOf("ssrc=") + 5));
            assertThat(actual)
                    .as("通道 %d 的 SSRC 与参考实现不一致", ch)
                    .isEqualTo(expectedSsrc(DEVICE_ID, ch));
            // 且必须是正数（& 0x7FFFFFFF 的语义：负数说明掩码被改）
            assertThat(actual).isPositive();
        }
    }

    @Test
    @DisplayName("SSRC 落在 SDP 的 y= 行上（设备侧 y= 与 RTP 头 SSRC 必须一致）")
    void ssrcLandsInSdp() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());
        String uri = adapter.getStreamUrl(d, 1);
        int ssrc = Integer.parseInt(uri.substring(uri.indexOf("ssrc=") + 5));

        List<Gb28181SipMessage> invites = awaitDeviceMsgs(
                m -> "INVITE".equals(m.methodOfCseq()), 1, 5000);
        assertThat(invites).as("INVITE 必须真发到设备").hasSize(1);
        assertThat(invites.get(0).bodyText())
                .as("SDP 的 y= 行必须与 URI 的 ssrc 一致，否则设备侧丢帧")
                .contains("y=" + ssrc);
    }

    // ==================================================================
    // ② 真实 socket 的 NOTIFY 真路径（杀 transport 的 onMessage/start + dispatchNotify）
    // ==================================================================

    @Test
    @DisplayName("真实 socket 进来的 NOTIFY ⇒ 订阅回调必须被触发（真路径，不是接缝）")
    void realDatagramNotifyTriggersCallback() throws Exception {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());

        var fired = new CopyOnWriteArrayList<String>();
        adapter.subscribeEvents(d, fired::add);

        // 传输层已经真开起来了（端口 > 0）
        int port = adapter.transportLocalPort();
        assertThat(port).isGreaterThan(0);

        // 取 SUBSCRIBE 的 Call-ID（同订阅的 NOTIFY 与 SUBSCRIBE 共用 Call-ID，RFC 3265）
        List<Gb28181SipMessage> subs = awaitDeviceMsgs(
                m -> "SUBSCRIBE".equals(m.methodOfCseq()), 1, 5000);
        assertThat(subs).as("SUBSCRIBE 必须真发到设备").hasSize(1);
        String callId = subs.get(0).firstHeader("Call-ID");

        // 真发一个 UDP datagram 到适配器的 SIP 监听端口
        String notify = "NOTIFY sip:" + DEVICE_ID + "@34010000002000000001 SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:" + device.port() + ";branch=z9hG4bK776\r\n"
                + "From: <sip:" + DEVICE_ID + "@34010000002000000001>\r\n"
                + "To: <sip:34010000002000000001@34010000002000000001>\r\n"
                + "Call-ID: " + callId + "\r\n"
                + "CSeq: 1 NOTIFY\r\n"
                + "Event: presence\r\n"
                + "Content-Type: Application/MANSCDP+xml\r\n"
                + "Content-Length: 8\r\n"
                + "\r\n"
                + "<Alarm/>";
        byte[] bytes = notify.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.send(new DatagramPacket(bytes, bytes.length,
                    new InetSocketAddress("127.0.0.1", port)));
        }

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && fired.isEmpty()) {
            Thread.sleep(20);
        }
        assertThat(fired)
                .as("真实 socket → transport 收流线程 → onMessage → dispatchNotify → 回调，"
                        + "这条链任何一环被改坏都必须红")
                .isNotEmpty();
        assertThat(fired.get(0)).contains("<Alarm/>");
    }

    @Test
    @DisplayName("Call-ID 对不上的 NOTIFY ⇒ 不得触发任何回调（路由必须按 Call-ID）")
    void notifyWithUnknownCallIdDoesNotFire() throws Exception {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());
        var fired = new CopyOnWriteArrayList<String>();
        adapter.subscribeEvents(d, fired::add);

        int port = adapter.transportLocalPort();
        String notify = "NOTIFY sip:x@34010000002000000001 SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:" + device.port() + ";branch=z9hG4bK777\r\n"
                + "Call-ID: totally-unknown-call-id\r\n"
                + "CSeq: 1 NOTIFY\r\n"
                + "Content-Length: 8\r\n"
                + "\r\n"
                + "<Alarm/>";
        byte[] bytes = notify.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.send(new DatagramPacket(bytes, bytes.length,
                    new InetSocketAddress("127.0.0.1", port)));
        }

        long deadline = System.currentTimeMillis() + 800;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(fired).isEmpty();
    }

    // ==================================================================
    // ③ 退订必须真发信（杀 unsubscribeEvents 的 VoidMethodCall + Negate）
    // ==================================================================

    @Test
    @DisplayName("退订 ⇒ 真发一条 Expires:0 的 SUBSCRIBE（不是只清本地状态）")
    void unsubscribeReallySendsExpiresZero() throws Exception {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());
        String handle = adapter.subscribeEvents(d, s -> { });

        awaitDeviceMsgs(m -> "SUBSCRIBE".equals(m.methodOfCseq()), 1, 5000);
        adapter.unsubscribeEvents(handle);

        List<Gb28181SipMessage> subs = awaitDeviceMsgs(
                m -> "SUBSCRIBE".equals(m.methodOfCseq()), 2, 5000);
        assertThat(subs)
                .as("退订必须真的发一条 SUBSCRIBE（Expires: 0），否则设备侧仍会推事件")
                .hasSizeGreaterThanOrEqualTo(2);
        Gb28181SipMessage last = subs.get(subs.size() - 1);
        assertThat(last.firstHeader("Expires")).isEqualTo("0");
    }

    @Test
    @DisplayName("退订未知 handle ⇒ 不发信、不抛（幂等）")
    void unsubscribeUnknownHandleSendsNothing() throws Exception {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());
        adapter.subscribeEvents(d, s -> { });

        awaitDeviceMsgs(m -> "SUBSCRIBE".equals(m.methodOfCseq()), 1, 5000);
        long before = device.received().stream()
                .filter(m -> "SUBSCRIBE".equals(m.methodOfCseq())).count();

        adapter.unsubscribeEvents("gb28181-sub-not-a-real-handle");

        Thread.sleep(300);
        long after = device.received().stream()
                .filter(m -> "SUBSCRIBE".equals(m.methodOfCseq())).count();
        assertThat(after)
                .as("未知 handle 不得触发任何 SUBSCRIBE")
                .isEqualTo(before);
    }

    // ==================================================================
    // ④ close 必须真关传输层 + 清订阅（杀 close 的 VoidMethodCall）
    // ==================================================================

    @Test
    @DisplayName("close ⇒ 订阅被清空（之后的 NOTIFY 不得再触发回调）")
    void closeClearsSubscribers() throws Exception {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());
        var fired = new CopyOnWriteArrayList<String>();
        adapter.subscribeEvents(d, fired::add);

        List<Gb28181SipMessage> subs = awaitDeviceMsgs(
                m -> "SUBSCRIBE".equals(m.methodOfCseq()), 1, 5000);
        assertThat(subs).as("SUBSCRIBE 必须真发到设备").hasSize(1);
        String callId = subs.get(0).firstHeader("Call-ID");

        adapter.close();

        // close 之后把同 Call-ID 的 NOTIFY 递进来：回调不得再触发
        Gb28181SipMessage notify = Gb28181SipMessage.request("NOTIFY x SIP/2.0", "<Alarm/>".getBytes())
                .header("Call-ID", callId)
                .header("CSeq", "1 NOTIFY");
        adapter.deliverNotify(notify);
        Thread.sleep(200);
        assertThat(fired)
                .as("close 之后不得再有订阅回调（close 必须清掉订阅表）")
                .isEmpty();
    }

    @Test
    @DisplayName("close ⇒ SIP 传输层必须真被关掉（socket 泄漏 = 端口漏 + 收流线程悬挂）")
    void closeReallyClosesTransport() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());
        adapter.subscribeEvents(d, s -> { });
        assertThat(adapter.transportClosed())
                .as("未关闭时传输层不得谎报已关闭")
                .isFalse();

        adapter.close();

        assertThat(adapter.transportClosed())
                .as("close 之后传输层的 UDP socket 必须已关闭（否则端口漏、收流线程悬挂）")
                .isTrue();
    }

    @Test
    @DisplayName("close ⇒ 订阅表被清空（退订已知 handle 时日志落在「未知 handle」而不是「地址未知」）")
    void closeClearsSubscriptionTable() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());
        String handle = adapter.subscribeEvents(d, s -> { });

        adapter.close();

        try (LogWiretap tap = LogWiretap.on(Gb28181Adapter.class)) {
            adapter.unsubscribeEvents(handle);
            assertThat(tap.lines())
                    .as("close 之后订阅表已清 ⇒ 退订旧 handle 必须落在「未知 handle」分支")
                    .anyMatch(l -> l.contains("unknown handle"));
        }
    }

    @Test
    @DisplayName("close ⇒ 再点播会懒开新的传输层（端口必须换新）")
    void closeAllowsLazyTransportRecreation() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());
        adapter.subscribeEvents(d, s -> { });
        int first = adapter.transportLocalPort();
        assertThat(first).isGreaterThan(0);

        adapter.close();
        assertThat(adapter.transportLocalPort())
                .as("已关闭的传输层不得再对外报端口")
                .isEqualTo(-1);

        // 重新登记并订阅 ⇒ 传输层懒开新实例（端口不同 ⇒ 旧的那个确实被关掉了）
        adapter.learnContact(DEVICE_ID, device.address());
        adapter.subscribeEvents(d, s -> { });
        int second = adapter.transportLocalPort();
        assertThat(second).isGreaterThan(0);
        assertThat(second).isNotEqualTo(first);
        assertThat(adapter.transportClosed()).isFalse();
    }

    // ==================================================================
    // ⑤ 边界：空参、越界、会话未开
    // ==================================================================

    @Test
    @DisplayName("learnContact 拒绝空白 deviceId，但正常 deviceId 必须放行")
    void learnContactRejectsBlank() {
        assertThatThrownBy(() -> adapter.learnContact("  ",
                new InetSocketAddress("127.0.0.1", 5060)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.learnContact(null,
                new InetSocketAddress("127.0.0.1", 5060)))
                .isInstanceOf(IllegalArgumentException.class);
        // 空白 id 不得被登记（否则一次误调用就把设备"登记"成空串）
        assertThat(adapter.knowsContact("  ")).isFalse();

        // **正常 id 必须放行**：只测"该抛的抛了"抓不住"条件取反"型变异体
        // （取反后只有非空白才抛 ⇒ 空白反而通过 ⇒ 上面的断言会红，但正常 id 也会红）
        adapter.learnContact(DEVICE_ID, device.address());
        assertThat(adapter.knowsContact(DEVICE_ID)).isTrue();
    }

    @Test
    @DisplayName("mediaFrame 越界或未开会话 ⇒ 返回 null（不抛）")
    void mediaFrameOutOfRangeReturnsNull() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());
        adapter.getStreamUrl(d, 1);

        assertThat(adapter.mediaFrame(DEVICE_ID, 1, 0)).isNull();
        assertThat(adapter.mediaFrame(DEVICE_ID, 1, -1)).isNull();
        assertThat(adapter.mediaFrame(DEVICE_ID, 99, 0)).isNull();
    }

    @Test
    @DisplayName("媒体会话在 close 之前 isClosed=false（close 之后才 true）")
    void mediaSessionIsClosedOnlyAfterClose() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(DEVICE_ID, device.address());
        adapter.getStreamUrl(d, 1);
        List<Gb28181MediaSession> sessions = adapter.mediaSessionsSnapshot();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).isClosed())
                .as("未关闭的会话 isClosed 必须是 false（返回 true 等于谎报）")
                .isFalse();
        adapter.close();
        assertThat(sessions.get(0).isClosed()).isTrue();
    }
}
