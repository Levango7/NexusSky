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
 * link-sim 运行时控制通道的契约测试（P2 #20）。
 *
 * <p>起真实 {@link LinkSimControlServer}（回环 + 随机端口），用真实 HTTP 客户端
 * 打 GET/PATCH，并<b>按住引擎本身</b>验证"参数真的进了数据面"——只断言响应
 * JSON 会漏掉"控制面写进一个没人读的字段"这类假接线。
 *
 * <p>覆盖：参数应用（延迟/丢包/带宽/分区）、direction 分向、画像切换/reset、
 * 非法输入（未知字段/类型/范围/互斥）、方法/路径/坏 JSON/超大 body。
 */
@DisplayName("link-sim 运行时控制通道：GET/PATCH /profile")
class LinkSimControlServerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private ImpairmentEngine uplink;
    private ImpairmentEngine downlink;
    private LinkSimControlServer server;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        uplink = LinkProfile.LAN.engine();
        downlink = LinkProfile.LAN.engine();
        server = new LinkSimControlServer(0,
                new LinkSimControl(LinkProfile.LAN, uplink, LinkProfile.LAN, downlink, false));
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
    @DisplayName("GET /profile：画像/模式/双方向参数/上限/统计齐备，初始为画像模式")
    void getReturnsFullState() throws Exception {
        JsonNode view = get();

        assertThat(view.path("profile").asText()).isEqualTo("lan");
        assertThat(view.path("mode").asText()).isEqualTo("profile");
        assertThat(view.path("envCoupled").asBoolean()).isFalse();
        assertThat(view.path("uplink").path("delayMs").asDouble()).isZero();
        assertThat(view.path("uplink").path("mode").asText()).isEqualTo("profile");
        assertThat(view.path("uplink").path("dropModel").asText()).isEqualTo("ge");
        assertThat(view.path("downlink").path("profile").asText()).isEqualTo("lan");
        assertThat(view.path("limits").path("delayMsMax").asDouble()).isEqualTo(5_000);
        assertThat(view.path("profiles").toString()).contains("\"lte-edge\"", "\"starlink\"");
        assertThat(view.path("uplink").path("stats").path("forwarded").asLong()).isZero();
        assertThat(view.path("uplink").path("stats").path("dropped").asLong()).isZero();
    }

    // ------------------------------------------------------------------
    // PATCH：参数真的进了引擎
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH 延迟：引擎判决立刻变成新值（不是只改响应 JSON）")
    void patchDelayChangesVerdict() throws Exception {
        JsonNode view = patch("{\"delayMs\":120,\"jitterMs\":0}");

        assertThat(view.path("mode").asText()).isEqualTo("custom");
        assertThat(view.path("uplink").path("delayMs").asDouble()).isEqualTo(120);
        assertThat(view.path("uplink").path("dropModel").asText())
                .as("只改延迟不应把 GE 丢包模型说成 uniform").isEqualTo("ge");
        assertThat(uplink.verdict(64)).isEqualTo(120L);
        assertThat(downlink.verdict(64)).isEqualTo(120L);
    }

    @Test
    @DisplayName("PATCH dropPct=100：整条链路黑洞；dropModel 切 uniform；pGood=pBad=1")
    void patchDropPctIsHonestUniformModel() throws Exception {
        JsonNode view = patch("{\"dropPct\":100}");

        assertThat(view.path("uplink").path("dropModel").asText()).isEqualTo("uniform");
        assertThat(view.path("uplink").path("pGoodDrop").asDouble()).isEqualTo(1.0);
        assertThat(view.path("uplink").path("pBadDrop").asDouble()).isEqualTo(1.0);
        assertThat(view.path("uplink").path("pGoodToBad").asDouble()).isZero();
        assertThat(view.path("uplink").path("pBadToGood").asDouble()).isEqualTo(1.0);
        for (int i = 0; i < 20; i++) {
            assertThat(uplink.verdict(64)).as("第 %d 包", i).isEqualTo(-1L);
        }
    }

    @Test
    @DisplayName("PATCH 带宽：kbps 换算成字节/秒（8 kbps = 1000 B/s），桶容量随之调整")
    void patchBandwidthConvertsUnits() throws Exception {
        JsonNode view = patch("{\"bandwidthKbps\":8}");

        assertThat(view.path("uplink").path("bandwidthKbps").asDouble()).isEqualTo(8.0);
        ImpairmentSpec spec = uplink.spec();
        assertThat(spec.rateBytesPerSec()).isEqualTo(1000.0);
        assertThat(spec.burstBytes()).isEqualTo(2048.0);
    }

    @Test
    @DisplayName("PATCH 分区：cycle/down 落成 up=cycle-down；down>cycle 拒绝")
    void patchPartitionConvertsCycle() throws Exception {
        JsonNode view = patch("{\"partitionCycleSec\":25,\"partitionDownSec\":2.5}");

        assertThat(view.path("uplink").path("partitionCycleSec").asDouble()).isEqualTo(25.0);
        assertThat(view.path("uplink").path("partitionDownSec").asDouble()).isEqualTo(2.5);
        ImpairmentSpec spec = uplink.spec();
        assertThat(spec.partitionUpSec()).isEqualTo(22.5);
        assertThat(spec.partitionDownSec()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("PATCH direction=uplink：只改上行，下行保持原值")
    void patchCanTargetOneDirection() throws Exception {
        JsonNode view = patch("{\"direction\":\"uplink\",\"delayMs\":300,\"jitterMs\":0}");

        assertThat(view.path("uplink").path("delayMs").asDouble()).isEqualTo(300);
        assertThat(view.path("downlink").path("delayMs").asDouble()).isZero();
        assertThat(view.path("mode").asText()).as("只有一侧自定义 → mixed").isEqualTo("mixed");
        assertThat(uplink.verdict(64)).isEqualTo(300L);
        assertThat(downlink.verdict(64)).isZero();
    }

    @Test
    @DisplayName("画像切换 + reset：reset 恢复当前画像默认值（不是初始画像）")
    void profileSwitchAndReset() throws Exception {
        JsonNode switched = patch("{\"profile\":\"lte-edge\"}");
        assertThat(switched.path("profile").asText()).isEqualTo("lte-edge");
        assertThat(switched.path("mode").asText()).isEqualTo("profile");
        assertThat(switched.path("uplink").path("delayMs").asDouble()).isEqualTo(120);
        assertThat(uplink.spec().jitterMs()).isEqualTo(90.0);
        // lte-edge 有 3% GE 丢包，采样到第一个非丢包再验延迟区间，避免 3% 假红。
        long delay = -1;
        for (int i = 0; i < 1_000 && delay < 0; i++) {
            delay = uplink.verdict(64);
        }
        assertThat(delay).isBetween(120L, 210L);

        patch("{\"delayMs\":5,\"jitterMs\":0,\"dropPct\":0}");
        JsonNode reset = patch("{\"reset\":true}");

        assertThat(reset.path("profile").asText()).isEqualTo("lte-edge");
        assertThat(reset.path("mode").asText()).isEqualTo("profile");
        assertThat(reset.path("uplink").path("delayMs").asDouble()).isEqualTo(120);
        assertThat(reset.path("uplink").path("dropModel").asText()).isEqualTo("ge");
        assertThat(uplink.spec().delayMs()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("参数替换不清零累计统计（面板上的 fwd/drop 不应因调参归零）")
    void applyRetainsCounters() throws Exception {
        patch("{\"delayMs\":0,\"jitterMs\":0}");
        assertThat(uplink.verdict(64)).isZero();
        assertThat(uplink.verdict(64)).isZero();

        JsonNode view = patch("{\"delayMs\":50}");

        assertThat(view.path("uplink").path("stats").path("forwarded").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(view.path("uplink").path("stats").path("avgDelayMs").asLong()).isZero();
    }

    // ------------------------------------------------------------------
    // 非法输入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("非法字段/类型/范围/互斥一律 400，并给出可读原因")
    void invalidPatchesAreRejected() throws Exception {
        String[] bodies = {
                "{}",
                "{\"bogus\":1}",
                "{\"delayMs\":-1}",
                "{\"delayMs\":\"slow\"}",
                "{\"jitterMs\":5001}",
                "{\"dropPct\":101}",
                "{\"bandwidthKbps\":-1}",
                "{\"partitionDownSec\":5}",
                "{\"partitionCycleSec\":10,\"partitionDownSec\":11}",
                "{\"profile\":\"nope\"}",
                "{\"profile\":\"lan\",\"delayMs\":1}",
                "{\"reset\":false}",
                "{\"reset\":true,\"delayMs\":1}",
                "{\"direction\":\"sideways\",\"delayMs\":1}",
                "{\"direction\":5,\"delayMs\":1}",
        };
        for (String body : bodies) {
            HttpResponse<String> resp = http.send(request("/profile").method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).as("body=%s", body).isEqualTo(400);
            assertThat(mapper.readTree(resp.body()).path("result").asText())
                    .as("body=%s", body).isNotBlank();
        }
        // 全部拒绝后引擎仍是初始参数
        assertThat(uplink.spec().delayMs()).isZero();
        assertThat(uplink.spec().partitionDownSec()).isZero();
    }

    @Test
    @DisplayName("坏 JSON / 非对象 / 超大 body：400 / 400 / 413，不 500")
    void malformedBodiesAreRejected() throws Exception {
        HttpResponse<String> badJson = http.send(
                request("/profile").method("PATCH", HttpRequest.BodyPublishers.ofString("{not json")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(badJson.statusCode()).isEqualTo(400);

        HttpResponse<String> array = http.send(
                request("/profile").method("PATCH", HttpRequest.BodyPublishers.ofString("[1,2]")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(array.statusCode()).isEqualTo(400);

        String huge = "{\"delayMs\":1,\"pad\":\"" + "x".repeat(70 * 1024) + "\"}";
        HttpResponse<String> tooLarge = http.send(
                request("/profile").method("PATCH", HttpRequest.BodyPublishers.ofString(huge)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tooLarge.statusCode()).isEqualTo(413);
    }

    @Test
    @DisplayName("方法/路径：POST 405（带 Allow），未知路径 404，均返回 JSON")
    void methodAndPathErrors() throws Exception {
        HttpResponse<String> post = http.send(
                request("/profile").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(405);
        assertThat(post.headers().firstValue("Allow").orElse("")).isEqualTo("GET, PATCH");
        assertThat(mapper.readTree(post.body()).path("status").asText()).isEqualTo("error");

        HttpResponse<String> missing = http.send(
                request("/nope").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------

    private JsonNode get() throws Exception {
        HttpResponse<String> resp = http.send(request("/profile").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return mapper.readTree(resp.body());
    }

    private JsonNode patch(String json) throws Exception {
        HttpResponse<String> resp = http.send(
                request("/profile").header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("PATCH %s -> %s", json, resp.body()).isEqualTo(200);
        return mapper.readTree(resp.body());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(3));
    }
}
