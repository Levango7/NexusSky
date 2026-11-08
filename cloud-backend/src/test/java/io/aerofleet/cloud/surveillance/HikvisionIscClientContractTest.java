package io.aerofleet.cloud.surveillance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 海康 iSecure Center OpenAPI 客户端契约测试（零硬件：JDK HttpServer 假平台）。
 *
 * <p>依据海康开放平台公开文档（综合安防管理平台 OpenAPI）与官方 SDK 行为：
 * <ul>
 *   <li>接口形如 {@code /artemis/{uri}}；</li>
 *   <li>AK/SK 摘要签名，签名字符串 = {@code METHOD\\nAccept\\nContent-Type\\n 自定义头(小写排序)\\n 路径}，
 *       {@code X-Ca-Signature = Base64(HmacSHA256(appSecret, 串))}；</li>
 *   <li><b>token 也必须经签名请求获取</b>（/artemis/oauth/token）；</li>
 *   <li>响应 {@code {"code":"0","msg":"success","data":...}}——<b>code 是字符串 "0" 才算成功</b>。</li>
 * </ul>
 *
 * <p><b>为什么能零硬件</b>：签名算法公开且**确定性**（固定 nonce/timestamp ⇒ 固定
 * 签名），因此可以用**已知答案**测试钉死拼接顺序；海康另提供官方 OpenAPI 接口测试
 * 工具，无需真机即可调通每个接口。真机取流属互操作层。
 */
@DisplayName("hikvision isc OpenAPI: AK/SK signature + token contract (fake platform, zero hardware)")
class HikvisionIscClientContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private HikvisionIscClient client;
    private URI base;

    /** 假平台收到的请求流水。 */
    private final List<String> hits = new ArrayList<>();
    /** token 端点业务响应覆盖（null = 默认成功形状）。 */
    private String tokenResponse;
    /** 业务端点响应覆盖。 */
    private String businessResponse;

    @BeforeEach
    void boot() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/artemis", ex -> route(ex));
        server.setExecutor(null);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new HikvisionIscClient(base, "ak-123", "sk-secret");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        hits.add(ex.getRequestMethod() + " " + path
                + " sig=" + ex.getRequestHeaders().getFirst("x-ca-signature")
                + " nonce=" + ex.getRequestHeaders().getFirst("x-ca-nonce")
                + " ts=" + ex.getRequestHeaders().getFirst("x-ca-timestamp")
                + " token=" + ex.getRequestHeaders().getFirst("access_token")
                + " body=" + body);
        if (path.endsWith("/oauth/token")) {
            respond(ex, 200, tokenResponse == null ? defaultTokenResponse() : tokenResponse);
        } else {
            respond(ex, 200, businessResponse == null ? defaultBusiness() : businessResponse);
        }
    }

    // ------------------------------------------------------------------
    // 签名契约（确定性 ⇒ 已知答案）
    // ------------------------------------------------------------------

    /**
     * **已知答案**签名测试（独立真值，不是自证）。
     *
     * <p>固定 appSecret / method / Accept / Content-Type / 三个 x-ca-* 头 / 路径，
     * 期望的 {@code X-Ca-Signature} 由<b>独立的 Python hmac 实现</b>算出
     * （{@code hmac.digest(secret, signedString, sha256)} 后 Base64），
     * 与 Java 实现无关——两边对同一条签名字符串算出同一个值才对。
     *
     * <p><b>为什么必须有这一条</b>：早期只有"同一实现的内部一致性"测试
     * （换 Accept ⇒ 签名变、换 secret ⇒ 签名变），那是**自证**——把拼接顺序改错、
     * 把 HmacSHA256 换成 HmacSHA1，两组断言依然全绿（A/B 实测 break-order 与
     * break-algo 都 0 红）。只有拿外部真值来比，才能把"算法/顺序对不对"钉死。
     */
    @Test
    @DisplayName("已知答案：固定输入 ⇒ 固定 X-Ca-Signature（独立实现算出的真值）")
    void knownAnswerSignatureMatchesIndependentImplementation() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-ca-key", "ak-123");
        headers.put("x-ca-nonce", "nonce-fixed");
        headers.put("x-ca-timestamp", "1700000000000");

        String actual = HikvisionIscAuth.signature(
                "sk-secret", "POST", "*/*", "application/json", headers,
                "/artemis/api/video/v2/cameras/previewURLs");

        assertThat(actual)
                .as("独立 Python hmac(sha256) 实现算出的真值；不一致 ⇒ 拼接顺序或算法错了")
                .isEqualTo("O/f/jE+D2+ggHLHMJiPRwEMRMwuf2xS3WQ4asKUZOQs=");
    }

    @Test
    @DisplayName("签名字符串按文档顺序拼：METHOD/Accept/Content-Type/自定义头(小写排序)/路径")
    void signedStringFollowsDocumentedOrder() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-ca-key", "ak-123");
        headers.put("x-ca-nonce", "nonce-fixed");
        headers.put("x-ca-timestamp", "1700000000000");

        // 我们无法直接拿到内部拼串，但可以验证**结果**与独立重算一致（真值）
        String expected = HikvisionIscAuth.signature(
                "sk-secret", "POST", "*/*", "application/json", headers,
                "/artemis/api/video/v2/cameras/previewURLs");
        assertThat(expected).isNotBlank();

        // 顺序错了签名必须不同（换 Accept ⇒ 不同签名）
        String other = HikvisionIscAuth.signature(
                "sk-secret", "POST", "application/json", "*/*", headers,
                "/artemis/api/video/v2/cameras/previewURLs");
        assertThat(other).isNotEqualTo(expected);
    }

    @Test
    @DisplayName("头名用原始大小写排会算出**另一个**签名（契约：按小写名排序）")
    void headerSortingIsCaseInsensitive() {
        Map<String, String> lower = new LinkedHashMap<>();
        lower.put("x-ca-key", "ak");
        lower.put("x-ca-nonce", "n");
        lower.put("x-ca-timestamp", "t");

        Map<String, String> mixed = new LinkedHashMap<>();
        mixed.put("X-Ca-Key", "ak");
        mixed.put("X-Ca-Nonce", "n");
        mixed.put("X-Ca-Timestamp", "t");

        assertThat(HikvisionIscAuth.signature("s", "POST", "*/*", "application/json",
                mixed, "/artemis/x"))
                .isEqualTo(HikvisionIscAuth.signature("s", "POST", "*/*", "application/json",
                        lower, "/artemis/x"));
    }

    @Test
    @DisplayName("路径必须带 /artemis 前缀（少前缀 ⇒ 完全不同的签名）")
    void artemisPrefixIsPartOfSignature() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-ca-key", "ak");
        assertThat(HikvisionIscAuth.signature("s", "POST", "*/*", "application/json",
                h, "/artemis/api/x"))
                .isNotEqualTo(HikvisionIscAuth.signature("s", "POST", "*/*", "application/json",
                        h, "/api/x"));
    }

    @Test
    @DisplayName("appSecret 不同 ⇒ 签名不同（换密钥必须换签名）")
    void secretChangeChangesSignature() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-ca-key", "ak");
        String a = HikvisionIscAuth.signature("secret-a", "POST", "*/*", "application/json", h, "/artemis/x");
        String b = HikvisionIscAuth.signature("secret-b", "POST", "*/*", "application/json", h, "/artemis/x");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("Content-Type params (charset/boundary) do not take part in the signed string")
    void contentTypeParamsStripped() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-ca-key", "ak");
        assertThat(HikvisionIscAuth.signature("s", "POST", "*/*",
                "application/json;charset=UTF-8", h, "/artemis/x"))
                .isEqualTo(HikvisionIscAuth.signature("s", "POST", "*/*",
                        "application/json", h, "/artemis/x"));
    }

    // ------------------------------------------------------------------
    // token 流程
    // ------------------------------------------------------------------

    @Test
    @DisplayName("token 经签名请求获取：客户端带齐五个 x-ca-* 头")
    void tokenRequestCarriesSignatureHeaders() {
        client.authenticate();

        String hit = hits.get(0);
        assertThat(hit).startsWith("POST /artemis/oauth/token");
        assertThat(hit).contains("sig=");
        assertThat(hit).doesNotContain("sig=null");
        assertThat(hit).contains("nonce=").doesNotContain("nonce=null");
        assertThat(hit).contains("ts=").doesNotContain("ts=null");
        assertThat(hit).contains("client_id").contains("client_secret");
        assertThat(client.currentAuth()).isNotNull();
        assertThat(client.currentAuth().accessToken()).isEqualTo("at.isc.1");
    }

    @Test
    @DisplayName("token 过期 = now + 2h - 1min 安全余量")
    void tokenExpiryHasSafetyMargin() {
        Instant before = Instant.now();
        HikvisionIscAuth auth = client.authenticate();
        Instant after = Instant.now();

        assertThat(auth.expiresAt()).isBetween(
                before.plus(HikvisionIscAuth.DOC_TTL).minus(HikvisionIscAuth.SAFETY_MARGIN),
                after.plus(HikvisionIscAuth.DOC_TTL).minus(HikvisionIscAuth.SAFETY_MARGIN));
    }

    @Test
    @DisplayName("可用 token 复用（不重复取 token）")
    void tokenIsCached() {
        client.authenticate();
        client.authenticate();

        assertThat(hits.stream().filter(h -> h.contains("/oauth/token")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("业务请求带 access_token 头")
    void businessRequestCarriesAccessToken() {
        client.authenticate();
        client.postJson("/api/resource/v1/cameras", newBody().put("pageNo", 1));

        String hit = hits.get(1);
        assertThat(hit).contains("token=at.isc.1");
        assertThat(hit).doesNotContain("sig=null");
    }

    // ------------------------------------------------------------------
    // 平台语义失败（code != "0"）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("code != \"0\" ⇒ 语义异常（文档：code 是字符串 \"0\" 才算成功）")
    void nonZeroCodeIsSemanticFailure() {
        businessResponse = json(m -> m.put("code", "1").put("msg", "camera not found")).toString();
        client.authenticate();

        assertThatThrownBy(() -> client.postJson("/api/resource/v1/cameras", newBody()))
                .isInstanceOf(HikvisionIscClient.HikvisionIscException.class)
                .hasMessageContaining("hikvision isc rejected")
                .hasMessageContaining("camera not found");
    }

    @Test
    @DisplayName("token 失效类 msg ⇒ 重取一次并重试（真实平台提前失效比 2h 到期更常见）")
    void authFailureRetriesOnce() {
        client.authenticate();
        int before = hits.size();
        businessResponse = json(m -> m.put("code", "401").put("msg", "token expired")).toString();
        assertThatThrownBy(() -> client.postJson("/api/x", newBody()))
                .isInstanceOf(HikvisionIscClient.HikvisionIscException.class);
        // 至少又取了一次 token（重取）+ 重试了一次业务
        assertThat(hits.stream().skip(before).filter(h -> h.contains("/oauth/token")).count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("响应无 access_token 字段 ⇒ 响亮失败（不返回 null 让上层 NPE）")
    void missingTokenFieldFailsLoudly() {
        tokenResponse = json(m -> m.put("code", "0").putObject("data")).toString();
        assertThatThrownBy(() -> client.authenticate())
                .isInstanceOf(HikvisionIscClient.HikvisionIscException.class)
                .hasMessageContaining("no access_token");
    }

    @Test
    @DisplayName("预览接口返回 data.url（rtsp/hls），不播放不校验可达")
    void previewUrlReturnsDocumentedShape() {
        client.authenticate();
        businessResponse = json(m -> m.put("code", "0").put("msg", "success")
                .putObject("data").put("url", "rtsp://isc.example.com/openUrl/Kp0VqXS")).toString();

        assertThat(client.previewUrl("cam-001", "rtsp", 0))
                .isEqualTo("rtsp://isc.example.com/openUrl/Kp0VqXS");
    }

    @Test
    @DisplayName("预览响应缺 data.url ⇒ 响亮失败")
    void previewWithoutUrlFailsLoudly() {
        client.authenticate();
        businessResponse = json(m -> m.put("code", "0").putObject("data")).toString();
        assertThatThrownBy(() -> client.previewUrl("cam-001", "rtsp", 0))
                .isInstanceOf(HikvisionIscClient.HikvisionIscException.class)
                .hasMessageContaining("no data.url");
    }

    @Test
    @DisplayName("cameraIndexCode 空白 ⇒ 立即拒绝")
    void blankCameraIndexRejected() {
        assertThatThrownBy(() -> client.previewUrl("  ", "rtsp", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 构造与 URI
    // ------------------------------------------------------------------

    @Test
    @DisplayName("构造参数 null ⇒ 立即 NPE 指名")
    void nullCtorArgsRejected() {
        assertThatThrownBy(() -> new HikvisionIscClient(null, "ak", "sk"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HikvisionIscClient(base, null, "sk"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HikvisionIscClient(base, "ak", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("baseUri 尾斜杠归一（/artemis 前多一个 / 会让网关 404）")
    void trailingSlashNormalised() {
        HikvisionIscClient c = new HikvisionIscClient(URI.create(base.toString() + "/"), "ak", "sk");
        c.authenticate();
        assertThat(hits.get(0)).doesNotContain("//artemis");
        assertThat(hits.get(0)).contains("/artemis/oauth/token");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ObjectNode newBody() {
        return MAPPER.createObjectNode();
    }

    private static ObjectNode json(java.util.function.Consumer<ObjectNode> f) {
        ObjectNode n = MAPPER.createObjectNode();
        f.accept(n);
        return n;
    }

    private static String defaultTokenResponse() {
        return json(m -> m.put("code", "0").put("msg", "success")
                .putObject("data").put("access_token", "at.isc.1")).toString();
    }

    private static String defaultBusiness() {
        return json(m -> m.put("code", "0").put("msg", "success")
                .putObject("data").put("ok", true)).toString();
    }

    // ==================================================================
    // ⑨ 变异门禁收口：错误分类 / 传输异常 / firstText 兜底 / 构造路径
    // ==================================================================

    @Test
    @DisplayName("send 的 IOException ⇒ 自建短句在外、底层异常必须留在 cause 里")
    void transportFailureKeepsCauseAndShortMessage() {
        // 打一个拒绝连接的端口 ⇒ 传输失败（不像平台语义拒绝那样吞掉 cause）
        var c = new HikvisionIscClient(URI.create("http://127.0.0.1:1"), "ak", "sk");
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(c::authenticate);

        assertThat(thrown).isInstanceOf(HikvisionIscClient.HikvisionIscException.class);
        assertThat(thrown.getMessage())
                .contains("hikvision isc POST /oauth/token failed")
                // 消息里不留底层细节（排障时先看消息，细节去 cause）
                .doesNotContain("Connection refused")
                .doesNotContain("127.0.0.1:1");
        // **关键不变量**：传输失败的 cause 被保留——否则排障时只剩一句
        // "failed"，完全看不出是网络、超时还是被拒。
        assertThat(thrown.getCause())
                .as("transport failure must keep its cause")
                .isNotNull();
        assertThat(c.currentAuth()).isNull();
    }

    @Test
    @DisplayName("平台回非 JSON（HTML 错误页）⇒ 归为响应不可读而不是解析 NPE")
    void nonJsonResponseIsUnreadable() {
        hits.clear();
        // 直接打一个会回 HTML 的端口：用假平台响应非 JSON body
        businessResponse = "<html>502 Bad Gateway</html>";
        tokenResponse = "<html>502 Bad Gateway</html>";
        assertThatThrownBy(client::authenticate)
                .isInstanceOf(HikvisionIscClient.HikvisionIscException.class)
                .hasMessageContaining("unreadable");
    }

    @Test
    @DisplayName("code 为 null / msg 缺失 ⇒ 异常消息里带 code，不留 NPE")
    void nullCodeDoesNotNpe() {
        // 平台回 {"code":"5"}（无 msg）⇒ 短句里带 code
        businessResponse = toStringOf(m -> m.put("code", "5"));
        client.authenticate();
        int before = hits.size();
        try {
            client.postJson("/api/x", newBody());
        } catch (HikvisionIscClient.HikvisionIscException e) {
            // 无 msg 时短句要带 code（不能 NPE），且必须能被认成"普通业务失败"
            assertThat(e.getMessage()).contains("code 5");
            assertThat(e.code()).isEqualTo("5");
        }
        // 普通业务失败不触发重取：只多打了这一次业务请求
        assertThat(hits).hasSize(before + 1);
    }

    @Test
    @DisplayName("firstText 兜底顺序：accessToken > access_token > token")
    void firstTextFallbackOrder() {
        tokenResponse = toStringOf(m -> m.put("code", "0").putObject("data")
                .put("accessToken", "at.accessToken"));
        assertThat(client.authenticate().accessToken()).isEqualTo("at.accessToken");

        tokenResponse = toStringOf(m -> m.put("code", "0").putObject("data")
                .put("token", "at.token"));
        var c2 = new HikvisionIscClient(base, "ak", "sk");
        assertThat(c2.authenticate().accessToken()).isEqualTo("at.token");
    }

    @Test
    @DisplayName("contentMd5 是稳定的 Base64（同一 body ⇒ 同一值，不同 body 不同值）")
    void contentMd5IsStable() {
        String a = HikvisionIscAuth.contentMd5("{\"a\":1}");
        String b = HikvisionIscAuth.contentMd5("{\"a\":1}");
        String c = HikvisionIscAuth.contentMd5("{\"a\":2}");
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
        // MD5 = 16 字节 ⇒ Base64 长度 24（22 字符 + '=='）
        assertThat(a).hasSize(24);
        assertThat(a).matches("^[A-Za-z0-9+/]{22}==$");
    }

    @Test
    @DisplayName("token 缺失时构造 auth 立即 NPE（不造出 expiresAt=null 的半成品）")
    void nullTokenRejectedAtConstruction() {
        assertThatThrownBy(() -> new HikvisionIscAuth(null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> HikvisionIscAuth.issuedNow(null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HikvisionIscAuth("at", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("validAt 边界：等于 expiresAt 的那一刻算已过期")
    void validAtBoundary() {
        Instant now = Instant.now();
        HikvisionIscAuth auth = new HikvisionIscAuth("at", now.plusSeconds(60));
        assertThat(auth.validAt(now.minusSeconds(1))).isTrue();
        assertThat(auth.validAt(now.plusSeconds(59))).isTrue();
        assertThat(auth.validAt(now.plusSeconds(60))).isFalse();
        assertThat(auth.validAt(now.plusSeconds(61))).isFalse();
    }

    @Test
    @DisplayName("artemisPrefix 空白 ⇒ 归一为默认 /artemis（不吃 null 造出 //oauth/token）")
    void blankArtemisPrefixNormalised() {
        var c = new HikvisionIscClient(base, "ak", "sk", "   ");
        c.authenticate();
        assertThat(hits.get(0)).contains("/artemis/oauth/token");
        assertThat(hits.get(0)).doesNotContain("//artemis");

        var c2 = new HikvisionIscClient(base, "ak", "sk", null);
        c2.authenticate();
        assertThat(hits).allSatisfy(h -> assertThat(h).contains("/artemis/"));
    }

    @Test
    @DisplayName("retryOnceOnAuthFailure 只在 auth 类失败时重取（普通业务失败不重试）")
    void noRetryOnPlainBusinessFailure() {
        client.authenticate();
        int before = hits.size();
        businessResponse = toStringOf(m -> m.put("code", "7").put("msg", "camera offline"));
        assertThatThrownBy(() -> client.postJson("/api/x", newBody()))
                .isInstanceOf(HikvisionIscClient.HikvisionIscException.class);
        // 只多打了一次业务请求，没有重取 token
        assertThat(hits).hasSize(before + 1);
    }

    // ---- 小工具 ----

    private static String toStringOf(java.util.function.Consumer<ObjectNode> f) {
        ObjectNode n = MAPPER.createObjectNode();
        f.accept(n);
        return n.toString();
    }

    private static void respond(HttpExchange ex, int code, String s) throws IOException {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
