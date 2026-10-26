package io.aerofleet.cloud.surveillance.onvif;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OnvifSoapClient} 测试。
 *
 * <p>HTTP 层用真的 {@link HttpServer}（随机端口）而不是 mock：这里要验的正是
 * "发出去的 SOAP 能不能被设备侧验签"。服务端<b>独立</b>用 {@link MessageDigest}
 * 重算 digest，不复用被测实现——否则等于自己验自己。
 *
 * <p>digest 算法另有一条"已知答案"用例（固定 nonce/created/password，
 * 期望值由 Python hashlib 独立算得），防止两边一起错。
 */
@DisplayName("OnvifSoapClient SOAP/WS-Security")
class OnvifSoapClientTest {

    private static final String USER = "admin";
    private static final String PASS = "pass123";

    private HttpServer server;
    private String serviceUrl;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<Function<String, Response>> responder = new AtomicReference<>();

    private record Response(int status, String body) {
    }

    private OnvifSettings settings;

    @BeforeEach
    void startServer() throws Exception {
        settings = new OnvifSettings();
        settings.setConnectTimeoutMs(2000);
        settings.setReadTimeoutMs(3000);
        settings.setMaxResponseBytes(1024 * 1024);
        responder.set(body -> new Response(200, envelope("<tds:GetCapabilitiesResponse/>")));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/onvif/device_service", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.add(body);
            Response response = responder.get().apply(body);
            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
            exchange.sendResponseHeaders(response.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.start();
        serviceUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/onvif/device_service";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ===== digest =====

    @Test
    @DisplayName("PasswordDigest 已知答案：nonce=0..15 / created=2026-10-26T09:00:00Z / pass123")
    void passwordDigest_knownAnswer() {
        byte[] nonce = new byte[16];
        for (int i = 0; i < 16; i++) {
            nonce[i] = (byte) i;
        }

        String digest = OnvifSoapClient.passwordDigest(nonce, "2026-10-26T09:00:00Z", PASS);

        // 期望值由 Python: base64(sha1(bytes(range(16)) + created + password)) 独立算得
        assertThat(digest).isEqualTo("FAvQfPiTB2jO5bPuYoA3DSGSL0Q=");
    }

    @Test
    @DisplayName("信封：带 UsernameToken/Digest/Nonce/Created，用户名转义，明文密码不出现")
    void envelope_containsSecurityHeader() {
        byte[] nonce = new byte[16];
        String created = "2026-10-26T09:00:00Z";
        String envelope = OnvifSoapClient.buildEnvelope("a&b", PASS, nonce, created,
                "<tds:GetCapabilities/>");

        assertThat(envelope)
                .contains("<wsse:Username>a&amp;b</wsse:Username>")
                .contains("#PasswordDigest")
                .contains(Base64.getEncoder().encodeToString(nonce))
                .contains("<wsu:Created>" + created + "</wsu:Created>")
                .contains("s:mustUnderstand=\"1\"");
        assertThat(envelope).doesNotContain(PASS);
    }

    @Test
    @DisplayName("真实 HTTP：服务端独立重算 digest 必须一致（nonce 用原始字节，不是 Base64 文本）")
    void call_digestVerifiableByServer() {
        responder.set(body -> {
            Document request = OnvifXml.parse(body.getBytes(StandardCharsets.UTF_8), "request");
            String nonceB64 = OnvifXml.text(
                    OnvifXml.firstDescendant(request.getDocumentElement(), "Nonce"));
            String created = OnvifXml.text(
                    OnvifXml.firstDescendant(request.getDocumentElement(), "Created"));
            String digest = OnvifXml.text(
                    OnvifXml.firstDescendant(request.getDocumentElement(), "Password"));
            String expected = independentDigest(Base64.getDecoder().decode(nonceB64), created, PASS);
            if (!expected.equals(digest)) {
                return new Response(401, envelope(fault("NotAuthorized")));
            }
            return new Response(200, envelope("<tds:GetCapabilitiesResponse/>"));
        });

        Document document = new OnvifSoapClient(settings)
                .call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS);

        assertThat(document.getDocumentElement()).isNotNull();
        assertThat(receivedBodies).hasSize(1);
        assertThat(receivedBodies.get(0)).contains("<tds:GetCapabilities/>");
    }

    @Test
    @DisplayName("注入固定时钟与 nonce：请求里的 Created/Nonce 与注入值一致")
    void call_usesInjectedClockAndNonce() {
        byte[] nonce = new byte[16];
        for (int i = 0; i < 16; i++) {
            nonce[i] = (byte) (i + 1);
        }
        OnvifSoapClient client = new OnvifSoapClient(HttpClient.newHttpClient(), settings,
                () -> Instant.parse("2026-10-26T09:00:00.987Z"), () -> nonce);

        client.call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS);

        String body = receivedBodies.get(0);
        // 截断到秒（ONVIF 设备的 digest 校验按秒级 Created）
        assertThat(body).contains("<wsu:Created>2026-10-26T09:00:00Z</wsu:Created>");
        assertThat(body).contains(Base64.getEncoder().encodeToString(nonce));
    }

    // ===== 错误路径 =====

    @Test
    @DisplayName("HTTP 500：抛 OnvifException 且带状态码，消息含响应片段")
    void call_http500_throwsWithStatus() {
        responder.set(body -> new Response(500, "<html>boom</html>"));

        assertThatThrownBy(() -> new OnvifSoapClient(settings)
                .call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("boom")
                .satisfies(e -> assertThat(((OnvifException) e).httpStatus()).isEqualTo(500));
    }

    @Test
    @DisplayName("SOAP Fault：抛出设备给出的原因，不当作成功")
    void call_soapFault_throwsWithReason() {
        responder.set(body -> new Response(200, envelope(fault("NotAuthorized"))));

        assertThatThrownBy(() -> new OnvifSoapClient(settings)
                .call(serviceUrl, "GetStreamUri", "<trt:GetStreamUri/>", USER, PASS))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("SOAP fault")
                .hasMessageContaining("NotAuthorized");
    }

    @Test
    @DisplayName("响应含 DOCTYPE：拒绝解析（不读外部实体），错误里说明解析失败")
    void call_doctypeResponse_rejected() {
        responder.set(body -> new Response(200,
                "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                        + envelope("<tds:GetCapabilitiesResponse>&xxe;</tds:GetCapabilitiesResponse>")));

        assertThatThrownBy(() -> new OnvifSoapClient(settings)
                .call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("failed to parse");
    }

    @Test
    @DisplayName("响应体超过上限：读取阶段就失败，不把大包整个读进内存")
    void call_oversizedResponse_throws() {
        settings.setMaxResponseBytes(32);
        responder.set(body -> new Response(200, "x".repeat(256)));

        assertThatThrownBy(() -> new OnvifSoapClient(settings)
                .call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("连不上：抛 OnvifException 并保留原因，不静默返回空文档")
    void call_unreachable_throws() {
        settings.setConnectTimeoutMs(300);

        assertThatThrownBy(() -> new OnvifSoapClient(settings)
                .call("http://127.0.0.1:1/onvif/device_service", "GetCapabilities",
                        "<tds:GetCapabilities/>", USER, PASS))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("cannot reach");
    }

    @Test
    @DisplayName("HTTP 300 也算失败：2xx 才算成功（设备固件返回重定向必须显式报错）")
    void call_http300_throws() {
        responder.set(body -> new Response(300, "redirect?"));

        assertThatThrownBy(() -> new OnvifSoapClient(settings)
                .call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("HTTP 300");
    }

    @Test
    @DisplayName("maxResponseBytes=0 表示不设上限：响应体能完整读完")
    void call_zeroLimit_isUnlimited() {
        settings.setMaxResponseBytes(0);
        responder.set(body -> new Response(200, envelope("<tds:GetCapabilitiesResponse/>")));

        Document document = new OnvifSoapClient(settings)
                .call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS);

        assertThat(document.getDocumentElement()).isNotNull();
    }

    @Test
    @DisplayName("响应体恰好等于上限：放行（超限才报错，误在边界拒绝会把正常设备挡掉）")
    void call_bodyExactlyAtLimit_accepted() {
        String body = envelope("<tds:GetCapabilitiesResponse/>");
        int exact = body.getBytes(StandardCharsets.UTF_8).length;
        settings.setMaxResponseBytes(exact);
        responder.set(b -> new Response(200, body));

        Document document = new OnvifSoapClient(settings)
                .call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS);

        assertThat(document.getDocumentElement()).isNotNull();
    }

    @Test
    @DisplayName("错误消息片段：恰好 300 字符不截断，301 字符才追加省略标记")
    void call_errorSnippet_boundary() {
        responder.set(body -> new Response(500, "a".repeat(300)));
        assertThatThrownBy(() -> new OnvifSoapClient(settings)
                .call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("a".repeat(300))
                .hasMessageNotContaining("\u2026");

        responder.set(body -> new Response(500, "b".repeat(301)));
        assertThatThrownBy(() -> new OnvifSoapClient(settings)
                .call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("b".repeat(300))
                .hasMessageNotContaining("b".repeat(301))
                .hasMessageContaining("\u2026");
    }

    @Test
    @DisplayName("默认 nonce 源：每次调用产出不同且非全零的 16 字节 nonce（不可预测性契约）")
    void call_defaultNonceSource_distinctAndNonZero() {
        OnvifSoapClient client = new OnvifSoapClient(settings);

        for (int i = 0; i < 5; i++) {
            client.call(serviceUrl, "GetCapabilities", "<tds:GetCapabilities/>", USER, PASS);
        }

        assertThat(receivedBodies).hasSize(5);
        java.util.Set<String> nonces = new java.util.HashSet<>();
        for (String body : receivedBodies) {
            String nonceB64 = body.replaceAll("(?s).*<wsse:Nonce[^>]*>", "")
                    .replaceAll("(?s)</wsse:Nonce>.*", "");
            byte[] nonce = Base64.getDecoder().decode(nonceB64);
            assertThat(nonce).hasSize(16);
            assertThat(nonce).isNotEqualTo(new byte[16]); // 全零 = 随机源没 feeding
            assertThat(nonces.add(nonceB64)).as("nonce 必须每次不同").isTrue();
        }
    }

    // ===== 工具 =====

    private static String independentDigest(byte[] nonce, String created, String password) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(nonce);
            sha1.update(created.getBytes(StandardCharsets.UTF_8));
            sha1.update(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String envelope(String body) {
        return "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\""
                + " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""
                + " xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                + "<s:Body>" + body + "</s:Body></s:Envelope>";
    }

    private static String fault(String reason) {
        return "<s:Fault><s:Code><s:Value>s:Sender</s:Value></s:Code>"
                + "<s:Reason><s:Text xml:lang=\"en\">" + reason + "</s:Text></s:Reason></s:Fault>";
    }
}
