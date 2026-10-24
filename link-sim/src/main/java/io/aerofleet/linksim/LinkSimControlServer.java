package io.aerofleet.linksim;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * link-sim 运行时控制通道（P2 #20）：HTTP + JSON，监听 {@code 127.0.0.1}。
 *
 * <pre>
 * GET   /profile   当前画像/模式 + 上下行参数 + 累计统计
 * PATCH /profile   切换画像 / 恢复默认 / 更新自定义损伤参数
 * </pre>
 *
 * <p><b>鉴权口径</b>：本通道<b>故意不做应用层鉴权</b>，安全边界是"只绑回环地址"：
 * 它只服务本机操作者与同机 cloud-backend 的转发端点；后者对公网暴露
 * {@code /api/v1/link-sim/control} 并按 RBAC 要求 OPERATOR（见
 * {@code LinkSimControlController}）。因此不要把本端口绑定到 0.0.0.0。
 *
 * <p>请求体上限 64 KB；未知字段/类型错误/越界一律 400 并带具体原因，
 * 未知路径 404，方法不允许 405（带 Allow 头）。错误响应与成功响应同形：
 * {@code {"status":"error","result":"..."}}。
 */
final class LinkSimControlServer {

    private static final Logger log = LoggerFactory.getLogger(LinkSimControlServer.class);

    /** 请求体上限：控制请求只是一小组标量，64 KB 已非常宽松。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private static final String PATH = "/profile";

    private final HttpServer server;
    private final LinkSimControl control;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor;

    /**
     * @param port 监听端口；0 = 由系统分配（测试用，实际端口见 {@link #port()}）
     * @throws IOException 绑定失败（端口占用等）
     */
    LinkSimControlServer(int port, LinkSimControl control) throws IOException {
        this.control = control;
        this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        this.server.createContext("/", this::handle);
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "link-control-http");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(executor);
    }

    /** 实际绑定端口（port=0 时由系统分配）。 */
    int port() {
        return server.getAddress().getPort();
    }

    void start() {
        server.start();
    }

    /** 停止监听并关闭工作线程（测试收尾；生产进程随主循环退出）。 */
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!PATH.equals(ex.getRequestURI().getPath())) {
                respond(ex, 404, error("not found: " + ex.getRequestURI().getPath()));
                return;
            }
            switch (ex.getRequestMethod()) {
                case "GET" -> respond(ex, 200, control.view());
                case "PATCH" -> handlePatch(ex);
                default -> {
                    ex.getResponseHeaders().set("Allow", "GET, PATCH");
                    respond(ex, 405, error("method not allowed: " + ex.getRequestMethod()));
                }
            }
        } catch (LinkSimControl.BadRequestException e) {
            respond(ex, 400, error(e.getMessage()));
        } catch (Exception e) {
            log.warn("control request failed: {} {} -> {}", ex.getRequestMethod(),
                    ex.getRequestURI().getPath(), e.getMessage());
            respond(ex, 500, error("internal error: " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private void handlePatch(HttpExchange ex) throws IOException {
        long declared = parseContentLength(ex);
        if (declared > MAX_BODY_BYTES) {
            respond(ex, 413, error("request body too large (max " + MAX_BODY_BYTES + " bytes)"));
            return;
        }
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (raw.length > MAX_BODY_BYTES) {
            respond(ex, 413, error("request body too large (max " + MAX_BODY_BYTES + " bytes)"));
            return;
        }
        if (raw.length == 0) {
            throw new LinkSimControl.BadRequestException("request body must be a JSON object");
        }
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (Exception e) {
            throw new LinkSimControl.BadRequestException("malformed JSON: " + e.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw new LinkSimControl.BadRequestException("request body must be a JSON object");
        }
        Map<String, Object> body = mapper.convertValue(node, new TypeReference<>() {
        });
        respond(ex, 200, control.patch(body));
    }

    private static long parseContentLength(HttpExchange ex) {
        String raw = ex.getRequestHeaders().getFirst("Content-Length");
        if (raw == null) {
            return -1;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void respond(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            status = 500;
            bytes = "{\"status\":\"error\",\"result\":\"serialize failed\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("result", message == null ? "error" : message);
        return m;
    }
}
