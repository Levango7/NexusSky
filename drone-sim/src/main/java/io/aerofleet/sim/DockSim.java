package io.aerofleet.sim;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 机巢模拟器（F2）：模拟无人值守机场的物模型行为，供 cloud-backend 的
 * {@code SimLoopbackGateway}（HTTP 回环）联调与 e2e 断言。
 *
 * <p>端点（与 DJI Cloud API services 通道同形的 JSON 形状）：
 * <ul>
 *   <li>POST /services  {tid,bid,timestamp,gateway,method,data}
 *       → {tid,bid,timestamp,gateway,method,data:{result,output}}（DJI 官方字段表同形，
 *       tid/bid 为 UUID 文本、设备消息在 data.output）</li>
 *   <li>GET  /osd       → 当前 OSD（state/temperature/batteryPct/droneSysid）</li>
 *   <li>GET  /health    → {ok:true}</li>
 * </ul>
 * OSD 推送：启动时给 --osd-url 则以 --osd-period-ms 周期 POST 到云端 /api/v1/docks/osd
 * （DJI 语义是机巢主动上报；HTTP 回环是 sim 传输的等价物——MQTT 传输的等价物是
 * {@link DockSimMqttPeer}，它按官方 topic 与属性名上报）。
 *
 * <p>状态机语义与 MQTT 对端共用 {@link DockSimCore}——两条传输的语义必须一致。
 *
 * <p>时序（与云端状态机 spec R2 对齐）：开门 3s、关门 3s、换电 5s、重启 8s；
 * 温度默认 30-45℃ 随机游走，--temp-fixed 可固定（造 FAULT 用）。
 */
public final class DockSim implements AutoCloseable {

    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    // DJI 公共字段表：tid/bid 是 UUID 文本（不是数字）
    private static final Pattern TID = Pattern.compile("\"tid\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BID = Pattern.compile("\"bid\"\\s*:\\s*\"([^\"]+)\"");

    private final String sn;
    private final String name;
    private final int httpPort;
    private final String osdUrl;
    private final long osdPeriodMs;
    private final DockSimCore core;
    /** 是否暴露 HTTP /services 回环端点。MQTT 模式下**关闭**：走错传输要立刻失败，不能悄悄成功。 */
    private final boolean httpServicesEnabled;

    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "dock-sim-timer");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private HttpServer server;

    public DockSim(String sn, String name, int httpPort, String osdUrl, long osdPeriodMs,
                   int droneSysid, Double tempFixedC) {
        this(sn, name, httpPort, osdUrl, osdPeriodMs, droneSysid, tempFixedC, true);
    }

    public DockSim(String sn, String name, int httpPort, String osdUrl, long osdPeriodMs,
                   int droneSysid, Double tempFixedC, boolean httpServicesEnabled) {
        this.sn = sn;
        this.name = name;
        this.httpPort = httpPort;
        this.osdUrl = osdUrl;
        this.osdPeriodMs = osdPeriodMs;
        this.httpServicesEnabled = httpServicesEnabled;
        this.core = new DockSimCore(droneSysid, tempFixedC);
        this.core.bindSn(sn);
    }

    /** 状态机核心（MQTT 对端复用同一份语义时由此取；测试用）。 */
    public DockSimCore core() {
        return core;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", httpPort), 0);
        if (httpServicesEnabled) {
            server.createContext("/services", this::handleServices);
        }
        server.createContext("/osd", this::handleOsd);
        server.createContext("/health", ex -> respond(ex, 200, "{\"ok\":true,\"sn\":\"" + sn + "\"}"));
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "dock-sim-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        SimLog.info("dock-sim listening on 127.0.0.1:" + httpPort
                + " (sn=" + sn + ", osd push " + (osdUrl == null ? "disabled" : "-> " + osdUrl) + ")");
        timer.scheduleAtFixedRate(this::tick, osdPeriodMs, osdPeriodMs, TimeUnit.MILLISECONDS);
    }

    /** 周期：温度游走 + 充电推进 + OSD 推送。 */
    private void tick() {
        core.tick();
        if ("FAULT".equals(core.state())) {
            return; // 故障态不推 OSD（真实机巢断电重启语义：心跳停 → 云端置 OFFLINE 前仍可 reboot）
        }
        if (osdUrl != null) {
            String body = osdJson();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(osdUrl))
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                SimLog.warn("dock-sim osd push failed: " + e.getMessage());
            }
        }
    }

    public String osdJson() {
        return core.osdJson();
    }

    private void handleOsd(HttpExchange ex) throws IOException {
        respond(ex, 200, osdJson());
    }

    private void handleServices(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"data\":{\"result\":1,\"output\":{\"message\":\"method not allowed\"}}}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = METHOD.matcher(body);
        if (!m.find()) {
            respond(ex, 400, "{\"data\":{\"result\":1,\"output\":{\"message\":\"missing method\"}}}");
            return;
        }
        String tid = textOf(TID, body, "");
        String bid = textOf(BID, body, "");
        String method = m.group(1);
        String result = applyMethod(method);
        // DJI services_reply 形状：{tid,bid,timestamp,gateway,method,data:{result,output}}
        String reply = "{\"tid\":\"" + tid + "\",\"bid\":\"" + bid + "\","
                + "\"timestamp\":" + System.currentTimeMillis() + ",\"gateway\":\"" + sn + "\","
                + "\"method\":\"" + method + "\","
                + "\"data\":" + result + "}";
        respond(ex, 200, reply);
    }

    /**
     * 执行命令，返回 DJI 形状的 data 片段。
     *
     * <p>合法返回 {@code {"result":0,"output":{}}}，非法返回
     * {@code {"result":1,"output":{"message":"state=X"}}}，未知 method 返回
     * {@code {"result":2,"output":{"message":"unknown method X"}}}。
     */
    String applyMethod(String method) {
        DockSimCore.Result res = core.apply(method);
        if (res.message() == null) {
            return "{\"result\":" + res.result() + ",\"output\":{}}";
        }
        return "{\"result\":" + res.result() + ",\"output\":{\"message\":\""
                + res.message().replace("\"", "'") + "\"}}";
    }

    private static String textOf(Pattern p, String s, String dflt) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : dflt;
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 实际监听端口（httpPort=0 时为系统分配的临时端口；测试用）。 */
    public int boundPort() {
        return server == null ? httpPort : server.getAddress().getPort();
    }

    /** 测试与故障注入入口。 */
    public void forceState(String s) {
        core.forceState(s);
    }

    public String state() {
        return core.state();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        timer.shutdownNow();
        core.close();
    }
}
