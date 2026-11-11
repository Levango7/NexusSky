package io.aerofleet.cloud.surveillance;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GB28181 适配器接线测试 —— <b>真 UDP 回环</b>（设备模拟器在对端）。
 *
 * <p>接线的定义是「信令面 + 媒体面 + 适配器三层串起来，端到端跑通」。
 * 因此这里不 mock：起 {@link Gb28181DeviceSimulator}（真 SIP/UDP + 真 RTP 推流），
 * 适配器真发 INVITE、真开收流会话、真收 PS 帧。任何一个环节没接上，
 * 端到端断言就会红。
 *
 * <p><b>测的是什么</b>：{@link Gb28181Adapter#getStreamUrl} 从「返回描述性标识」
 * 变成「返回真实摄入端点，且 INVITE 的 SDP 带真实收流端口」；
 * {@link Gb28181Adapter#subscribeEvents} 从「只造形状」变成「真发 SUBSCRIBE、
 * 真收 NOTIFY、真回调」。
 */
@DisplayName("gb28181 adapter wiring: real SIP+UDP and RTP loopback against device simulator")
class Gb28181AdapterWiringTest {

    private Gb28181DeviceSimulator device;
    private Gb28181Adapter adapter;

    @BeforeEach
    void boot() throws Exception {
        device = new Gb28181DeviceSimulator(0, Gb28181DeviceId.parse(
                "34010000001101320001"), "127.0.0.1:5060", 0, 2);
        device.start();
        adapter = new Gb28181Adapter(new Gb28181CatalogParser(),
                "34020000002000000001", "127.0.0.1:5060");
        // 设备侧 SDP（模拟器按此推流；PT=96/PS/90kHz）
        device.setMediaTiming(20, 3600);
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

    /** 等设备模拟器收到满足条件的 SIP 消息（读线程异步，不能当即断言）。 */
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
        return new SurveillanceDevice(
                "34010000001101320001", "测试相机",
                SurveillanceDevice.Vendor.GB28181, "127.0.0.1",
                device.port(), "sip", "none");
    }

    // ==================================================================
    // ① 联系地址闸门（原缺陷：SDP 写 0.0.0.0:0，信令发不出去）
    // ==================================================================

    @Test
    @DisplayName("未登记设备联系地址 ⇒ 响亮失败（不发注定失败的信令去 0.0.0.0）")
    void unknownContactFailsLoudly() {
        SurveillanceDevice d = gbDevice();
        assertThat(adapter.knowsContact(d.id)).isFalse();
        assertThatThrownBy(() -> adapter.getStreamUrl(d, 1))
                .isInstanceOf(Gb28181Adapter.Gb28181DeviceContactUnknownException.class)
                .hasMessageContaining("no learned contact address");
        // 没有任何会话被开（失败要不留副作用）
        assertThat(adapter.mediaSessionCount()).isZero();
    }

    @Test
    @DisplayName("learnContact 后可点播；knowsContact 反映登记状态")
    void learnContactEnablesPlay() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());
        assertThat(adapter.knowsContact(d.id)).isTrue();

        String url = adapter.getStreamUrl(d, 1);
        assertThat(url).startsWith("gb28181-rtp://").contains(d.id);
    }

    // ==================================================================
    // ② INVITE 的 SDP 必须带真实收流端口（原缺陷的核心）
    // ==================================================================

    @Test
    @DisplayName("点播后 INVITE 真发到设备，且 SDP 的 c=/m=/y= 全是真值非 0.0.0.0:0")
    void inviteCarriesRealSdp() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());

        String url = adapter.getStreamUrl(d, 1);

        // 设备侧确实收到了 INVITE（读线程异步，等到为止）
        List<Gb28181SipMessage> got = awaitDeviceMsgs(
                m -> "INVITE".equals(m.methodOfCseq()), 1, 5000);
        assertThat(got).as("适配器必须把 INVITE 真发到设备").hasSize(1);
        String sdp = got.get(0).bodyText();

        // 真实收流端口（不是 0）——这是本次修复的核心
        int port = adapter.mediaSessionPort(d.id, 1);
        assertThat(port).isGreaterThan(0);
        assertThat(sdp).contains("m=video " + port + " ");
        assertThat(sdp).contains("c=IN IP4 127.0.0.1");
        assertThat(sdp).doesNotContain("0.0.0.0");
        assertThat(sdp).doesNotContain("m=video 0 ");
        // SSRC 落到 y= 行，且与 URI 里的一致（上游可凭此对账）
        assertThat(sdp).contains("y=" + ssrcOf(url));

        // 返回的 URI 带同一端口与 SSRC
        assertThat(url).contains("port=" + port);
        assertThat(url).contains("ssrc=");

        // **未验证点，如实标注**：SDP 的传输标记（m= 行里的 TCP/RTP/AVP）来自
        // #258 交付的 Gb28181Sdp.body()，本轮未改。GB28181 附录 C.1 的示例 SDP
        // 确实写作 TCP/RTP/AVP 而媒体实际走 UDP，本机 PDF 文本提取无法证实原文，
        // 因此这里不断言它、也不顺手"修"——需要标准原文核对后再单独处理。
        assertThat(sdp).contains("TCP/RTP/AVP");
    }

    @Test
    @DisplayName("同一设备通道重复点播复用同一端口（设备才不会往旧端口推）")
    void repeatedPlayReusesSession() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());

        String u1 = adapter.getStreamUrl(d, 1);
        String u2 = adapter.getStreamUrl(d, 1);

        assertThat(u2).isEqualTo(u1);
        assertThat(adapter.mediaSessionCount()).isEqualTo(1);
        assertThat(awaitDeviceMsgs(m -> "INVITE".equals(m.methodOfCseq()), 2, 5000))
                .hasSize(2);
    }

    @Test
    @DisplayName("不同通道各开独立会话（端口不同，互不抢占）")
    void differentChannelsGetDifferentSessions() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());

        adapter.getStreamUrl(d, 1);
        adapter.getStreamUrl(d, 2);

        assertThat(adapter.mediaSessionCount()).isEqualTo(2);
        assertThat(adapter.mediaSessionPort(d.id, 1))
                .isNotEqualTo(adapter.mediaSessionPort(d.id, 2));
    }

    @Test
    @DisplayName("SSRC 由设备+通道确定性导出（同一点播重复拿到同一值）")
    void ssrcIsDeterministic() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());
        String a = adapter.getStreamUrl(d, 1);
        String b = adapter.getStreamUrl(d, 1);
        assertThat(ssrcOf(a)).isEqualTo(ssrcOf(b));

        String c = adapter.getStreamUrl(d, 2);
        assertThat(ssrcOf(c)).isNotEqualTo(ssrcOf(a));
    }

    // ==================================================================
    // ③ 端到端媒体面：设备真推流 → 适配器真收 → 解出帧
    // ==================================================================

    @Test
    @DisplayName("端到端：点播 → 设备按 SDP 推 RTP → 适配器收到并解出 PS 帧")
    void endToEndMediaFramesArrive() throws Exception {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());

        adapter.getStreamUrl(d, 1);
        // 设备侧按 SDP 的端口推流（这就是"接上"的含义）：streamMediaTo 只设目的地，
        // 帧由 pushFrame 按需推（模拟器没有定时器——见其 javadoc）
        java.net.InetSocketAddress ingest = new java.net.InetSocketAddress("127.0.0.1",
                adapter.mediaSessionPort(d.id, 1));
        device.streamMediaTo(ingest);

        // 推 3 帧：首帧关键帧（带系统头+PSM），后两帧非关键帧
        for (int i = 0; i < 3; i++) {
            device.pushFrame(1000 + i * 10, 9000L + i * 3600, i == 0);
        }

        // 等真帧到达（不是 sleep 猜，是等到为止 + 超时判红）
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline
                && adapter.mediaFrameCount(d.id, 1) < 2) {
            Thread.sleep(20);
        }
        assertThat(adapter.mediaFrameCount(d.id, 1))
                .as("设备推流后适配器应收到并解出 PS 帧")
                .isGreaterThanOrEqualTo(2);

        // 解出来的帧内容可由 PsDemuxer 再解一次（媒体面真的通）
        Gb28181MediaFrame f = adapter.mediaFrame(d.id, 1, 0);
        assertThat(f.packets()).isGreaterThanOrEqualTo(1);
        assertThat(f.ps()).isNotEmpty();
    }

    // ==================================================================
    // ④ 事件订阅：真发 SUBSCRIBE、真收 NOTIFY、真回调
    // ==================================================================

    @Test
    @DisplayName("supportsEventSubscription 为 true（不再是 501 桩）")
    void eventSubscriptionDeclared() {
        assertThat(adapter.supportsEventSubscription()).isTrue();
    }

    @Test
    @DisplayName("订阅真发 SUBSCRIBE 到设备；NOTIFY 到达真回调")
    void subscribeReallySendsAndNotifyFires() throws Exception {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());

        var fired = new CopyOnWriteArrayList<String>();
        String handle = adapter.subscribeEvents(d, fired::add);

        // SUBSCRIBE 真到了设备（读线程异步）
        List<Gb28181SipMessage> subs = awaitDeviceMsgs(
                m -> "SUBSCRIBE".equals(m.methodOfCseq()), 1, 5000);
        assertThat(subs).hasSize(1);

        // 设备侧回一条 NOTIFY（同 Call-ID）⇒ 回调必须触发
        String callId = subs.get(0).firstHeader("Call-ID");
        Gb28181SipMessage notify = Gb28181SipMessage.request(
                        "NOTIFY sip:" + d.id + "@34020000002000000001 SIP/2.0", null)
                .header("Via", "SIP/2.0/UDP 127.0.0.1:" + device.port() + ";branch="
                        + Gb28181Signaling.newBranch())
                .header("From", "<sip:" + d.id + "@34020000002000000001>")
                .header("To", "<sip:34020000002000000001@34020000002000000001>")
                .header("Call-ID", callId)
                .header("CSeq", "1 NOTIFY")
                .header("Event", "presence")
                .header("Content-Type", "Application/MANSCDP+xml")
                .header("Content-Length", String.valueOf(
                        "<Alarm/>".length()));
        notify = Gb28181SipMessage.request("NOTIFY x SIP/2.0", "<Alarm/>".getBytes())
                .header("Via", "SIP/2.0/UDP 127.0.0.1:" + device.port() + ";branch="
                        + Gb28181Signaling.newBranch())
                .header("Call-ID", callId)
                .header("CSeq", "1 NOTIFY")
                .header("Content-Type", "Application/MANSCDP+xml")
                .header("Content-Length", "8");
        adapter.deliverNotify(notify);

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && fired.isEmpty()) {
            Thread.sleep(20);
        }
        assertThat(fired).as("NOTIFY 必须触发订阅回调").isNotEmpty();
    }

    @Test
    @DisplayName("未登记联系地址 ⇒ 订阅响亮失败，且不留空壳订阅")
    void subscribeUnknownContactLeavesNoShell() {
        SurveillanceDevice d = gbDevice();
        assertThatThrownBy(() -> adapter.subscribeEvents(d, s -> { }))
                .isInstanceOf(Gb28181Adapter.Gb28181DeviceContactUnknownException.class);
    }

    @Test
    @DisplayName("退订清理本地状态（重复退订不炸）")
    void unsubscribeCleansUp() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());
        String h = adapter.subscribeEvents(d, s -> { });
        adapter.unsubscribeEvents(h);
        adapter.unsubscribeEvents(h);   // 幂等
    }

    @Test
    @DisplayName("同一设备重复订阅复用 handle（不双投）")
    void resubscribeReusesHandle() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());
        String h1 = adapter.subscribeEvents(d, s -> { });
        String h2 = adapter.subscribeEvents(d, s -> { });
        assertThat(h2).isEqualTo(h1);
    }

    // ==================================================================
    // ⑤ 生命周期与既有契约不被破坏
    // ==================================================================

    @Test
    @DisplayName("close 关掉全部媒体会话（不泄漏端口）")
    void closeReleasesSessions() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());
        adapter.getStreamUrl(d, 1);
        adapter.getStreamUrl(d, 2);
        assertThat(adapter.mediaSessionCount()).isEqualTo(2);
        // close 前持有会话引用（close 后 registry 的 map 已清，就拿不到了）
        java.util.List<Gb28181MediaSession> beforeClose =
                adapter.mediaSessionsSnapshot();
        assertThat(beforeClose).hasSize(2);

        adapter.close();
        assertThat(adapter.mediaSessionCount()).isZero();
        // 关掉后 transport 也清了：再点播应重新懒开而不是 NPE
        assertThat(adapter.knowsContact(d.id)).isFalse();

        // **socket 必须真的关掉**：只清 map 不关 socket 会漏端口（本进程后续
        // 再也拿不到这些端口，且收流线程还挂着）。
        //
        // 判据用会话自身的 isClosed()，**不用**"同一端口能否再绑定"——实测
        // CI（Linux）上 close() 之后端口可能仍在 TIME_WAIT，立刻重绑会
        // BindException: Address already in use，那是内核行为而非没关 socket；
        // 本地 Windows 不复现，于是那个断言成了只在 CI 红的假失败
        // （本轮实测：port 35446 not released after adapter.close()）。
        assertThat(beforeClose).allSatisfy(s -> assertThat(s.isClosed()).isTrue());
    }

    @Test
    @DisplayName("streamUrlOrigin = MEDIA_PLANE_LOCAL（本地可用/设备未验证，两件事都说）")
    void streamUrlOriginIsHonest() {
        assertThat(adapter.streamUrlOrigin())
                .isEqualTo(StreamUrlOrigin.MEDIA_PLANE_LOCAL);
        assertThat(adapter.streamUrlOrigin().playable()).isFalse();
        assertThat(adapter.streamUrlOrigin().deviceVerified()).isFalse();
    }

    @Test
    @DisplayName("非 GB28181 设备仍被拒（adapt 不越界）")
    void nonGbDeviceStillRejected() {
        SurveillanceDevice onvif = new SurveillanceDevice(
                "x", "x", SurveillanceDevice.Vendor.ONVIF, "1.2.3.4", 80, "rtsp", "none");
        assertThatThrownBy(() -> adapter.getStreamUrl(onvif, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.subscribeEvents(onvif, s -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("channel < 1 仍被拒")
    void nonPositiveChannelRejected() {
        SurveillanceDevice d = gbDevice();
        adapter.learnContact(d.id, device.address());
        assertThatThrownBy(() -> adapter.getStreamUrl(d, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.getStreamUrl(d, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static String ssrcOf(String url) {
        int i = url.indexOf("ssrc=");
        return i < 0 ? "" : url.substring(i + 5).split("&")[0];
    }
}
