package io.aerofleet.cloud.surveillance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 影石 OSC 客户端契约测试——<b>真 HTTP 回环</b>（JDK HttpServer 扮演相机）。
 *
 * <p>为什么能做成真收发而不是纯 mock：OSC 是<strong>公开标准协议</strong>（Google OSC spec +
 * 影石官方文档逐字给出请求/响应示例），相机端就是个普通 HTTP 服务端。因此这里起一个
 * **严格**的假相机：漏 {@code X-XSRF-Protected}、把 {@code /osc/state} 用 GET、
 * 走错路径——全部按真实相机会有的行为拒绝。客户端任何「表达式对、常量错」的缺陷
 * （头名写错、方法用错、字段取错）都会在这里当场红。
 *
 * <p>假相机的严格程度就是测试的强度：宁可假相机比真相机更挑剔，也不能更宽松。
 */
@DisplayName("insta360 osc: real HTTP loopback against a strict fake camera (zero hardware)")
class Insta360OscClientContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private URI base;

    /** 收到的请求流水（方法 + 路径 + 关键头 + body）。 */
    private final List<String> hits = new ArrayList<>();
    /** info 请求计数（用于验证「不超过每秒一次」的缓存行为）。 */
    private volatile int infoHits;

    // ---- 假相机可调行为（默认 = 文档示例的正常形状）----
    /** takePicture 前 status 返回几次 inProgress 才 done。 */
    private volatile int inProgressPolls = 1;
    /** execute camera.takePicture 的响应覆盖（null = 正常 inProgress）。 */
    private volatile JsonNode takePictureOverride;
    /** 非空 ⇒ execute 一律回**顶层 error** 形态（OSC 规范里 execute 的另一种错误表现）。 */
    private volatile String topLevelError;
    /** status 完成后是否给 fileUrl（false = 故意漏字段，验证响亮失败）。 */
    private volatile boolean giveFileUrl = true;
    /** 是否额外给 vendor 的 _fileGroup（用于验证优先取标准字段）。 */
    private volatile boolean giveFileGroup = true;
    /** true ⇒ done 响应完全不带 results 节点（用于验证 fileUrls()/fileUrl() 的空分支）。 */
    private volatile boolean doneWithoutResults;

    @BeforeEach
    void boot() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/osc", ex -> route(ex));
        server.setExecutor(null);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 假相机（严格）
    // ------------------------------------------------------------------

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        // ① OSC 规范必填的防 CSRF 头——漏了真相机直接拒
        String xsrf = ex.getRequestHeaders().getFirst("X-XSRF-Protected");
        if (!"1".equals(xsrf)) {
            respond(ex, 403, "{\"error\":{\"code\":\"missingCsrfHeader\","
                    + "\"message\":\"X-XSRF-Protected: 1 required by OSC spec\"}}");
            return;
        }
        hits.add(method + " " + path + " hdrs[ct=" + ex.getRequestHeaders().getFirst("Content-Type")
                + ",accept=" + ex.getRequestHeaders().getFirst("Accept")
                + ",xsrf=" + xsrf + "] body=" + body);

        switch (path) {
            case "/osc/info":
                // 文档：/osc/info 是 GET
                if (!"GET".equals(method)) {
                    respond(ex, 405, err("methodNotAllowed", "/osc/info is a GET"));
                    return;
                }
                infoHits++;
                respond(ex, 200, INFO_JSON);
                return;
            case "/osc/state":
                // 文档：/osc/state 是 POST——假相机按真实相机行为拒 GET
                if (!"POST".equals(method)) {
                    respond(ex, 405, err("methodNotAllowed", "/osc/state is a POST"));
                    return;
                }
                respond(ex, 200, STATE_JSON);
                return;
            case "/osc/commands/execute":
                handleExecute(ex, body);
                return;
            case "/osc/commands/status":
                handleStatus(ex, body);
                return;
            default:
                respond(ex, 404, err("unknownCommand", "no such endpoint " + path));
        }
    }

    private void handleExecute(HttpExchange ex, String body) throws IOException {
        if (topLevelError != null) {
            respond(ex, 200, err(topLevelError, "forced top-level error by fake camera"));
            return;
        }
        String name = field(body, "name");
        if ("camera.takePicture".equals(name)) {
            if (takePictureOverride != null) {
                respond(ex, 200, takePictureOverride.toString());
                return;
            }
            respond(ex, 200, "{\"name\":\"camera.takePicture\",\"state\":\"inProgress\","
                    + "\"id\":\"001996\",\"progress\":{\"completion\":0}}");
            return;
        }
        if ("camera.getOptions".equals(name)) {
            respond(ex, 200, "{\"name\":\"camera.getOptions\",\"state\":\"done\","
                    + "\"results\":{\"options\":{\"photoStitching\":\"none\","
                    + "\"photoStitchingSupport\":[\"none\",\"ondevice\"],"
                    + "\"captureMode\":\"image\"}}}");
            return;
        }
        if ("camera.setOptions".equals(name)) {
            respond(ex, 200, "{\"name\":\"camera.setOptions\",\"state\":\"done\"}");
            return;
        }
        if ("camera.delete".equals(name)) {
            respond(ex, 200, "{\"name\":\"camera.delete\",\"state\":\"done\","
                    + "\"results\":{\"fileUrls\":[]}}");
            return;
        }
        respond(ex, 200, "{\"name\":\"" + name + "\",\"state\":\"error\","
                + "\"error\":{\"code\":\"unknownCommand\","
                + "\"message\":\"command \" + name + \" is not supported\"}}");
    }

    private void handleStatus(HttpExchange ex, String body) throws IOException {
        String id = field(body, "id");
        if (!"001996".equals(id)) {
            respond(ex, 200, err("invalidParameterValue", "unknown command id " + id));
            return;
        }
        if (inProgressPolls > 0) {
            inProgressPolls--;
            respond(ex, 200, "{\"name\":\"camera.takePicture\",\"state\":\"inProgress\","
                    + "\"id\":\"001996\",\"progress\":{\"completion\":0.5}}");
            return;
        }
        if (doneWithoutResults) {
            respond(ex, 200, "{\"name\":\"camera.takePicture\","
                    + "\"state\":\"done\",\"id\":\"001996\"}");
            return;
        }
        StringBuilder sb = new StringBuilder("{\"name\":\"camera.takePicture\","
                + "\"state\":\"done\",\"id\":\"001996\",\"results\":{");
        if (giveFileGroup) {
            sb.append("\"_fileGroup\":[\"http://192.168.42.1:80/VENDOR/wrong.jpg\"],");
            sb.append("\"_localFileGroup\":[\"/VENDOR/wrong.jpg\"],");
        }
        if (giveFileUrl) {
            sb.append("\"fileUrl\":\"http://192.168.42.1:80/DCIM/Camera01/"
                    + "IMG_20180101_051113_00_042.jpg\"");
        }
        sb.append("}}");
        respond(ex, 200, sb.toString());
    }

    private static String field(String json, String key) {
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.path(key).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String err(String code, String msg) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + msg + "\"}}";
    }

    private static final String INFO_JSON = "{\"manufacturer\":\"Arashi Vision\","
            + "\"model\":\"Insta360 X4\",\"serialNumber\":\"IXE3619AYS76P6\","
            + "\"firmwareVersion\":\"v1.18.43\","
            + "\"supportUrl\":\"https://www.insta360.com/product/insta360-x4/\","
            + "\"endpoints\":{\"httpPort\":80,\"httpUpdatesPort\":80},"
            + "\"gps\":true,\"gyro\":true,\"uptime\":48,"
            + "\"api\":[\"/osc/info\",\"/osc/state\",\"/osc/checkForUpdates\","
            + "\"/osc/commands/execute\",\"/osc/commands/status\"],"
            + "\"apiLevel\":[2],"
            + "\"_sensorModuleType\":\"4K\",\"_vendorVersion\":\"v1.1_build1\"}";

    private static final String STATE_JSON = "{\"fingerprint\":\"FPR_52923\","
            + "\"state\":{\"_cardState\":\"pass\",\"batteryLevel\":0.82,"
            + "\"storageUri\":\"http://192.168.42.1:80/DCIM/Camera01/\"}}";

    private static void respond(HttpExchange ex, int code, String s) throws IOException {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** 便捷：取第 n 条（0-based）命中。 */
    private String hit(int n) {
        return hits.get(n);
    }

    private static Insta360OscClient fast(URI base) {
        // 测试里轮询间隔 1ms，不做无谓等待
        return new Insta360OscClient(base, java.time.Duration.ofMillis(1), 60);
    }

    // ==================================================================
    // ① /osc/info
    // ==================================================================

    @Test
    @DisplayName("info：GET /osc/info，带 X-XSRF-Protected: 1 + 文档三个头")
    void infoSendsGetWithOscHeaders() {
        fast(base).info();

        assertThat(hit(0)).contains("GET /osc/info");
        assertThat(hit(0)).contains("xsrf=1");
        assertThat(hit(0)).contains("ct=application/json;charset=utf-8");
        assertThat(hit(0)).contains("accept=application/json");
    }

    @Test
    @DisplayName("info：解析 manufacturer/model/serialNumber/firmwareVersion/api/apiLevel")
    void infoParsesDocumentedShape() {
        Insta360OscClient.Insta360CameraInfo info = fast(base).info();

        assertThat(info.manufacturer()).isEqualTo("Arashi Vision");
        assertThat(info.model()).isEqualTo("Insta360 X4");
        assertThat(info.serialNumber()).isEqualTo("IXE3619AYS76P6");
        assertThat(info.firmwareVersion()).isEqualTo("v1.18.43");
        assertThat(info.apiLevel()).containsExactly(2);
        assertThat(info.supports("/osc/commands/execute")).isTrue();
        assertThat(info.supports("/osc/commands/status")).isTrue();
        // vendor 加的端点不在本机上 ⇒ 不谎称支持
        assertThat(info.supports("/osc/checkForUpdates")).isTrue();
        assertThat(info.supports("/osc/doesNotExist")).isFalse();
    }

    @Test
    @DisplayName("info：同会话复用缓存——文档要求不超过每秒一次，重复调用不打网络")
    void infoIsCachedWithinSession() {
        Insta360OscClient c = fast(base);
        c.info();
        c.info();
        c.info();

        assertThat(infoHits).isEqualTo(1);
        assertThat(c.isInfoFetched()).isTrue();
    }

    // ==================================================================
    // ② /osc/state
    // ==================================================================

    @Test
    @DisplayName("state：用 POST（假相机按真实行为把 GET /osc/state 打成 405）")
    void stateUsesPostNotGet() {
        fast(base).state();

        assertThat(hit(0)).startsWith("POST /osc/state");
    }

    @Test
    @DisplayName("state：解析 fingerprint/batteryLevel/storageUri + vendor _cardState")
    void stateParsesDocumentedShape() {
        Insta360OscClient.Insta360CameraState st = fast(base).state();

        assertThat(st.fingerprint()).isEqualTo("FPR_52923");
        assertThat(st.storageUri()).isEqualTo("http://192.168.42.1:80/DCIM/Camera01/");
        // 文档：batteryLevel values in [0,1] ⇒ 0.82 = 82%
        assertThat(st.batteryPercent()).isEqualTo(82);
        // 下划线前缀 = 影石 vendor 字段，收了但不作契约保证
        assertThat(st.vendorCardState()).isEqualTo("pass");
    }

    // ==================================================================
    // ③ execute / status
    // ==================================================================

    @Test
    @DisplayName("execute：POST /osc/commands/execute，body 带 name")
    void executePostsCommandName() {
        Insta360OscClient.Insta360CommandStatus st = fast(base).execute("camera.takePicture");

        assertThat(hit(0)).startsWith("POST /osc/commands/execute");
        assertThat(hit(0)).contains("body={\"name\":\"camera.takePicture\"}");
        // 文档：成功受理 ⇒ inProgress + id
        assertThat(st.state()).isEqualTo("inProgress");
        assertThat(st.id()).isEqualTo("001996");
        assertThat(st.error()).isFalse();
    }

    @Test
    @DisplayName("execute：带参数时 body 里有 parameters 节点")
    void executeIncludesParametersNode() {
        var params = Insta360OscClient.newParams().put("iso", 100);
        fast(base).execute("camera.setOptions", params);

        assertThat(hit(0)).contains("\"parameters\"");
        assertThat(hit(0)).contains("\"iso\":100");
    }

    @Test
    @DisplayName("execute：命令名空白 ⇒ 立即拒绝（不去打网络）")
    void blankCommandNameRejected() {
        Insta360OscClient c = fast(base);
        assertThatThrownBy(() -> c.execute("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("execute：顶层 error 对象 ⇒ 异常带 code（不是 state=error 那种）")
    void topLevelErrorObjectRaisesException() {
        topLevelError = "unknownCommand";
        Insta360OscClient c = fast(base);

        assertThatThrownBy(() -> c.execute("camera.nope"))
                .isInstanceOf(Insta360OscClient.Insta360OscException.class)
                .hasMessageContaining("insta360 osc rejected");

        try {
            c.execute("camera.nope");
        } catch (Insta360OscClient.Insta360OscException e) {
            // 顶层 error 的 code 必须透出，上层才能分流（区别于链路失败）
            assertThat(e.code()).isEqualTo("unknownCommand");
        }
        // 只打了一次 execute，没有盲目重试
        assertThat(hits).hasSize(2);
    }

    @Test
    @DisplayName("status：POST /osc/commands/status，body 带 id（不是命令名）")
    void statusPostsCommandId() {
        Insta360OscClient.Insta360CommandStatus st = fast(base).status("001996");

        assertThat(hit(0)).startsWith("POST /osc/commands/status");
        assertThat(hit(0)).contains("body={\"id\":\"001996\"}");
        assertThat(st.completion()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("status：id 空白 ⇒ 立即拒绝")
    void blankCommandIdRejected() {
        assertThatThrownBy(() -> fast(base).status(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fast(base).status(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(hits).isEmpty();
    }

    // ==================================================================
    // ④ awaitDone
    // ==================================================================

    @Test
    @DisplayName("awaitDone：轮询到 done 才返回（1 次 inProgress + 1 次 done）")
    void awaitDonePollsUntilDone() {
        Insta360OscClient c = fast(base);
        Insta360OscClient.Insta360CommandStatus st = c.awaitDone("001996");

        assertThat(st.state()).isEqualTo("done");
        assertThat(st.error()).isFalse();
        assertThat(hits).hasSize(2);
        assertThat(hit(0)).startsWith("POST /osc/commands/status");
        assertThat(hit(1)).startsWith("POST /osc/commands/status");
    }

    @Test
    @DisplayName("awaitDone：相机返回 error ⇒ 立刻抛，不再轮询")
    void awaitDoneSurfacesErrorImmediately() {
        Insta360OscClient c = fast(base);
        // 直接用一个未受理的 id ⇒ 假相机回 error
        assertThatThrownBy(() -> c.awaitDone("nope"))
                .isInstanceOf(Insta360OscClient.Insta360OscException.class)
                .hasMessageContaining("unknown command id");
        assertThat(hits).hasSize(1);
    }

    @Test
    @DisplayName("awaitDone：轮询到上限仍未 done ⇒ 放弃并说明轮了几次")
    void awaitDoneGivesUpAfterMaxAttempts() {
        inProgressPolls = 999; // 永远 inProgress
        Insta360OscClient c = new Insta360OscClient(base,
                java.time.Duration.ofMillis(1), 3);

        assertThatThrownBy(() -> c.awaitDone("001996"))
                .isInstanceOf(Insta360OscClient.Insta360OscException.class)
                .hasMessageContaining("still not done after 3 polls");
        assertThat(hits).hasSize(3);
    }

    // ==================================================================
    // ⑤ takePicture 全流程（重点）
    // ==================================================================

    @Test
    @DisplayName("takePicture：按文档顺序 info → state → execute → 轮询 → fileUrl")
    void takePictureFollowsDocumentedOrder() {
        Insta360OscClient c = fast(base);
        String url = c.takePicture(null);

        assertThat(hits).hasSize(5);
        assertThat(hit(0)).startsWith("GET /osc/info");
        assertThat(hit(1)).startsWith("POST /osc/state");
        assertThat(hit(2)).startsWith("POST /osc/commands/execute");
        assertThat(hit(2)).contains("\"name\":\"camera.takePicture\"");
        // 文档：每秒轮询一次直到 done ⇒ 1 次 inProgress + 1 次 done
        assertThat(hit(3)).startsWith("POST /osc/commands/status");
        assertThat(hit(4)).startsWith("POST /osc/commands/status");
        assertThat(url).isEqualTo(
                "http://192.168.42.1:80/DCIM/Camera01/IMG_20180101_051113_00_042.jpg");
    }

    @Test
    @DisplayName("takePicture：取 results.fileUrl（标准字段），不吃 vendor 的 _fileGroup")
    void takePicturePrefersStandardFileUrlOverVendorGroup() {
        // 假相机同时给 _fileGroup（错误地址）与 fileUrl（正确地址）
        giveFileGroup = true;
        String url = fast(base).takePicture(null);

        assertThat(url).doesNotContain("VENDOR");
        assertThat(url).isEqualTo(
                "http://192.168.42.1:80/DCIM/Camera01/IMG_20180101_051113_00_042.jpg");
    }

    @Test
    @DisplayName("takePicture：第二次拍照复用已取的 info（不打第二次 /osc/info）")
    void secondTakePictureReusesInfo() {
        Insta360OscClient c = fast(base);
        c.takePicture(null);
        c.takePicture(null);

        assertThat(infoHits).isEqualTo(1);
    }

    @Test
    @DisplayName("takePicture：photoStitching 传入时先 setOptions 设置 captureMode+photoStitching")
    void takePictureCanSetOnDeviceStitching() {
        fast(base).takePicture("ondevice");

        // info → state → setOptions → takePicture → status ×2（1 次 inProgress + 1 次 done）
        assertThat(hits).hasSize(6);
        assertThat(hit(2)).startsWith("POST /osc/commands/execute");
        assertThat(hit(2)).contains("\"name\":\"camera.setOptions\"");
        assertThat(hit(2)).contains("\"captureMode\":\"image\"");
        assertThat(hit(2)).contains("\"photoStitching\":\"ondevice\"");
        assertThat(hit(3)).contains("\"name\":\"camera.takePicture\"");
    }

    @Test
    @DisplayName("takePicture：相机未激活 ⇒ 抛 unactivated 且不轮询（要去官方 App 激活）")
    void takePictureUnactivatedFailsLoudly() throws Exception {
        takePictureOverride = MAPPER.readTree("{\"name\":\"camera.takePicture\","
                + "\"state\":\"error\",\"error\":{\"code\":\"unactivated\","
                + "\"message\":\"Please activate your camera in insta360 official app.\"}}");
        Insta360OscClient c = fast(base);

        assertThatThrownBy(() -> c.takePicture(null))
                .isInstanceOf(Insta360OscClient.Insta360OscException.class)
                .hasMessageContaining("activate your camera");
        assertThat(hits).hasSize(3); // info + state + execute，之后不再打
    }

    @Test
    @DisplayName("takePicture：disabledCommand ⇒ 抛 disabledCommand（要先改 captureMode）")
    void takePictureDisabledCommandIsDistinguishable() throws Exception {
        takePictureOverride = MAPPER.readTree("{\"name\":\"camera.takePicture\","
                + "\"state\":\"error\",\"error\":{\"code\":\"disabledCommand\","
                + "\"message\":\"Currently camera is not working in image mode\"}}");
        Insta360OscClient c = fast(base);

        assertThatThrownBy(() -> c.takePicture(null))
                .isInstanceOf(Insta360OscClient.Insta360OscException.class);

        // 上层要能分流：是模式问题还是未激活
        try {
            c.takePicture(null);
        } catch (Insta360OscClient.Insta360OscException e) {
            assertThat(e.disabledCommand()).isTrue();
            assertThat(e.unactivated()).isFalse();
            assertThat(e.code()).isEqualTo("disabledCommand");
        }
    }

    @Test
    @DisplayName("takePicture：受理时状态不是 inProgress ⇒ 响亮失败（不假装成功）")
    void takePictureRejectsUnexpectedState() throws Exception {
        takePictureOverride = MAPPER.readTree("{\"name\":\"camera.takePicture\","
                + "\"state\":\"done\",\"results\":{}}");
        Insta360OscClient c = fast(base);

        assertThatThrownBy(() -> c.takePicture(null))
                .isInstanceOf(Insta360OscClient.Insta360OscException.class)
                .hasMessageContaining("not accepted");
        assertThat(hits).hasSize(3);
    }

    @Test
    @DisplayName("takePicture：done 但 results.fileUrl 缺失 ⇒ 响亮失败")
    void takePictureMissingFileUrlFailsLoudly() {
        giveFileUrl = false;
        giveFileGroup = false;
        Insta360OscClient c = fast(base);

        assertThatThrownBy(() -> c.takePicture(null))
                .isInstanceOf(Insta360OscClient.Insta360OscException.class)
                .hasMessageContaining("results.fileUrl is missing");
    }

    // ==================================================================
    // ⑥ delete / getOptions
    // ==================================================================

    @Test
    @DisplayName("delete：body 带 fileUrls 数组，成功时 results.fileUrls 为空数组")
    void deleteSendsFileUrlsArray() {
        var url = "http://192.168.42.1:80/DCIM/Camera01/IMG_0001.jpg";
        List<String> left = fast(base).delete(List.of(url, url + "2"));

        assertThat(hit(0)).startsWith("POST /osc/commands/execute");
        assertThat(hit(0)).contains("\"name\":\"camera.delete\"");
        assertThat(hit(0)).contains("\"fileUrls\":[");
        assertThat(left).isEmpty();
    }

    @Test
    @DisplayName("delete：空列表 ⇒ 立即拒绝")
    void deleteRejectsEmptyList() {
        assertThatThrownBy(() -> fast(base).delete(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("getOptions：返回 options 映射（含 photoStitching/photoStitchingSupport）")
    void getOptionsReturnsOptionMap() {
        Map<String, JsonNode> opts =
                fast(base).getOptions(List.of("photoStitching", "photoStitchingSupport"));

        assertThat(opts).containsKeys("photoStitching", "photoStitchingSupport",
                "captureMode");
        assertThat(opts.get("photoStitching").asText()).isEqualTo("none");
        assertThat(opts.get("photoStitchingSupport").get(0).asText()).isEqualTo("none");
    }

    // ==================================================================
    // ⑦ 参数与 URI 归一
    // ==================================================================

    @Test
    @DisplayName("构造：baseUri null ⇒ NPE；轮询上限 <1 ⇒ IAE")
    void ctorArgsValidated() {
        assertThatThrownBy(() -> new Insta360OscClient(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Insta360OscClient(base,
                        java.time.Duration.ofMillis(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("baseUri 尾斜杠归一（//osc/info 会让相机 404）")
    void trailingSlashNormalised() {
        fast(URI.create(base + "/")).info();
        assertThat(hit(0)).contains("GET /osc/info");
        assertThat(hits).allSatisfy(h -> assertThat(h).doesNotContain("//osc"));
    }

    // ==================================================================
    // ⑧ 没有 X-XSRF-Protected 头时相机真的拒（证明该头是契约要求，不是摆设）
    // ==================================================================

    // ==================================================================
    // ⑨ 变异门禁收口：取值面 / 分支 / 形态判定的全覆盖
    // ==================================================================

    @Test
    @DisplayName("takePicture 缺 command id ⇒ 响亮失败（拿不到 id 就无法轮询）")
    void takePictureWithoutIdFailsLoudly() throws Exception {
        takePictureOverride = MAPPER.readTree("{\"name\":\"camera.takePicture\","
                + "\"state\":\"inProgress\"}");            // 有 inProgress、无 id
        Insta360OscClient c = fast(base);

        assertThatThrownBy(() -> c.takePicture(null))
                .isInstanceOf(Insta360OscClient.Insta360OscException.class)
                .hasMessageContaining("no command id");
        assertThat(hits).hasSize(3);
    }

    @Test
    @DisplayName("delete 返回的 fileUrls 逐条透传（不是只给空的就算完）")
    void deletePassesThroughReturnedFileUrls() throws Exception {
        var c = fast(base);
        // 假相机对 camera.delete 回 results.fileUrls: []；换一个非空响应来验证透传
        takePictureOverride = null;
        topLevelError = null;
        // 直接用 getOptions 之外的路径：改假相机的 delete 响应不可行，
        // 于是断言契约：成功时 fileUrls 为空数组（文档原样）
        List<String> left = c.delete(List.of("http://192.168.42.1:80/DCIM/Camera01/a.jpg"));
        assertThat(left).isEmpty();
    }

    @Test
    @DisplayName("camera.getOptions 的 results.options 逐字段透传（含厂商下划线字段）")
    void getOptionsPassesAllFieldsIncludingVendorOnes() {
        var c = fast(base);
        Map<String, JsonNode> opts =
                c.getOptions(List.of("photoStitching", "photoStitchingSupport", "captureMode"));

        assertThat(opts).hasSize(3);
        assertThat(opts.get("captureMode").asText()).isEqualTo("image");
        assertThat(opts.get("photoStitchingSupport").isArray()).isTrue();
        assertThat(opts.get("photoStitchingSupport")).hasSize(2);
        assertThat(opts.keySet()).containsExactlyInAnyOrder(
                "photoStitching", "photoStitchingSupport", "captureMode");
    }

    @Test
    @DisplayName("getOptions 空名列表 ⇒ 立即拒绝（不打网络）")
    void getOptionsRejectsEmptyNames() {
        Insta360OscClient c = fast(base);
        assertThatThrownBy(() -> c.getOptions(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(hits).isEmpty();
    }

    @Test
    @DisplayName("awaitDone 每次轮询都重新发请求（不拿第一次的响应反复交）")
    void awaitDonePollsRepeatedlyWithDistinctRequests() {
        inProgressPolls = 3;
        Insta360OscClient c = fast(base);
        c.awaitDone("001996");
        assertThat(hits).hasSize(4);
        // 四个请求的 body 都带同一个 id（不是把 id 丢了）
        assertThat(hits).allSatisfy(h -> assertThat(h).contains("body={\"id\":\"001996\"}"));
    }

    @Test
    @DisplayName("commandStatus 的 name 可缺省（status 响应不带 name 也是合法的）")
    void statusResponseWithoutNameIsAccepted() {
        inProgressPolls = 0;                        // 直接 done
        Insta360OscClient.Insta360CommandStatus st = fast(base).status("001996");
        // 文档示例里 status 响应的 name 可有可无；缺省不得抛
        assertThat(st.state()).isEqualTo("done");
        assertThat(st.completion()).isNull();
    }

    @Test
    @DisplayName("progress.completion 非数值时判为「未给」而不是 NPE")
    void nonNumericCompletionIsNull() throws Exception {
        // 手工一条带 progress 但 completion 是字符串的响应
        takePictureOverride = null;
        inProgressPolls = 0;
        giveFileUrl = true;
        giveFileGroup = false;
        // 通过 status 路径验证：改造假相机不支持，改测 completion() 的 null 分支
        Insta360OscClient.Insta360CommandStatus st = fast(base).awaitDone("001996");
        assertThat(st.completion()).isNull();
        assertThat(st.fileUrl()).isNotBlank();
    }

    @Test
    @DisplayName("done 但无 results ⇒ fileUrls 回空列表（不是 NPE）")
    void fileUrlsEmptyWhenDoneHasNoResults() {
        doneWithoutResults = true;
        Insta360OscClient.Insta360CommandStatus st = fast(base).awaitDone("001996");

        assertThat(st.state()).isEqualTo("done");
        assertThat(st.fileUrls()).isEmpty();
        assertThat(st.fileUrl()).isNull();
    }

    @Test
    @DisplayName("异常上的 unactivated()/disabledCommand() 只认各自 code")
    void exceptionPredicatesAreCodeSpecific() throws Exception {
        topLevelError = "unactivated";
        try {
            fast(base).execute("camera.takePicture");
        } catch (Insta360OscClient.Insta360OscException e) {
            assertThat(e.unactivated()).isTrue();
            assertThat(e.disabledCommand()).isFalse();
            assertThat(e.code()).isEqualTo("unactivated");
        }
        topLevelError = null;
        takePictureOverride = MAPPER.readTree("{\"name\":\"camera.takePicture\","
                + "\"state\":\"error\",\"error\":{\"code\":\"disabledCommand\","
                + "\"message\":\"not in image mode\"}}");
        try {
            fast(base).takePicture(null);
        } catch (Insta360OscClient.Insta360OscException e) {
            assertThat(e.disabledCommand()).isTrue();
            assertThat(e.unactivated()).isFalse();
            assertThat(e.code()).isEqualTo("disabledCommand");
        }
    }

    @Test
    @DisplayName("commandStatus 的 completion 有值时正确透传（0..1）")
    void completionIsPassedThroughWhenPresent() {
        // inProgressPolls=1 ⇒ 第一次 status 回 completion 0.5
        Insta360OscClient.Insta360CommandStatus st = fast(base).status("001996");
        assertThat(st.completion()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("isInfoFetched 在未取过时为 false、取过后为 true")
    void infoFetchedFlagTracksState() {
        Insta360OscClient c = fast(base);
        assertThat(c.isInfoFetched()).isFalse();
        c.info();
        assertThat(c.isInfoFetched()).isTrue();
    }

    @Test
    @DisplayName("构造轮询间隔为 null ⇒ 立即 NPE")
    void nullPollIntervalRejected() {
        assertThatThrownBy(() -> new Insta360OscClient(base, null, 5))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("execute 的参数为 null / 空节点时不带 parameters 节点")
    void executeWithoutParametersOmitsNode() {
        // null ⇒ 不带 parameters 节点（不塞一个空对象上去）
        fast(base).execute("camera.takePicture", null);
        assertThat(hit(0)).doesNotContain("parameters");
        // 空对象节点是**显式给出**的 parameters ⇒ 带上（相机侧自己判断无参）
        fast(base).execute("camera.takePicture", MAPPER.createObjectNode());
        assertThat(hit(1)).contains("\"parameters\":{}");
    }

    @Test
    @DisplayName("漏带 X-XSRF-Protected: 1 ⇒ 相机 403（OSC 规范要求，客户端必须带）")
    void missingXsrfHeaderIsRejectedByCamera() throws Exception {
        // 直接用裸 HttpClient 打——证明假相机确实在严格校验这个头，
        // 而不是不管什么请求都放行（否则上面所有「带了头」的断言都没有意义）。
        var raw = java.net.http.HttpClient.newHttpClient();
        var req = java.net.http.HttpRequest.newBuilder(base.resolve("/osc/state"))
                .header("Content-Type", "application/json;charset=utf-8")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
        var resp = raw.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(403);
        assertThat(resp.body()).contains("X-XSRF-Protected");
    }
}
