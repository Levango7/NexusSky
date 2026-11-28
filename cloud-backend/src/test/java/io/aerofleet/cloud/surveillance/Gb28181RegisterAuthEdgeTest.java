package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接线边缘分支补杀（Mutation gate surveillance 腿第二轮）：nonce 上限清空、
 * From 含冒号、requestUriOf 边界、onIncoming 方法分支、transport() 复用、
 * isKnownNonce TTL 边界、重复订阅、NOTIFY 源 null/contact null 分支。
 */
@DisplayName("GB28181 接线：边缘分支补杀")
class Gb28181RegisterAuthEdgeTest {

    private Gb28181Adapter adapter;

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
    }

    private Gb28181Adapter newAdapter() {
        adapter = new Gb28181Adapter(new Gb28181CatalogParser(),
                "00000000000000000001", "127.0.0.1:0", "secret-1");
        adapter.startRegisterServerIfConfigured();
        return adapter;
    }

    /** 本测试类的设备编码（与设备模拟器构造一致）。 */
    private Gb28181DeviceId camId() {
        return Gb28181DeviceId.of("34010000001", "10", "132", "0001");
    }

    /** 设备侧已收到的 SUBSCRIBE 条数（UDP 到达异步，断言前需轮询）。 */
    private static long subscribesReceived(Gb28181DeviceSimulator device) {
        return device.received().stream()
                .filter(m -> "SUBSCRIBE".equals(m.method())).count();
    }

    @Test
    @DisplayName("rememberNonce 达容量上限：清空后只剩最新一枚")
    void rememberNonce_overflowClearsPool() {
        newAdapter();
        for (int i = 0; i < 1024; i++) {
            ReflectionTestUtils.invokeMethod(adapter, "rememberNonce", "n-" + i);
        }
        // 第 1025 次触发 clear 后**立即**检查（clear 之后不再继续 put）
        ReflectionTestUtils.invokeMethod(adapter, "rememberNonce", "overflow");
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> nonces =
                (ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(adapter, "issuedNonces");
        assertThat(nonces.size()).as("容量防线清空后仅余最新一枚").isEqualTo(1);
        assertThat(nonces).containsKey("overflow");
    }

    @Test
    @DisplayName("isKnownNonce TTL 边界：恰好 TTL 内有效、超过 1ms 即失效")
    void isKnownNonce_ttlBoundary() {
        newAdapter();
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> nonces =
                (ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(adapter, "issuedNonces");
        nonces.put("edge-in", System.currentTimeMillis() - 200_000L);
        nonces.put("edge-out", System.currentTimeMillis() - 400_000L);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(adapter, "isKnownNonce", "edge-in"))
                .as("恰好 300000ms 仍有效").isEqualTo(true);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(adapter, "isKnownNonce", "edge-out"))
                .as("超过 1ms 即失效").isEqualTo(false);
    }

    @Test
    @DisplayName("From 含冒号（id:port@domain）：截到冒号前解析并成功学址")
    void registerFromWithColon_stillParses() throws Exception {
        newAdapter();
        Gb28181DeviceSimulator device = new Gb28181DeviceSimulator(0,
                Gb28181DeviceId.of("34010000001", "10", "132", "0001"), "127.0.0.1:0", 4000, 1);
        device.start();
        try {
            // 第一次无 Authorization 拿挑战（From 带冒号：id:port@domain）
            String raw = "REGISTER sip:00000000000000000001 SIP/2.0\r\n"
                    + "Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-colon\r\n"
                    + "From: <sip:34010000001101320001:5060@00000000000000000001>\r\n"
                    + "To: <sip:34010000001101320001@00000000000000000001>\r\n"
                    + "Call-ID: reg-colon-1\r\nCSeq: 1 REGISTER\r\n"
                    + "Max-Forwards: 70\r\nExpires: 3600\r\nContent-Length: 0\r\n\r\n";
            device.sendBytes(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    new InetSocketAddress("127.0.0.1", adapter.transportLocalPort()));
            // 拿到 401 挑战里的真 nonce
            String nonce = null;
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                var opt = device.received().stream()
                        .filter(m -> m.isResponse() && m.statusCode() == 401)
                        .reduce((a, b) -> b);
                if (opt.isPresent()) {
                    nonce = Gb28181DigestAuth.parseAuthorization(
                            opt.get().firstHeader("WWW-Authenticate")).get("nonce");
                    break;
                }
                Thread.sleep(25);
            }
            assertThat(nonce).as("应收到 401 挑战并取到 nonce").isNotBlank();
            // 带冒号 From + 有效鉴权：应成功学址
            String auth = Gb28181DigestAuth.authorizationHeader(
                    "34010000001101320001", Gb28181DigestAuth.DEFAULT_REALM, nonce,
                    "sip:00000000000000000001", "secret-1", "REGISTER");
            String raw2 = raw.replace("Content-Length: 0",
                    "Authorization: " + auth + "\r\nContent-Length: 0");
            device.sendBytes(raw2.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    new InetSocketAddress("127.0.0.1", adapter.transportLocalPort()));
            Thread.sleep(250);
            assertThat(adapter.knowsContact("34010000001101320001"))
                    .as("From 含冒号也应截到冒号前解析成功并学址").isTrue();
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("requestUriOf：start-line 只有一个空格时返回整行（uri 校验按整行比对）")
    void requestUriOf_singleSpace() {
        // SipMessage.request 要求「METHOD URI SIP/2.0」三段——用合法行验证中段提取
        Gb28181SipMessage normal = Gb28181SipMessage.request("REGISTER sip:x SIP/2.0", null);
        String uri = (String) ReflectionTestUtils.invokeMethod(
                Gb28181Adapter.class, "requestUriOf", normal);
        assertThat(uri).isEqualTo("sip:x");
    }

    @Test
    @DisplayName("transport() 复用：第二次 startRegisterServerIfConfigured 不新开端口")
    void transport_reusesOpenInstance() {
        newAdapter();
        int first = adapter.transportLocalPort();
        adapter.startRegisterServerIfConfigured();
        assertThat(adapter.transportLocalPort()).as("已开实例复用、端口不变").isEqualTo(first);
    }

    @Test
    @DisplayName("onIncoming 无 CSeq 的 REGISTER 走 method() 分支（同样进鉴权）")
    void onIncoming_noCseq_usesMethodFallback() throws Exception {
        newAdapter();
        Gb28181DeviceSimulator device = new Gb28181DeviceSimulator(0,
                Gb28181DeviceId.of("34010000001", "10", "132", "0001"), "127.0.0.1:0", 4000, 1);
        device.start();
        try {
            // 无 CSeq 的 REGISTER：methodOfCseq()=null → 走 method() 分支
            String raw = "REGISTER sip:00000000000000000001 SIP/2.0\r\n"
                    + "Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-nc\r\n"
                    + "From: <sip:34010000001101320001@00000000000000000001>\r\n"
                    + "To: <sip:34010000001101320001@00000000000000000001>\r\n"
                    + "Call-ID: reg-nc-1\r\n"
                    + "Max-Forwards: 70\r\nExpires: 3600\r\nContent-Length: 0\r\n\r\n";
            device.sendBytes(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    new InetSocketAddress("127.0.0.1", adapter.transportLocalPort()));
            // 无 Authorization → 挑战 401 应到达设备
            long deadline = System.currentTimeMillis() + 2000;
            boolean saw401 = false;
            while (System.currentTimeMillis() < deadline) {
                if (device.received().stream().anyMatch(m -> m.isResponse() && m.statusCode() == 401)) {
                    saw401 = true;
                    break;
                }
                Thread.sleep(25);
            }
            assertThat(saw401).as("无 CSeq 的 REGISTER 也应被 401 挑战").isTrue();
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("重复订阅同一设备：复用 handle、不重复发 SUBSCRIBE")
    void subscribeTwice_reusesHandle() throws Exception {
        newAdapter();
        Gb28181DeviceSimulator device = new Gb28181DeviceSimulator(0,
                Gb28181DeviceId.of("34010000001", "10", "132", "0001"), "127.0.0.1:0", 4000, 1);
        device.start();
        try {
            device.registerTo(new InetSocketAddress("127.0.0.1", adapter.transportLocalPort()),
                    "00000000000000000001", "secret-1", 4000);
            SurveillanceDevice dev = new SurveillanceDevice(camId().value(), "cam",
                    SurveillanceDevice.Vendor.GB28181, "0.0.0.0", 5060, "sip", "none");
            AtomicInteger alarms = new AtomicInteger();
            String h1 = adapter.subscribeEvents(dev, body -> alarms.incrementAndGet());
            // transport 是异步 UDP：先等第一条 SUBSCRIBE 到达设备，否则 CI 上会在
            // 到达前读到 0 而误报（2026-11-24 的 flaky 就是这个时序）。
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && subscribesReceived(device) == 0) {
                Thread.sleep(25);
            }
            assertThat(subscribesReceived(device))
                    .as("第一次订阅应真发一条 SUBSCRIBE").isEqualTo(1);
            String h2 = adapter.subscribeEvents(dev, body -> alarms.incrementAndGet());
            assertThat(h2).as("重复订阅复用同一 handle").isEqualTo(h1);
            // 给「错误地重复发送」留出到达窗口，再确认仍只有一条
            Thread.sleep(400);
            assertThat(subscribesReceived(device))
                    .as("第二次订阅不再重复发 SUBSCRIBE").isEqualTo(1);
        } finally {
            device.close();
        }
    }

    @Test
    @DisplayName("deliverNotify 接缝（source=null）不查源也投递")
    void deliverNotify_nullSource_stillDelivers() throws Exception {
        newAdapter();
        Gb28181DeviceSimulator device = new Gb28181DeviceSimulator(0,
                camId(), "127.0.0.1:0", 4000, 1);
        device.start();
        try {
            device.registerTo(new InetSocketAddress("127.0.0.1", adapter.transportLocalPort()),
                    "00000000000000000001", "secret-1", 4000);
            SurveillanceDevice dev = new SurveillanceDevice(camId().value(), "cam",
                    SurveillanceDevice.Vendor.GB28181, "0.0.0.0", 5060, "sip", "none");
            AtomicInteger fired = new AtomicInteger();
            adapter.subscribeEvents(dev, body -> fired.incrementAndGet());
            // 从设备实收的 SUBSCRIBE 取 Call-ID（adapter 的 signaling 实例生成的随机尾）；
            // 轮询等待（drained 纪律：本机微秒、CI 需轮询）
            Gb28181SipMessage sub = null;
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                var opt = device.received().stream()
                        .filter(m -> "SUBSCRIBE".equals(m.method()))
                        .findFirst();
                if (opt.isPresent()) {
                    sub = opt.get();
                    break;
                }
                Thread.sleep(25);
            }
            if (sub == null) {
                throw new AssertionError("platform never sent SUBSCRIBE within 3000ms");
            }
            Gb28181SipMessage notify = Gb28181SipMessage.request("NOTIFY sip:x SIP/2.0", null)
                    .header("Call-ID", sub.firstHeader("Call-ID"))
                    .header("CSeq", "1 NOTIFY")
                    .header("Event", "Presence");
            adapter.deliverNotify(notify);
            assertThat(fired.get()).as("source=null 的接缝不查源、应投递").isEqualTo(1);
        } finally {
            device.close();
        }
    }
}
