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
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 机巢模拟器（F2）：模拟无人值守机场的物模型行为，供 cloud-backend 的
 * {@code SimLoopbackGateway}（HTTP 回环）联调与 e2e 断言。
 *
 * <p>端点（与 DJI Cloud API services 通道同形的 JSON 形状）：
 * <ul>
 *   <li>POST /services  {tid,bid,method,data} → {tid,bid,timestamp,data:{result,message}}</li>
 *   <li>GET  /osd       → 当前 OSD（state/temperature/batteryPct/droneSysid）</li>
 *   <li>GET  /health    → {ok:true}</li>
 * </ul>
 * OSD 推送：启动时给 --osd-url 则以 --osd-period-ms 周期 POST 到云端 /api/v1/docks/osd
 * （DJI 语义是机巢主动上报；HTTP 回环是 sim 传输的等价物）。
 *
 * <p>时序（与云端状态机 spec R2 对齐）：开门 3s、关门 3s、换电 5s、重启 8s；
 * 温度默认 30-45℃ 随机游走，--temp-fixed 可固定（造 FAULT 用）。
 */
public final class DockSim implements AutoCloseable {

    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TID = Pattern.compile("\"tid\"\\s*:\\s*(\\d+)");
    private static final Pattern BID = Pattern.compile("\"bid\"\\s*:\\s*(\\d+)");

    private final String sn;
    private final String name;
    private final int httpPort;
    private final String osdUrl;
    private final long osdPeriodMs;
    private final int droneSysid;
    private final Double tempFixedC;

    private volatile String state = "IDLE";
    private volatile double temperature = 35.0;
    private volatile int batteryPct = 80;
    private volatile String faultReason = null;

    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "dock-sim-timer");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicLong osdSeq = new AtomicLong();
    private HttpServer server;

    public DockSim(String sn, String name, int httpPort, String osdUrl, long osdPeriodMs,
                   int droneSysid, Double tempFixedC) {
        this.sn = sn;
        this.name = name;
        this.httpPort = httpPort;
        this.osdUrl = osdUrl;
        this.osdPeriodMs = osdPeriodMs;
        this.droneSysid = droneSysid;
        this.tempFixedC = tempFixedC;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", httpPort), 0);
        server.createContext("/services", this::handleServices);
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
        if (tempFixedC != null) {
            temperature = tempFixedC;
        } else {
            double delta = (Math.random() - 0.5) * 1.0;
            temperature = Math.max(28.0, Math.min(50.0, temperature + delta));
        }
        if ("CHARGING".equals(state) && batteryPct < 100) {
            batteryPct = Math.min(100, batteryPct + 1);
        }
        if ("FAULT".equals(state)) {
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
        return "{\"sn\":\"" + sn + "\",\"state\":\"" + state + "\","
                + "\"temperature\":" + String.format(java.util.Locale.ROOT, "%.1f", temperature) + ","
                + "\"batteryPct\":" + batteryPct + ","
                + "\"droneSysid\":" + droneSysid + ","
                + "\"seq\":" + osdSeq.incrementAndGet() + "}";
    }

    private void handleOsd(HttpExchange ex) throws IOException {
        respond(ex, 200, osdJson());
    }

    private void handleServices(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"data\":{\"result\":1,\"message\":\"method not allowed\"}}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = METHOD.matcher(body);
        if (!m.find()) {
            respond(ex, 400, "{\"data\":{\"result\":1,\"message\":\"missing method\"}}");
            return;
        }
        long tid = longOf(TID, body, 0);
        long bid = longOf(BID, body, 0);
        String method = m.group(1);
        String result = applyMethod(method);
        String reply = "{\"tid\":" + tid + ",\"bid\":" + bid + ","
                + "\"timestamp\":" + System.currentTimeMillis() + ",\"data\":" + result + "}";
        respond(ex, 200, reply);
    }

    /** 执行命令：合法返回 {"result":0}，非法返回 {"result":1,"message":"state=X"}。 */
    String applyMethod(String method) {
        String cur = state;
        switch (method) {
            case "door_open" -> {
                if (!"IDLE".equals(cur) && !"CHARGING".equals(cur)) {
                    return rejected(cur);
                }
                state = "OPENING";
                timer.schedule(() -> state = "OPEN", 3000, TimeUnit.MILLISECONDS);
                return "{\"result\":0}";
            }
            case "door_close" -> {
                if (!"OPEN".equals(cur)) {
                    return rejected(cur);
                }
                state = "CLOSING";
                timer.schedule(() -> state = batteryPct < 100 ? "CHARGING" : "IDLE",
                        3000, TimeUnit.MILLISECONDS);
                return "{\"result\":0}";
            }
            case "battery_swap" -> {
                if (!"IDLE".equals(cur) && !"CHARGING".equals(cur)) {
                    return rejected(cur);
                }
                state = "EXCHANGING";
                timer.schedule(() -> {
                    batteryPct = 20;
                    state = "CHARGING";
                }, 5000, TimeUnit.MILLISECONDS);
                return "{\"result\":0}";
            }
            case "reboot" -> {
                state = "FAULT";
                faultReason = "manual reboot";
                timer.schedule(() -> {
                    state = "IDLE";
                    faultReason = null;
                }, 8000, TimeUnit.MILLISECONDS);
                return "{\"result\":0}";
            }
            default -> {
                return "{\"result\":2,\"message\":\"unknown method " + method + "\"}";
            }
        }
    }

    private String rejected(String cur) {
        return "{\"result\":1,\"message\":\"state=" + cur + "\"}";
    }

    private static long longOf(Pattern p, String s, long dflt) {
        Matcher m = p.matcher(s);
        return m.find() ? Long.parseLong(m.group(1)) : dflt;
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 测试与故障注入入口。 */
    public void forceState(String s) {
        this.state = s;
    }

    public String state() {
        return state;
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        timer.shutdownNow();
    }
}
