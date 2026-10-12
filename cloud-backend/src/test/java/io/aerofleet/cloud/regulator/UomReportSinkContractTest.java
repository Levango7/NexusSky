package io.aerofleet.cloud.regulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aerofleet.cloud.regulator.model.ActivationRequest;
import io.aerofleet.cloud.regulator.model.ActivationResult;
import io.aerofleet.cloud.regulator.model.FlightStatus;
import io.aerofleet.cloud.regulator.model.TelemetryReport;
import io.aerofleet.cloud.regulator.model.VerifyRequest;
import io.aerofleet.cloud.regulator.model.VerifyResult;
import io.aerofleet.cloud.regulator.model.VerifyStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UomReportSink} 的**契约测试**：拿 JDK 内置 {@link HttpServer} 当假 UOM 平台，
 * 把真实平台才暴露的那几类缺陷（报文形状、鉴权头、签名口径、重试判断）钉在单测里。
 *
 * <p><b>为什么单独写这个而不是复用 {@code SimReportSinkTest}</b>：两个 sink 的重试与响应
 * 解析各写一份（{@code ReportRetryPolicy} 是 2026-10-12 才把判定收敛到一处的），
 * sim 侧的绿**推不出** uom 侧的绿——SPI 门禁登记的就是这种"编译通过、启动通过、
 * 但没有任何断言点名过"的适配器。
 *
 * <p><b>断言刻意分成"看得见的请求"与"看得见的次数"</b>：
 * 重试类断言一律显式 {@code setMaxRetry(>0)}——在 {@code maxRetry=0} 下"只发一次请求"
 * 无论实现对错都成立，那是本仓已经在 CHANGELOG 里记过一次的空转断言形状。
 */
@DisplayName("UomReportSink 对真实 UOM 平台的上报契约（假 UOM + 真 HTTP）")
class UomReportSinkContractTest {

    private static final String API_KEY = "uom-api-key-0123456789";
    private static final String SIGNING_KEY = "uom-hmac-secret";

    private HttpServer server;
    private ObjectMapper mapper;
    private RegulatorConfig config;
    private int port;

    /** 收到的每一个请求（顺序即发生顺序）——"重试了几次"只能由它证明。 */
    private final List<Recorded> requests = new ArrayList<>();
    /** 每个请求该回什么：按调用顺序弹出，用尽后重复最后一个。 */
    private final List<Resp> script = new ArrayList<>();
    private int cursor;

    private record Recorded(String method, String path, String body, Map<String, List<String>> headers) {
        String first(String name) {
            List<String> v = headers.get(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    private record Resp(int status, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper();
        requests.clear();
        script.clear();
        cursor = 0;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();

        config = new RegulatorConfig();
        config.setUomBaseUrl("http://127.0.0.1:" + port);
        config.setHttpTimeoutMs(2000);
        config.setApiKey(API_KEY);
        config.setSigningKey(SIGNING_KEY);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().toString(), body,
                ex.getRequestHeaders()));
        Resp resp = cursor < script.size() ? script.get(cursor) : lastScripted();
        cursor++;
        byte[] out = resp.body() == null ? new byte[0] : resp.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(resp.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
        ex.close();
    }

    private Resp lastScripted() {
        return script.isEmpty() ? new Resp(200, "{}") : script.get(script.size() - 1);
    }

    private UomReportSink sink() {
        return new UomReportSink(config, mapper);
    }

    // ───────────────────────── 报文形状与鉴权 ─────────────────────────

    @Test
    @DisplayName("1. 激活上报：POST /api/activate 的线格式（路径、方法、Content-Type、字段、API Key 头）")
    void activationWireFormat() {
        config.setMaxRetry(0);
        script.add(new Resp(200, "{\"success\":true,\"activationId\":\"ACT-77\"}"));

        ActivationResult r = sink().reportActivation(
                new ActivationRequest(7, "SN-0007", 1_700_000_000_000L, 31.23, 121.47));

        assertThat(r.success()).isTrue();
        assertThat(r.activationId()).isEqualTo("ACT-77");
        assertThat(r.errorMessage()).isNull();

        assertThat(requests).hasSize(1);
        Recorded req = requests.get(0);
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/api/activate");
        assertThat(req.first("Content-Type")).contains("application/json");
        assertThat(req.first("X-API-Key")).isEqualTo(API_KEY);

        JsonNode sent = parse(req.body());
        assertThat(sent.path("serialNo").asText()).isEqualTo("SN-0007");
        assertThat(sent.path("activationTime").asLong()).isEqualTo(1_700_000_000_000L);
        assertThat(sent.path("lat").asDouble()).isEqualTo(31.23);
        assertThat(sent.path("lon").asDouble()).isEqualTo(121.47);
    }

    @Test
    @DisplayName("2. 签名口径可被平台方复算：X-Signature == HMAC-SHA256(METHOD\\nPATH\\nTS\\nBODY) Base64")
    void signatureIsRecomputableByThePlatform() throws Exception {
        config.setMaxRetry(0);
        script.add(new Resp(200, "{\"success\":true,\"activationId\":\"ACT-1\"}"));

        sink().reportActivation(new ActivationRequest(1, "SN-1", 1L, 0.0, 0.0));

        Recorded req = requests.get(0);
        String ts = req.first("X-Timestamp");
        assertThat(ts).as("配了 signingKey 就必须带时间戳头").isNotBlank();
        String expected = hmac(SIGNING_KEY,
                "POST\n" + req.path() + "\n" + ts + "\n" + req.body());
        assertThat(req.first("X-Signature"))
                .as("签名串错一位，平台侧就整批 401；这条断言就是防这个")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("3. 不配 signingKey 时不发签名头：避免「配了空串也照样签」这种半开状态")
    void noSignatureHeaderWhenSigningKeyAbsent() {
        config.setMaxRetry(0);
        config.setSigningKey(null);
        script.add(new Resp(200, "{\"success\":true,\"activationId\":\"ACT-2\"}"));

        sink().reportActivation(new ActivationRequest(2, "SN-2", 1L, 0.0, 0.0));

        Recorded req = requests.get(0);
        assertThat(req.first("X-Signature")).isNull();
        assertThat(req.first("X-Timestamp")).isNull();
        assertThat(req.first("X-API-Key")).isEqualTo(API_KEY);
    }

    // ───────────────────────── 重试语义（终态 vs 可重试） ─────────────────────────

    @Test
    @DisplayName("4. 403 是终态：maxRetry=3 也只发 1 次，且结果判为失败")
    void client403IsTerminalNotRetried() {
        config.setMaxRetry(3);
        script.add(new Resp(403, "{\"success\":false,\"reason\":\"未实名注册\"}"));

        ActivationResult r = sink().reportActivation(new ActivationRequest(3, "SN-3", 1L, 0.0, 0.0));

        assertThat(r.success()).isFalse();
        assertThat(requests)
                .as("4xx 重试救不回来，只会把同一请求打 3 遍（约 7s）并被平台判滥用")
                .hasSize(1);
    }

    @Test
    @DisplayName("5. 503 可重试：第二次成功后不再继续，结果取第二次的响应体")
    void server503IsRetriedAndCanSucceed() {
        config.setMaxRetry(1);
        script.add(new Resp(503, "upstream busy"));
        script.add(new Resp(200, "{\"success\":true,\"activationId\":\"ACT-5\"}"));

        ActivationResult r = sink().reportActivation(new ActivationRequest(5, "SN-5", 1L, 0.0, 0.0));

        assertThat(r.success()).isTrue();
        assertThat(r.activationId()).isEqualTo("ACT-5");
        assertThat(requests).hasSize(2);
    }

    @Test
    @DisplayName("6. 429 虽属 4xx 但必须重试（它带的是配额语义，不是请求被拒）")
    void http429IsRetried() {
        config.setMaxRetry(1);
        script.add(new Resp(429, "{\"success\":false,\"reason\":\"quota\"}"));
        script.add(new Resp(200, "{\"success\":true,\"activationId\":\"ACT-6\"}"));

        ActivationResult r = sink().reportActivation(new ActivationRequest(6, "SN-6", 1L, 0.0, 0.0));

        assertThat(r.success()).isTrue();
        assertThat(requests).hasSize(2);
    }

    // ───────────────────────── 解析与脱敏 ─────────────────────────

    @Test
    @DisplayName("7. 实名查询：状态解析 + 所有者脱敏在 sink 内完成（不能把原名交给调用方）")
    void verifyMasksOwnerName() {
        config.setMaxRetry(0);
        script.add(new Resp(200, "{\"status\":\"VERIFIED\",\"owner\":\"张三\",\"registerDate\":\"2026-01-02\"}"));

        VerifyResult r = sink().verifyRealNameStatus(new VerifyRequest(9, "SN-9", "CERT-9"));

        assertThat(r.status()).isEqualTo(VerifyStatus.VERIFIED);
        assertThat(r.ownerName()).isEqualTo("张*");
        assertThat(r.registerDate()).isEqualTo("2026-01-02");
        assertThat(requests.get(0).path())
                .as("GET /api/verify 要带两个查询参数")
                .isEqualTo("/api/verify?serialNo=SN-9&certNo=CERT-9");
    }

    @Test
    @DisplayName("8. 平台给了不认识的 status 串 ⇒ ERROR，而不是抛异常或当成 VERIFIED")
    void unknownStatusMapsToError() {
        config.setMaxRetry(0);
        script.add(new Resp(200, "{\"status\":\"WHATEVER\",\"owner\":\"李四\"}"));

        VerifyResult r = sink().verifyRealNameStatus(new VerifyRequest(10, "SN-10", "CERT-10"));

        assertThat(r.status()).isEqualTo(VerifyStatus.ERROR);
    }

    @Test
    @DisplayName("9. 平台不可达：以 errorMessage 传达且不抛出（调用方是上报线程，抛出去会吞掉整批）")
    void unreachableReportsViaErrorMessage() {
        int deadPort = port;
        server.stop(0);
        config.setMaxRetry(0);

        ActivationResult r = sink().reportActivation(new ActivationRequest(11, "SN-11", 1L, 0.0, 0.0));

        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("不可达");
    }

    @Test
    @DisplayName("10. 遥测上报带飞行态：status 用枚举名、经纬高/速度/航向逐字段落在线格式里")
    void telemetryCarriesFlightState() {
        config.setMaxRetry(0);
        script.add(new Resp(200, "{\"success\":true}"));

        var r = sink().reportTelemetry(new TelemetryReport(12, "SN-12", 1_700_000_001L,
                31.1, 121.2, 88.5, 12.5, 270.0, FlightStatus.AIRBORNE));

        assertThat(r.success()).isTrue();
        JsonNode sent = parse(requests.get(0).body());
        assertThat(sent.path("sysid").asInt()).isEqualTo(12);
        assertThat(sent.path("serialNo").asText()).isEqualTo("SN-12");
        assertThat(sent.path("alt").asDouble()).isEqualTo(88.5);
        assertThat(sent.path("speed").asDouble()).isEqualTo(12.5);
        assertThat(sent.path("heading").asDouble()).isEqualTo(270.0);
        assertThat(sent.path("status").asText()).isEqualTo("AIRBORNE");
        assertThat(requests.get(0).path()).isEqualTo("/api/telemetry");
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("请求体不是合法 JSON: " + body, e);
        }
    }

    private static String hmac(String key, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
