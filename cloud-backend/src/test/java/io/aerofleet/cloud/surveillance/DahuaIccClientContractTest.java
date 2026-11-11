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
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 大华 ICC OpenAPI 客户端契约测试（零硬件：JDK 内置 HttpServer 当假平台）。
 *
 * <p>依据：大华开放平台公开文档 open-icc.dahuatech.com 的「对接指南 - 整体流程 -
 * 鉴权认证」。文档原文关键点逐条钉住：
 * <ul>
 *   <li>access_token 有效期 2h；</li>
 *   <li>请求头 Authorization: token_type + 空格 + access_token；</li>
 *   <li>password RSA 加密后发送（公钥另取）；</li>
 *   <li>文档明言「不同 ICC 版本接口方法会有出入」⇒ 客户端必须对字段名宽容；</li>
 *   <li>文档明言提供联调测试环境 ⇒ 本次契约测试不依赖任何硬件。</li>
 * </ul>
 *
 * <p>与 UomReportSinkContractTest 同一套路（JDK HttpServer 假平台），因此它跑在
 * CI 每次构建里，不需要真 ICC、不需要 client_id。
 */
@DisplayName("dahua icc OpenAPI client: REST/OAuth2 contract (fake platform, zero hardware)")
class DahuaIccClientContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private DahuaIccClient client;
    private URI base;

    /** 假平台收到的请求流水（断言依据）。 */
    private final List<String> hits = new ArrayList<>();
    /** 401 注入开关：第一次业务请求回 401，之后回 200。 */
    private boolean inject401Once;
    private final AtomicInteger businessCalls = new AtomicInteger();
    /** token 端点固定响应（null = 用默认形状）。 */
    private String tokenResponseOverride;

    @BeforeEach
    void boot() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/evo-apigw/evo-oauth/1.0.0/oauth/public-key", ex -> {
            hits.add("GET public-key");
            respond(ex, 200, TestKeys.PUBLIC_PEM);
        });
        server.createContext("/evo-apigw/evo-oauth/1.0.0/oauth/extend/token", ex -> {
            String received = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            hits.add("POST token body=" + received);
            respond(ex, 200, tokenResponseOverride == null
                    ? defaultTokenResponse() : tokenResponseOverride);
        });
        server.createContext("/business", ex -> {
            int n = businessCalls.incrementAndGet();
            hits.add("GET business auth=" + ex.getRequestHeaders().getFirst("Authorization")
                    + " n=" + n);
            if (inject401Once && n == 1) {
                respond(ex, 401, json(m -> m.put("errMsg", "token expired")));
            } else {
                respond(ex, 200, json(m -> m.putObject("data").put("ok", true)));
            }
        });
        server.setExecutor(null);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = newClient(base);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private DahuaIccClient newClient(URI b) {
        return new DahuaIccClient(b, "cid", "csecret", "apiuser", "pw123456");
    }

    // ------------------------------------------------------------------
    // 鉴权流程（文档两步）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("auth follows the documented two steps: public key first, then RSA-ed password")
    void authenticationFollowsDocumentedTwoSteps() {
        client.authenticate();

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0)).isEqualTo("GET public-key");
        assertThat(hits.get(1)).startsWith("POST token body=");
        String sent = hits.get(1);
        assertThat(sent).contains("grant_type", "username", "password", "client_id", "client_secret");
        // password 必须是 RSA 密文，不是明文
        assertThat(sent).doesNotContain("pw123456");
        assertThat(client.currentAuth()).isNotNull();
        assertThat(client.currentAuth().accessToken()).isEqualTo("at.abc123");
    }

    @Test
    @DisplayName("token expiry = now + 2h - 1min margin (doc says 2h; client goes earlier)")
    void tokenExpiryAppliesSafetyMargin() {
        Instant before = Instant.now();
        DahuaIccAuth auth = client.authenticate();
        Instant after = Instant.now();

        assertThat(auth.expiresAt()).isBetween(
                before.plus(DahuaIccAuth.DOC_TTL).minus(DahuaIccAuth.SAFETY_MARGIN),
                after.plus(DahuaIccAuth.DOC_TTL).minus(DahuaIccAuth.SAFETY_MARGIN));
        assertThat(auth.validAt(auth.expiresAt().minusSeconds(1))).isTrue();
        assertThat(auth.validAt(auth.expiresAt())).isFalse();
        assertThat(auth.authorizationHeader()).isEqualTo("bearer at.abc123");
    }

    @Test
    @DisplayName("a still-valid token is reused (real platforms cap concurrent logins)")
    void authenticationIsCachedWhileValid() {
        client.authenticate();
        client.authenticate();
        client.authenticate();

        assertThat(hits.stream().filter(h -> h.startsWith("POST token")).count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 业务请求 + 401 重试
    // ------------------------------------------------------------------

    @Test
    @DisplayName("business request carries Authorization header")
    void businessRequestCarriesAuthorizationHeader() {
        client.getAuthorized("/business");

        assertThat(hits).anyMatch(h -> h.contains("auth=bearer at.abc123"));
    }

    @Test
    @DisplayName("401 triggers one re-auth + retry (early expiry beats the 2h TTL in practice)")
    void fourOhOneTriggersReAuthAndRetry() {
        inject401Once = true;

        HttpResponse<String> resp = client.getAuthorized("/business");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(hits.stream().filter(h -> h.startsWith("POST token")).count()).isEqualTo(2);
        assertThat(businessCalls.get()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // 失败路径（都要求"响亮失败"，不静默、不外泄）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("token field names are tolerated: access_token / token / magicId all work")
    void tokenFieldNameIsTolerant() throws Exception {
        assertThat(readToken(json(m -> m.putObject("data").put("access_token", "a1"))))
                .isEqualTo("a1");
        assertThat(readToken(json(m -> m.putObject("data").put("token", "t2"))))
                .isEqualTo("t2");
        assertThat(readToken(json(m -> m.putObject("data").put("magicId", "m3"))))
                .isEqualTo("m3");
        assertThat(readToken(defaultTokenResponse())).isEqualTo("at.abc123");
    }

    @Test
    @DisplayName("no token field at all fails loudly instead of returning null for an NPE upstream")
    void missingTokenFieldFailsLoudly() throws Exception {
        tokenResponseOverride = json(m -> m.put("errMsg", "bad").putObject("data"));
        assertThatThrownBy(client::authenticate)
                .isInstanceOf(DahuaIccClient.DahuaIccException.class)
                .hasMessageContaining("no access token field");
    }

    @Test
    @DisplayName("token endpoint non-200 is wrapped (platform body with internal IP must not leak)")
    void tokenEndpointErrorIsWrapped() throws Exception {
        HttpServer s = freshServer((ex, path) -> {
            if (path.endsWith("public-key")) {
                respond(ex, 200, TestKeys.PUBLIC_PEM);
            } else {
                respond(ex, 500, "internal detail 10.80.3.101");
            }
        });
        try {
            assertThatThrownBy(newClient(baseOf(s))::authenticate)
                    .isInstanceOf(DahuaIccClient.DahuaIccException.class)
                    .hasMessageContaining("token request failed: HTTP 500")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("10.80.3.101"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    @DisplayName("empty public key fails loudly (no RSA on empty string with an unreadable error)")
    void emptyPublicKeyFailsLoudly() throws Exception {
        HttpServer s = freshServer((ex, path) -> {
            if (path.endsWith("public-key")) {
                respond(ex, 200, "");
            } else {
                respond(ex, 200, defaultTokenResponse());
            }
        });
        try {
            assertThatThrownBy(newClient(baseOf(s))::authenticate)
                    .isInstanceOf(DahuaIccClient.DahuaIccException.class)
                    .hasMessageContaining("public key response is empty");
        } finally {
            s.stop(0);
        }
    }

    @Test
    @DisplayName("persistent 401 surfaces as 401 (no infinite retry, no fake success)")
    void persistentFourOhOneSurfaces() throws Exception {
        HttpServer s = freshServer((ex, path) -> {
            if (path.endsWith("public-key")) {
                respond(ex, 200, TestKeys.PUBLIC_PEM);
            } else if (path.endsWith("token")) {
                respond(ex, 200, json(m -> m.putObject("data").put("accessToken", "at.x")));
            } else {
                respond(ex, 401, "nope");
            }
        });
        try {
            assertThat(newClient(baseOf(s)).getAuthorized("/business").statusCode()).isEqualTo(401);
        } finally {
            s.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 构造期与 URI 归一
    // ------------------------------------------------------------------

    @Test
    @DisplayName("null ctor args rejected immediately (not at first request)")
    void nullConstructionArgsRejected() {
        assertThatThrownBy(() -> new DahuaIccClient(null, "c", "s", "u", "p"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DahuaIccClient(base, null, "s", "u", "p"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DahuaIccClient(base, "c", "s", "u", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("trailing slash on baseUri is normalised (//evo-apigw would 404)")
    void baseUriTrailingSlashNormalised() {
        client.fetchPublicKey();

        assertThat(hits).contains("GET public-key");
        assertThat(hits).hasSize(1);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String readToken(String json) throws Exception {
        HttpServer s = freshServer((ex, path) -> {
            if (path.endsWith("public-key")) {
                respond(ex, 200, TestKeys.PUBLIC_PEM);
            } else {
                respond(ex, 200, json);
            }
        });
        try {
            return newClient(baseOf(s)).authenticate().accessToken();
        } finally {
            s.stop(0);
        }
    }

    /**
     * 简易假平台：oauth 端点 + 一个 /business 业务端点都交给同一个 handler。
     *
     * <p>**为什么必须也挂 /business**：JDK HttpServer 对未挂 context 的路径回 404。
     * 早期版本只挂了 /evo-apigw，于是 persistentFourOhOneSurfaces 断言 401、
     * 实际拿到 404——**测试自己被假平台的默认行为骗了**。这类"假平台悄悄换了
     * 错误码"的坑，只有把响应状态码本身写进断言才会暴露。
     */
    private HttpServer freshServer(BiHandler h) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/evo-apigw", ex -> h.handle(ex, ex.getRequestURI().getPath()));
        s.createContext("/business", ex -> h.handle(ex, ex.getRequestURI().getPath()));
        s.setExecutor(null);
        s.start();
        return s;
    }

    private interface BiHandler {
        void handle(HttpExchange ex, String path) throws IOException;
    }

    private static URI baseOf(HttpServer s) {
        return URI.create("http://127.0.0.1:" + s.getAddress().getPort());
    }

    private static String defaultTokenResponse() {
        return json(m -> m.putObject("data")
                .put("accessToken", "at.abc123")
                .put("tokenType", "bearer"));
    }

    private static String json(Consumer<ObjectNode> f) {
        ObjectNode n = MAPPER.createObjectNode();
        f.accept(n);
        try {
            return MAPPER.writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange ex, int code, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** 测试用 RSA 公钥（运行时生成——仓里不放任何密钥）。 */
    static final class TestKeys {
        static final String PUBLIC_PEM = publicPem();

        private static String publicPem() {
            try {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(2048);
                KeyPair kp = g.generateKeyPair();
                String b64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
                StringBuilder sb = new StringBuilder();
                sb.append("-----BEGIN PUBLIC KEY-----\n");
                for (int i = 0; i < b64.length(); i += 64) {
                    sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
                }
                sb.append("-----END PUBLIC KEY-----");
                return sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
