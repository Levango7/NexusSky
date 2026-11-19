package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接线分支覆盖补充（Mutation gate surveillance 腿：把新增代码的存活变异体杀掉）。
 * <p>
 * 每个用例钉一个此前未覆盖的分支，命名即被测分支——变异器（ConditionalsBoundary /
 * NegateConditionals / MathMutator / VoidMethodCall / EmptyObjectReturnVals 等）
 * 在这些点上翻过/改过都必须转红。
 */
@DisplayName("GB28181 接线：鉴权/通知分支覆盖补充")
class Gb28181RegisterAuthBranchTest {

    private Gb28181Adapter adapter;
    private Gb28181DeviceSimulator device;

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
        device = new Gb28181DeviceSimulator(0,
                Gb28181DeviceId.of("34010000001", "10", "132", "0001"), "127.0.0.1:0", 4000, 1);
        device.start();
        return device;
    }

    private Gb28181Adapter newAdapter() {
        adapter = new Gb28181Adapter(new Gb28181CatalogParser(),
                "00000000000000000001", "127.0.0.1:0", "secret-1");
        adapter.startRegisterServerIfConfigured();
        return adapter;
    }

    /** 直接构造 REGISTER 报文（不经设备模拟器，便于造畸形头）。 */
    private static byte[] registerBytes(String from, String authorization) {
        StringBuilder sb = new StringBuilder();
        sb.append("REGISTER sip:00000000000000000001 SIP/2.0\r\n")
                .append("Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-test\r\n")
                .append("From: ").append(from).append("\r\n")
                .append("To: <sip:34010000001200000001@00000000000000000001>\r\n")
                .append("Call-ID: reg-branch-1\r\n")
                .append("CSeq: 1 REGISTER\r\n")
                .append("Max-Forwards: 70\r\n")
                .append("Expires: 3600\r\n");
        if (authorization != null) {
            sb.append("Authorization: ").append(authorization).append("\r\n");
        }
        sb.append("Content-Length: 0\r\n\r\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void sendToAdapter(byte[] data) throws Exception {
        // 必须用设备主 socket 发：adapter 的 401/200 回给来源地址——临时 socket 没人收，
        // 设备永远看不到挑战（这就是「no 401 challenge seen」的成因）
        device.sendBytes(data, new InetSocketAddress("127.0.0.1", adapter.transportLocalPort()));
        Thread.sleep(250);
    }

    @Test
    @DisplayName("DigestAuth 解析边界：null/非 Digest/空 header 皆空 map；verify 对空 map/空 nonce 拒")
    void digestAuth_parseAndVerifyBoundaries() {
        assertThat(Gb28181DigestAuth.parseAuthorization(null)).isEmpty();
        assertThat(Gb28181DigestAuth.parseAuthorization("Basic dXNlcjpwYXNz")).isEmpty();
        assertThat(Gb28181DigestAuth.parseAuthorization("   ")).isEmpty();

        String uri = "sip:00000000000000000001";
        String realm = Gb28181DigestAuth.DEFAULT_REALM;
        String user = "34010000001200000001";
        String nonce = Gb28181DigestAuth.nonce();
        String header = Gb28181DigestAuth.authorizationHeader(user, realm, nonce, uri, "pw", "REGISTER");
        Map<String, String> auth = Gb28181DigestAuth.parseAuthorization(header);

        assertThat(Gb28181DigestAuth.verify(Map.of(), "REGISTER", uri, realm, user, "pw", nonce))
                .as("空 auth map 拒").isFalse();
        assertThat(Gb28181DigestAuth.verify(auth, "REGISTER", uri, realm, user, "pw", null))
                .as("平台未签发 nonce（null）拒").isFalse();

        Map<String, String> noUser = new java.util.LinkedHashMap<>(auth);
        noUser.remove("username");
        assertThat(Gb28181DigestAuth.verify(noUser, "REGISTER", uri, realm, user, "pw", nonce))
                .as("缺 username 拒").isFalse();
        Map<String, String> noResp = new java.util.LinkedHashMap<>(auth);
        noResp.remove("response");
        assertThat(Gb28181DigestAuth.verify(noResp, "REGISTER", uri, realm, user, "pw", nonce))
                .as("缺 response 拒").isFalse();
        Map<String, String> noNonce = new java.util.LinkedHashMap<>(auth);
        noNonce.remove("nonce");
        assertThat(Gb28181DigestAuth.verify(noNonce, "REGISTER", uri, realm, user, "pw", nonce))
                .as("缺 nonce 拒").isFalse();
        Map<String, String> noUri = new java.util.LinkedHashMap<>(auth);
        noUri.remove("uri");
        assertThat(Gb28181DigestAuth.verify(noUri, "REGISTER", uri, realm, user, "pw", nonce))
                .as("缺 uri 拒").isFalse();

        // response 大小写混合：hex 应大小写不敏感
        Map<String, String> upper = new java.util.LinkedHashMap<>(auth);
        upper.put("response", auth.get("response").toUpperCase(java.util.Locale.ROOT));
        assertThat(Gb28181DigestAuth.verify(upper, "REGISTER", uri, realm, user, "pw", nonce))
                .as("response 大小写不敏感").isTrue();

        // qop 为空串 → 走经典分支（不是 qop 分支）
        Map<String, String> emptyQop = new java.util.LinkedHashMap<>(auth);
        emptyQop.put("qop", "");
        assertThat(Gb28181DigestAuth.verify(emptyQop, "REGISTER", uri, realm, user, "pw", nonce))
                .as("空 qop 走经典分支").isTrue();
    }

    @Test
    @DisplayName("nonce 过期/不存在：isKnownNonce 皆 false")
    void nonceExpiry_isKnownNonceFalse() {
        newAdapter();
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> nonces =
                (ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(adapter, "issuedNonces");
        nonces.put("stale-1", System.currentTimeMillis() - 400_000L);
        nonces.put("fresh-1", System.currentTimeMillis());
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(adapter, "isKnownNonce", "stale-1"))
                .as(">5min 的 nonce 过期").isEqualTo(false);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(adapter, "isKnownNonce", "fresh-1"))
                .as("刚签发的 nonce 有效").isEqualTo(true);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(adapter, "isKnownNonce", "never-issued"))
                .as("未签发过的 nonce 拒").isEqualTo(false);
    }

    @Test
    @DisplayName("From 头无 sip: 或无 @：设备编码解析失败 → 拒且不学址")
    void registerFromMalformed_noContactLearned() throws Exception {
        newAdapter();
        newDevice();
        // 先发一次无 Authorization 的 REGISTER 拿 401 挑战里的真 nonce
        byte[] first = registerBytes("<sip:34010000001200000001@00000000000000000001>", null);
        sendToAdapter(first);
        String realNonce = readNonceFromChallenge();
        String realAuth = Gb28181DigestAuth.authorizationHeader(
                "34010000001200000001", Gb28181DigestAuth.DEFAULT_REALM, realNonce,
                "sip:00000000000000000001", "secret-1", "REGISTER");

        sendToAdapter(registerBytes("<tel:+86100>", realAuth));
        sendToAdapter(registerBytes("<sip:34010000001200000001>", realAuth));
        assertThat(adapter.knowsContact("34010000001200000001")).isFalse();
    }

    @Test
    @DisplayName("Authorization username 与设备编码不符：拒")
    void registerUsernameMismatch_rejected() throws Exception {
        newAdapter();
        newDevice();
        sendToAdapter(registerBytes("<sip:34010000001200000001@00000000000000000001>", null));
        String nonce = readNonceFromChallenge();
        String badAuth = Gb28181DigestAuth.authorizationHeader(
                "34010000001200000002", Gb28181DigestAuth.DEFAULT_REALM, nonce,
                "sip:00000000000000000001", "secret-1", "REGISTER");
        sendToAdapter(registerBytes("<sip:34010000001200000001@00000000000000000001>", badAuth));
        assertThat(adapter.knowsContact("34010000001200000001")).isFalse();
    }

    @Test
    @DisplayName("digest uri 与请求行不一致：拒")
    void registerUriMismatch_rejected() throws Exception {
        newAdapter();
        newDevice();
        sendToAdapter(registerBytes("<sip:34010000001200000001@00000000000000000001>", null));
        String nonce = readNonceFromChallenge();
        String badAuth = Gb28181DigestAuth.authorizationHeader(
                "34010000001200000001", Gb28181DigestAuth.DEFAULT_REALM, nonce,
                "sip:OTHER-DOMAIN", "secret-1", "REGISTER");
        sendToAdapter(registerBytes("<sip:34010000001200000001@00000000000000000001>", badAuth));
        assertThat(adapter.knowsContact("34010000001200000001")).isFalse();
    }

    @Test
    @DisplayName("onIncoming 非 REGISTER/NOTIFY 方法：不处理、不抛、不学址")
    void onIncoming_otherMethod_noAction() throws Exception {
        newAdapter();
        newDevice();
        String invite = "INVITE sip:34010000001200000001@00000000000000000001 SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-x\r\n"
                + "From: <sip:34010000001200000001@00000000000000000001>\r\n"
                + "To: <sip:34010000001200000001@00000000000000000001>\r\n"
                + "Call-ID: inv-1\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n";
        sendToAdapter(invite.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(adapter.knowsContact("34010000001200000001")).isFalse();
    }

    @Test
    @DisplayName("Signaling.callIdSuffix：非空、长度=9、hex 小写、两次不同")
    void callIdSuffix_shape() {
        String a = Gb28181Signaling.callIdSuffixForTest();
        String b = Gb28181Signaling.callIdSuffixForTest();
        assertThat(a).isNotBlank().hasSize(9).startsWith("-");
        assertThat(a.substring(1)).matches("[0-9a-f]{8}");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("SipTransport.onMessageWithSource：回调收到消息且带来源地址")
    void sourceListener_receivesSourceAddress() throws Exception {
        Gb28181SipTransport t = new Gb28181SipTransport(0);
        t.start();
        try {
            AtomicInteger calls = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<InetSocketAddress> seen =
                    new java.util.concurrent.atomic.AtomicReference<>();
            t.onMessageWithSource((m, from) -> {
                calls.incrementAndGet();
                seen.set(from);
            });
            byte[] msg = "OPTIONS sip:x SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:1;branch=z9hG4bK-o\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (DatagramSocket sender = new DatagramSocket()) {
                sender.send(new DatagramPacket(msg, msg.length,
                        new InetSocketAddress("127.0.0.1", t.localPort())));
            }
            long deadline = System.currentTimeMillis() + 2000;
            while (calls.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertThat(calls.get()).isEqualTo(1);
            assertThat(seen.get()).isNotNull();
            assertThat(seen.get().getAddress().isLoopbackAddress()).isTrue();
        } finally {
            t.close();
        }
    }

    /** 从设备收到的 401 里取平台签发的真实 nonce（供后续带鉴权请求使用）。 */
    private String readNonceFromChallenge() {
        return device.received().stream()
                .filter(m -> m.isResponse() && m.statusCode() == 401)
                .reduce((a, b) -> b)
                .map(m -> Gb28181DigestAuth.parseAuthorization(
                        m.firstHeader("WWW-Authenticate")).get("nonce"))
                .orElseThrow(() -> new AssertionError("no 401 challenge seen"));
    }
}
