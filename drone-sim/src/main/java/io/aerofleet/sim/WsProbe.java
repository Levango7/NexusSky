package io.aerofleet.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * 容量基线的 WebSocket 客户端探针：开 K 条连接，测「后端收到帧 → 客户端拿到」的时延。
 * <p>
 * 只测带 {@code timestamp} 的**逐帧**推送（{@code onMavlinkMessage} 路径）。1Hz 聚合的
 * {@code batch} 帧不含时间戳，只统计到达条数，不参与分位数——用 batch 的到达间隔冒充时延
 * 会把「1Hz 调度周期」算进结果里，得到一堆 1000ms 的假数字。
 * <p>
 * 连接数上限是真实产品约束：{@code aerofleet.ws.max-connections-per-ip} 默认 10，
 * 同机压 K>10 时第 11 条起会被 POLICY_VIOLATION 关掉。本探针把失败**计数并报告**，
 * 不吞掉——「连不上」本身就是要交付给客户的结论。
 */
final class WsProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WsProbe() {
    }

    /**
     * 跑一轮测量。
     *
     * @param url        ws:// 地址
     * @param clients    并发连接数
     * @param durationMs 测量时长（连接建立后开始计时）
     * @return 报告 JSON
     */
    static ObjectNode run(String url, int clients, long durationMs) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        List<Listener> listeners = new ArrayList<>(clients);
        List<WebSocket> open = new ArrayList<>(clients);
        int connected = 0;

        for (int i = 0; i < clients; i++) {
            Listener l = new Listener();
            try {
                WebSocket ws = http.newWebSocketBuilder()
                        .buildAsync(URI.create(url), l)
                        .get(10, TimeUnit.SECONDS);
                l.attach(ws);
                open.add(ws);
                connected++;
            } catch (Exception e) {
                l.fail(describe(e));
            }
            listeners.add(l);
        }

        long until = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        for (WebSocket ws : open) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 关连接失败不影响已有样本
            }
        }
        // 给回调一点时间把最后几帧记完（sendClose 返回时 onText 可能还在排队）
        Thread.sleep(500);

        return buildReport(clients, durationMs, connected, listeners);
    }

    private static String describe(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static ObjectNode buildReport(int clients, long durationMs, int connected,
                                          List<Listener> listeners) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("clients", clients);
        root.put("durationSec", durationMs / 1000.0);
        // handshakeOk 只代表 HTTP 101 完成。服务端的连接数限流是在握手**之后**
        // 用 close(POLICY_VIOLATION) 拒绝的，此时 buildAsync 早已返回成功——
        // 只报 handshakeOk 会把「20 条全连上」写进报告，而实际只有 10 条活着。
        // 因此额外给 activeClients（收到过至少一帧的连接数），以它为准。
        root.put("handshakeOk", connected);
        root.put("connectFailures", clients - connected);

        List<Long> all = new ArrayList<>();
        long totalMessages = 0;
        int activeClients = 0;
        int rejectedByServer = 0;
        int totalParseFailures = 0;
        ArrayNode perClient = root.putArray("perClient");
        for (int i = 0; i < listeners.size(); i++) {
            Listener l = listeners.get(i);
            ObjectNode c = perClient.addObject();
            c.put("index", i + 1);
            long[] samples = l.snapshot();
            if (l.messages > 0) {
                activeClients++;
            } else if (l.failure != null) {
                rejectedByServer++;
            }
            if (l.failure != null) {
                c.put("error", l.failure);
            }
            c.put("messages", l.messages);
            c.put("latencySamples", samples.length);
            c.put("parseFailures", l.parseFailures);
            totalMessages += l.messages;
            totalParseFailures += l.parseFailures;
            for (long v : samples) {
                all.add(v);
            }
        }
        root.put("activeClients", activeClients);
        root.put("rejectedByServer", rejectedByServer);
        root.put("totalMessages", totalMessages);
        root.put("parseFailures", totalParseFailures);
        // 时延样本数与帧数的比值。若显著小于 1，说明大量帧没有 timestamp 字段
        // 或解析失败，此时 p50/p99 只代表一小撮样本，不能当作整体时延。
        root.put("latencyCoverage",
                totalMessages == 0 ? 0.0 : round((double) all.size() / totalMessages));

        if (!all.isEmpty()) {
            long[] arr = new long[all.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = all.get(i);
            }
            Arrays.sort(arr);
            ObjectNode lat = root.putObject("latencyMs");
            lat.put("count", arr.length);
            lat.put("p50", percentile(arr, 50));
            lat.put("p95", percentile(arr, 95));
            lat.put("p99", percentile(arr, 99));
            lat.put("max", arr[arr.length - 1]);
            lat.put("mean", round(Arrays.stream(arr).average().orElse(0)));
        } else {
            root.putNull("latencyMs");
        }
        return root;
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 单条连接的接收器：累积分片、解析 JSON、记时延。 */
    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();
        private final List<Long> latencies = new ArrayList<>();
        private int messages;
        /**
         * 解析失败帧数。必须计数而不是静默吞掉：若全部帧都解析失败，
         * {@link #latencies} 会是空的，报告里 latencyMs 直接变 null——
         * 「没有时延数据」和「时延为零」在报告上看起来一样，无法区分。
         */
        private int parseFailures;
        private String failure;
        private WebSocket ws;

        void attach(WebSocket ws) {
            this.ws = ws;
            ws.request(1);
        }

        void fail(String reason) {
            this.failure = reason;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                handle(buf.toString());
                buf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (failure == null && statusCode != WebSocket.NORMAL_CLOSURE) {
                failure = "closed with status " + statusCode
                        + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")");
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (failure == null) {
                failure = error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + error.getMessage());
            }
        }

        private void handle(String json) {
            messages++;
            long now = System.currentTimeMillis();
            try {
                var node = MAPPER.readTree(json);
                if (node.has("timestamp")) {
                    long ts = node.get("timestamp").asLong();
                    if (ts > 0 && ts <= now) {
                        latencies.add(now - ts);
                    }
                }
            } catch (Exception e) {
                // 单帧解析失败不中断测量（帧格式问题应由契约测试发现，不是压测的职责），
                // 但必须计数：否则全量解析失败会伪装成「无时延样本」。
                parseFailures++;
            }
        }

        long[] snapshot() {
            long[] arr = new long[latencies.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = latencies.get(i);
            }
            return arr;
        }
    }
}
