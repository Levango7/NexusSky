package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GB28181 REGISTER 鉴权接线回环测试（威胁建模 ⑤ 接线前置清单①②③的落实证明）：
 * 真实 UDP socket 上跑「设备 REGISTER → 401 挑战 → 带 Digest Authorization → 200 + 学址」，
 * 并钉住 NOTIFY 来源校验（伪造源丢弃）、错口令永不学址、服务关闭时维持 fail-closed。
 */
@DisplayName("GB28181 REGISTER 鉴权接线：401 挑战 → Digest → 学址")
class Gb28181RegisterAuthLoopbackTest {

    private static final String DOMAIN = "00000000000000000001";

    private Gb28181Adapter adapter;
    private Gb28181DeviceSimulator device;
    private Gb28181DeviceId camId;

    @AfterEach
    void tearDown() {
        if (device != null) {
            device.close();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    private Gb28181DeviceSimulator newDevice() throws IOException {
        camId = Gb28181DeviceId.of("34010000001", "10", "132", "0001");
        device = new Gb28181DeviceSimulator(0, camId, "127.0.0.1:0", 4000, 1);
        device.start();
        return device;
    }

    private InetSocketAddress platformAddr() {
        return new InetSocketAddress("127.0.0.1", adapter.transportLocalPort());
    }

    @Test
    @DisplayName("Digest 已知答案：经典形态与 qop 形态可验；错口令/错 nonce/错 uri 拒")
    void digestKnownAnswer() {
        String username = "34020000002000000001";
        String realm = Gb28181DigestAuth.DEFAULT_REALM;
        String nonce = Gb28181DigestAuth.nonce();
        String uri = "sip:" + DOMAIN;
        String header = Gb28181DigestAuth.authorizationHeader(
                username, realm, nonce, uri, "12345678", "REGISTER");
        Map<String, String> auth = Gb28181DigestAuth.parseAuthorization(header);

        assertThat(Gb28181DigestAuth.verify(auth, "REGISTER", uri, realm,
                username, "12345678", nonce)).isTrue();
        assertThat(Gb28181DigestAuth.verify(auth, "REGISTER", uri, realm,
                username, "wrong", nonce)).isFalse();
        assertThat(Gb28181DigestAuth.verify(auth, "REGISTER", uri, realm,
                username, "12345678", Gb28181DigestAuth.nonce())).isFalse();
        assertThat(Gb28181DigestAuth.verify(auth, "REGISTER", "sip:other", realm,
                username, "12345678", nonce)).isFalse();
        assertThat(Gb28181DigestAuth.verify(auth, "REGISTER", uri, realm,
                "34020000002000000002", "12345678", nonce)).isFalse();

        // qop="auth" 形态（RFC 2617 §3.2.1）
        String ha1 = Gb28181DigestAuth.md5Hex(username + ":" + realm + ":12345678");
        String ha2 = Gb28181DigestAuth.md5Hex("REGISTER:" + uri);
        Map<String, String> qopAuth = new java.util.LinkedHashMap<>(auth);
        qopAuth.put("qop", "auth");
        qopAuth.put("nc", "00000001");
        qopAuth.put("cnonce", "abc");
        qopAuth.put("response", Gb28181DigestAuth.md5Hex(
                ha1 + ":" + nonce + ":00000001:abc:auth:" + ha2));
        assertThat(Gb28181DigestAuth.verify(qopAuth, "REGISTER", uri, realm,
                username, "12345678", nonce)).isTrue();
        // 解析：引号值剥引号、键小写
        assertThat(auth.get("username")).isEqualTo(username);
        assertThat(auth).containsKey("response");
    }

    @Test
    @DisplayName("回环：REGISTER → 401 挑战 → 带 Digest → 200 OK + 平台学址")
    void registerDigestHandshakeLearnsContact() throws Exception {
        adapter = newAdapter("secret-1");
        device = newDevice();

        Gb28181SipMessage resp = device.registerTo(platformAddr(), DOMAIN, "secret-1", 4000);

        assertThat(resp.statusCode()).isEqualTo(200);
        // 握手真实发生：先 401 挑战、后 200（不是平台直接放行）
        assertThat(device.received().stream()
                .filter(Gb28181SipMessage::isResponse)
                .map(Gb28181SipMessage::statusCode))
                .containsExactly(401, 200);
        assertThat(adapter.knowsContact(camId.value())).isTrue();
    }

    @Test
    @DisplayName("错口令：永远停在 401 挑战，平台永不学址")
    void wrongPassword_neverLearnsContact() throws Exception {
        adapter = newAdapter("secret-1");
        device = newDevice();

        assertThatThrownBy(() -> device.registerTo(platformAddr(), DOMAIN, "wrong", 2500))
                .isInstanceOf(Gb28181SipTransport.SipTimeoutException.class);
        // 期间只收到过 401
        assertThat(device.received().stream()
                .filter(Gb28181SipMessage::isResponse)
                .map(Gb28181SipMessage::statusCode))
                .containsOnly(401);
        assertThat(adapter.knowsContact(camId.value())).isFalse();
    }

    @Test
    @DisplayName("服务关闭（未配置口令）：不绑端口维持 fail-closed，学址无从发生")
    void serverOff_staysFailClosed() {
        adapter = new Gb28181Adapter(new Gb28181CatalogParser(), DOMAIN, "127.0.0.1:0");
        assertThat(adapter.transportLocalPort()).as("未配置口令不应绑定端口").isEqualTo(-1);
        adapter.startRegisterServerIfConfigured();
        assertThat(adapter.transportLocalPort()).as("空口令下启动仍是 no-op").isEqualTo(-1);
        assertThat(adapter.knowsContact("34010000001200000001")).isFalse();
    }

    @Test
    @DisplayName("NOTIFY 来源校验：已学联系地址送达订阅回调，伪造源丢弃")
    void notifyFromForeignSource_isDropped() throws Exception {
        adapter = newAdapter("secret-1");
        device = newDevice();
        device.registerTo(platformAddr(), DOMAIN, "secret-1", 4000);
        assertThat(adapter.knowsContact(camId.value())).isTrue();

        SurveillanceDevice dev = new SurveillanceDevice(camId.value(), "cam-1",
                SurveillanceDevice.Vendor.GB28181, "0.0.0.0", 5060, "sip", "none");
        AtomicInteger alarms = new AtomicInteger();
        adapter.subscribeEvents(dev, body -> alarms.incrementAndGet());

        // 平台发来的 SUBSCRIBE：取其 Call-ID（NOTIFY 必须回带同一 Call-ID，RFC 3265）。
        // 确定性等待（drained 纪律）：本机 loopback 微秒级即达，CI 调度下需轮询——
        // 不轮询就是 CI 偶发 "platform never sent SUBSCRIBE" 的成因
        Gb28181SipMessage sub = awaitSubscribe(5000);
        String callId = sub.firstHeader("Call-ID");

        // 伪造源（另一 socket、同一 Call-ID）：必须被丢
        try (DatagramSocket spoofer = new DatagramSocket()) {
            Gb28181SipMessage fake = Gb28181SipMessage.request(
                    "NOTIFY sip:" + camId.value() + " SIP/2.0", null)
                    .header("Via", "SIP/2.0/UDP 127.0.0.1:" + spoofer.getLocalPort()
                            + ";branch=" + Gb28181Signaling.newBranch())
                    .header("From", "<sip:" + camId.value() + "@" + DOMAIN + ">")
                    .header("To", "<sip:" + camId.value() + "@" + DOMAIN + ">")
                    .header("Call-ID", callId)
                    .header("CSeq", "1 NOTIFY")
                    .header("Event", "Presence");
            byte[] b = fake.render();
            spoofer.send(new DatagramPacket(b, b.length,
                    new InetSocketAddress("127.0.0.1", adapter.transportLocalPort())));
        }
        Thread.sleep(400);
        assertThat(alarms.get()).as("伪造源的 NOTIFY 不得触发订阅回调").isEqualTo(0);

        // 真源（设备主 socket，= 已学联系地址）：送达
        device.sendNotify(platformAddr(), callId, "<Alarm><Code>1</Code></Alarm>");
        long deadline = System.currentTimeMillis() + 2000;
        while (alarms.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(alarms.get()).as("真源 NOTIFY 应送达订阅回调").isEqualTo(1);
    }

    private Gb28181Adapter newAdapter(String password) {
        Gb28181Adapter a = new Gb28181Adapter(new Gb28181CatalogParser(), DOMAIN, "127.0.0.1:0", password);
        a.startRegisterServerIfConfigured();
        return a;
    }

    /** 确定性等待平台 SUBSCRIBE 到达设备（drained 纪律：本机微秒、CI 需轮询）。 */
    private Gb28181SipMessage awaitSubscribe(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            java.util.Optional<Gb28181SipMessage> hit = device.received().stream()
                    .filter(m -> "SUBSCRIBE".equals(m.method()))
                    .findFirst();
            if (hit.isPresent()) {
                return hit.get();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("platform never sent SUBSCRIBE within " + timeoutMs + "ms");
    }
}
