package io.aerofleet.linksim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全画像控制通道契约测试（P2 #20）。
 *
 * <p>与 {@link LinkSimControlServerTest} 同构：起真实 {@link LinkSimControlServer}
 * （回环 + 随机端口）打真实 HTTP，并且<b>按住引擎本身</b>验证"参数真的进了数据面"——
 * 只断言响应 JSON 会漏掉"写进一个没人读的字段"这类假接线。
 *
 * <p>本类的特有断言：安全画像<b>只有 tamperRate 一个旋钮</b>。签名剥离是检测到
 * 签名帧必剥（动作全命中，无概率可调），因此 stripe 不给滑块、十元组参数一个都
 * 不收（PATCH 它们一律 400 并说清原因）——给一个拖了不改变任何行为的控件，
 * 正是 P1 #11 清掉的病。
 */
@DisplayName("link-sim 安全画像控制通道：GET/PATCH /profile（tamperRate only）")
class SecurityControlServerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private SecurityImpairmentEngine engine;
    private LinkSimControlServer server;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        engine = new SecurityImpairmentEngine(LinkProfile.SIGNING_TAMPER, 0.5);
        server = new LinkSimControlServer(0, new SecurityControl(engine));
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    // ------------------------------------------------------------------
    // GET
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /profile：mode=security、画像、唯一旋钮 tamperRate、统计键齐备")
    void getReturnsSecurityState() throws Exception {
        JsonNode view = get();

        assertThat(view.path("profile").asText()).isEqualTo("tamper");
        assertThat(view.path("mode").asText()).isEqualTo("security");
        assertThat(view.path("envCoupled").asBoolean()).isFalse();
        assertThat(view.path("security").path("kind").asText()).isEqualTo("SIGNING_TAMPER");
        assertThat(view.path("security").path("tamperRate").asDouble()).isEqualTo(0.5);
        assertThat(view.path("security").path("tunable").asText()).isEqualTo("tamperRate");
        assertThat(view.path("security").path("limits").path("tamperRateMin").asDouble()).isZero();
        assertThat(view.path("security").path("limits").path("tamperRateMax").asDouble()).isEqualTo(1.0);
        // 十元组参数不允许出现在安全视图里（没有的东西不挂键）
        assertThat(view.path("uplink").path("delayMs").isMissingNode()).isTrue();
        assertThat(view.path("uplink").path("dropPct").isMissingNode()).isTrue();
        assertThat(view.path("downlink").path("tamperRate").asDouble()).isEqualTo(0.5);
        // 通用 stats 键仍在（前端渲染要读），dropped=0 是事实而非占位
        assertThat(view.path("uplink").path("stats").path("forwarded").asLong()).isZero();
        assertThat(view.path("uplink").path("stats").path("dropped").asLong()).isZero();
        assertThat(view.path("uplink").path("stats").path("dropPct").asDouble()).isZero();
        assertThat(view.path("uplink").path("stats").path("tampered").asLong()).isZero();
        assertThat(view.path("uplink").path("stats").path("stripped").asLong()).isZero();
    }

    // ------------------------------------------------------------------
    // PATCH：参数真的进了引擎
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH tamperRate=1：引擎每帧必判 TAMPER（真的进了数据面）")
    void patchTamperRateReachesDataPlane() throws Exception {
        JsonNode view = patch("{\"tamperRate\":1.0}");

        assertThat(view.path("security").path("tamperRate").asDouble()).isEqualTo(1.0);
        assertThat(view.path("uplink").path("tamperRate").asDouble()).isEqualTo(1.0);
        assertThat(engine.tamperRate()).isEqualTo(1.0);
        for (int i = 0; i < 50; i++) {
            assertThat(engine.verdict(signedFrame()))
                    .as("rate=1 ⇒ 第 %d 帧也必须 TAMPER", i)
                    .isEqualTo(SecurityImpairmentEngine.SecurityAction.TAMPER);
        }
        JsonNode after = get();
        assertThat(after.path("uplink").path("stats").path("tampered").asLong())
                .as("统计要真的随判决增长").isEqualTo(50);
        assertThat(after.path("uplink").path("stats").path("dropped").asLong())
                .as("篡改帧照发不误：dropped 必须保持 0（不拿丢包口径骗人）").isZero();
    }

    @Test
    @DisplayName("PATCH tamperRate=0：引擎永不 TAMPER（对照组，证明参数不是装饰）")
    void patchTamperRateZeroStopsTampering() throws Exception {
        patch("{\"tamperRate\":0.0}");

        for (int i = 0; i < 200; i++) {
            assertThat(engine.verdict(signedFrame()))
                    .isEqualTo(SecurityImpairmentEngine.SecurityAction.FORWARD);
        }
        assertThat(engine.tamperRate()).isZero();
    }

    @Test
    @DisplayName("PATCH tamperRate=0.5：统计里 tampered/forwarded 都出现；SIGNING_UNSIGNED 同通道")
    void halfRateBothOutcomesAndUnsignedProfile() throws Exception {
        patch("{\"tamperRate\":0.5}");
        for (int i = 0; i < 2_000; i++) {
            engine.verdict(signedFrame());
        }
        JsonNode view = get();
        assertThat(view.path("uplink").path("stats").path("tampered").asLong()).isPositive();
        assertThat(view.path("uplink").path("stats").path("forwarded").asLong()).isPositive();
        assertThat(view.path("uplink").path("stats").path("tamperRatio").asDouble())
                .isBetween(0.3, 0.7);

        // 同一控制面服务 SIGNING_UNSIGNED（剥离是动作全命中，没有滑块可调）
        SecurityImpairmentEngine unsigned = new SecurityImpairmentEngine(
                LinkProfile.SIGNING_UNSIGNED, 0.0);
        LinkSimControlServer s2 = startControl(unsigned);
        try {
            JsonNode u = get(s2);
            assertThat(u.path("profile").asText()).isEqualTo("unsigned");
            assertThat(u.path("mode").asText()).isEqualTo("security");
            assertThat(u.path("security").path("kind").asText()).isEqualTo("SIGNING_UNSIGNED");
            // INC bit 0 置位的帧必被剥：与 tamperRate 无关（0.0 也照剥）
            assertThat(unsigned.verdict(signedFrame()))
                    .isEqualTo(SecurityImpairmentEngine.SecurityAction.STRIP_SIGNATURE);
            assertThat(unsigned.verdict(plainFrame()))
                    .isEqualTo(SecurityImpairmentEngine.SecurityAction.FORWARD);
        } finally {
            s2.stop();
        }
    }

    // ------------------------------------------------------------------
    // 非法输入：安全画像没有十元组，一个都不收
    // ------------------------------------------------------------------

    @Test
    @DisplayName("十元组参数/空 body/缺失字段/坏类型/越界一律 400，并给可读原因")
    void invalidPatchesAreRejected() throws Exception {
        String[] bodies = {
                "{}",
                "{\"delayMs\":120}",
                "{\"delayMs\":120,\"tamperRate\":0.5}",
                "{\"dropPct\":100}",
                "{\"bandwidthKbps\":8}",
                "{\"partitionCycleSec\":25}",
                "{\"jitterMs\":0,\"tamperRate\":0.5}",
                "{\"profile\":\"lte-edge\"}",
                "{\"reset\":true}",
                "{\"bogus\":1}",
                "{\"tamperRate\":\"hot\"}",
                "{\"tamperRate\":1.5}",
                "{\"tamperRate\":-0.1}",
                "{\"tamperRate\":null}",
        };
        for (String body : bodies) {
            HttpResponse<String> resp = http.send(
                    request(base, "/profile").method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).as("body=%s", body).isEqualTo(400);
            assertThat(mapper.readTree(resp.body()).path("result").asText())
                    .as("body=%s 必须给出可读原因", body).isNotBlank();
        }
        // 全部拒绝后引擎概率不变
        assertThat(engine.tamperRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("坏 JSON / 非对象 / 超大 body / POST / 未知路径：400/400/413/405/404，不 500")
    void malformedRequestsRejected() throws Exception {
        HttpResponse<String> badJson = http.send(
                request(base, "/profile").method("PATCH", HttpRequest.BodyPublishers.ofString("{not json")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(badJson.statusCode()).isEqualTo(400);

        HttpResponse<String> array = http.send(
                request(base, "/profile").method("PATCH", HttpRequest.BodyPublishers.ofString("[1,2]")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(array.statusCode()).isEqualTo(400);

        String huge = "{\"tamperRate\":0.5,\"pad\":\"" + "x".repeat(70 * 1024) + "\"}";
        HttpResponse<String> tooLarge = http.send(
                request(base, "/profile").method("PATCH", HttpRequest.BodyPublishers.ofString(huge)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tooLarge.statusCode()).isEqualTo(413);

        HttpResponse<String> post = http.send(
                request(base, "/profile").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(405);
        assertThat(post.headers().firstValue("Allow").orElse("")).isEqualTo("GET, PATCH");

        HttpResponse<String> missing = http.send(
                request(base, "/nope").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------

    /** 一个"签名帧"字节样本（INC bit 0 置位即可，引擎只查这一位）。 */
    private static byte[] signedFrame() {
        return new byte[]{(byte) 0xFD, 0, 0x01};
    }

    /** 一个未签名帧字节样本（INC bit 0 = 0）。 */
    private static byte[] plainFrame() {
        return new byte[]{(byte) 0xFD, 0, 0x00};
    }

    private LinkSimControlServer startControl(SecurityImpairmentEngine e) throws Exception {
        LinkSimControlServer s = new LinkSimControlServer(0, new SecurityControl(e));
        s.start();
        return s;
    }

    private JsonNode get() throws Exception {
        return get(server);
    }

    private JsonNode get(LinkSimControlServer s) throws Exception {
        HttpResponse<String> resp = http.send(request(base(s), "/profile").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return mapper.readTree(resp.body());
    }

    private JsonNode patch(String json) throws Exception {
        HttpResponse<String> resp = http.send(
                request(base, "/profile").header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("PATCH %s -> %s", json, resp.body()).isEqualTo(200);
        return mapper.readTree(resp.body());
    }

    private String base(LinkSimControlServer s) {
        return "http://127.0.0.1:" + s.port();
    }

    private HttpRequest.Builder request(String base, String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(3));
    }
}
