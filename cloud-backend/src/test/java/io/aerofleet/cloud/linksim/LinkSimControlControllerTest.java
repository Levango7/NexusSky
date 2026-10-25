package io.aerofleet.cloud.linksim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 云端 link-sim 控制转发的契约测试（P2 #20）。
 *
 * <p>用真实 JDK {@link HttpServer} 假扮 link-sim（回环 + 随机端口），验证：
 * 转发的方法/路径/请求体、成功响应回传、上游 400 透传、连不上 503、
 * 超时 504、上游 5xx 502、非对象请求体本地拒绝，以及 RBAC 注解。
 *
 * <p>为什么不走 MockMvc：RBAC 由 {@code RoleInterceptor} 在 preHandle 里拦截，
 * MockMvc 对"拦截器返回 false"这类控制流失明（本轮踩过的坑）；角色要求直接
 * 断言注解，转发行为直接调控制器方法，两边都不经过 MVC 反射。
 */
@DisplayName("云端 link-sim 控制转发：GET/PATCH /api/v1/link-sim/control")
class LinkSimControlControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer upstream;
    private Thread upstreamThread;
    private LinkSimProperties props;
    private LinkSimControlClient client;
    private LinkSimControlController controller;

    /** 假 link-sim 收到的请求（方法 + 路径 + body）。 */
    private final List<String> seen = new CopyOnWriteArrayList<>();

    /** 慢响应开关（超时用例）。 */
    private volatile boolean slowGet;

    @BeforeEach
    void setUp() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/profile", this::handleUpstream);
        upstream.createContext("/boom", ex -> respond(ex, 500,
                "{\"status\":\"error\",\"result\":\"upstream exploded\"}"));
        upstreamThread = new Thread(upstream::start, "fake-linksim");
        upstreamThread.setDaemon(true);
        upstreamThread.start();

        props = new LinkSimProperties();
        props.setControlUrl("http://127.0.0.1:" + upstream.getAddress().getPort() + "/profile");
        props.setTimeoutMs(2000);
        client = new LinkSimControlClient(props, mapper);
        controller = new LinkSimControlController(client, mapper);
    }

    @AfterEach
    void tearDown() {
        upstream.stop(0);
    }

    // ------------------------------------------------------------------
    // 转发行为
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET：转发到上游 /profile，把上游 JSON 原样返回")
    void getForwardsAndReturnsUpstreamJson() throws Exception {
        ResponseEntity<JsonNode> resp = controller.status();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().path("profile").asText()).isEqualTo("lte");
        assertThat(resp.getBody().path("uplink").path("delayMs").asDouble()).isEqualTo(45.0);
        assertThat(seen).containsExactly("GET /profile body=<empty>");
    }

    @Test
    @DisplayName("PATCH：请求体逐字节转发（字段名/值不被云端改写），返回应用后的视图")
    void patchForwardsBodyVerbatim() throws Exception {
        JsonNode body = mapper.readTree("{\"direction\":\"uplink\",\"delayMs\":120,\"jitterMs\":0}");
        ResponseEntity<JsonNode> resp = controller.patch(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().path("mode").asText()).isEqualTo("mixed");
        assertThat(seen).containsExactly(
                "PATCH /profile body={\"direction\":\"uplink\",\"delayMs\":120,\"jitterMs\":0}");
    }

    // ------------------------------------------------------------------
    // 错误映射
    // ------------------------------------------------------------------

    @Test
    @DisplayName("上游 400（参数非法）：云端 400，原因原样透传")
    void upstreamBadRequestIsPassedThrough() throws Exception {
        JsonNode body = mapper.readTree("{\"delayMs\":-5}");
        ResponseEntity<JsonNode> resp = controller.patch(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().path("status").asText()).isEqualTo("error");
        assertThat(resp.getBody().path("result").asText()).contains("delayMs");
    }

    @Test
    @DisplayName("link-sim 没起控制通道（端口不通）：503，提示 --control-port")
    void unreachableSimIs503() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        props.setControlUrl("http://127.0.0.1:" + freePort + "/profile");

        ResponseEntity<JsonNode> resp = controller.status();

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(resp.getBody().path("result").asText()).contains("--control-port");
    }

    @Test
    @DisplayName("上游超时：504（不是 500，也不是假 200）")
    void upstreamTimeoutIs504() throws Exception {
        slowGet = true;
        props.setTimeoutMs(100);
        client = new LinkSimControlClient(props, mapper);
        controller = new LinkSimControlController(client, mapper);

        ResponseEntity<JsonNode> resp = controller.status();

        assertThat(resp.getStatusCode().value()).isEqualTo(504);
        assertThat(resp.getBody().path("result").asText()).contains("timed out");
    }

    @Test
    @DisplayName("上游 500：502（协议/上游故障，不冒充成 400）")
    void upstreamServerErrorIs502() throws Exception {
        props.setControlUrl("http://127.0.0.1:" + upstream.getAddress().getPort() + "/boom");
        controller = new LinkSimControlController(client, mapper);

        ResponseEntity<JsonNode> resp = controller.status();

        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(resp.getBody().path("result").asText()).contains("upstream exploded");
    }

    @Test
    @DisplayName("PATCH 请求体不是 JSON 对象：本地 400，不转发")
    void nonObjectBodyIsRejectedLocally() throws Exception {
        ResponseEntity<JsonNode> resp = controller.patch(mapper.readTree("[1,2,3]"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(seen).isEmpty();
    }

    // ------------------------------------------------------------------
    // 路由 / 权限声明
    // ------------------------------------------------------------------

    @Test
    @DisplayName("路由与 RBAC：GET 要求 OBSERVER，PATCH 要求 OPERATOR，基础路径固定")
    void routeAndRoleAnnotations() throws Exception {
        assertThat(LinkSimControlController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/link-sim");

        Method get = LinkSimControlController.class.getMethod("status");
        assertThat(get.getAnnotation(GetMapping.class).value()).containsExactly("/control");
        assertThat(get.getAnnotation(RequireRole.class).value()).isEqualTo(Role.OBSERVER);

        Method patch = LinkSimControlController.class.getMethod("patch", JsonNode.class);
        assertThat(patch.getAnnotation(PatchMapping.class).value()).containsExactly("/control");
        assertThat(patch.getAnnotation(RequireRole.class).value()).isEqualTo(Role.OPERATOR);
    }

    // ------------------------------------------------------------------

    private void handleUpstream(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath()
                + " body=" + (body.isEmpty() ? "<empty>" : body));

        String response;
        int status = 200;
        if ("PATCH".equals(ex.getRequestMethod()) && body.contains("-5")) {
            status = 400;
            response = "{\"status\":\"error\",\"result\":\"'delayMs' out of range [0.0, 5000.0]: -5.0\"}";
        } else if ("GET".equals(ex.getRequestMethod()) && slowGet) {
            sleep(600);
            response = "{}";
        } else if ("GET".equals(ex.getRequestMethod())) {
            response = "{\"profile\":\"lte\",\"mode\":\"profile\","
                    + "\"uplink\":{\"delayMs\":45},\"downlink\":{\"delayMs\":45}}";
        } else {
            response = "{\"profile\":\"lte\",\"mode\":\"mixed\","
                    + "\"uplink\":{\"delayMs\":120,\"jitterMs\":0},\"downlink\":{\"delayMs\":45}}";
        }
        respond(ex, status, response);
    }

    private static void respond(HttpExchange ex, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
