package io.aerofleet.regulatorsim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * regulator-sim 故障注入与请求计数的回归（2026-10-12）。
 *
 * <p>为什么加：e2e 要验"4xx 是语义终态不重试"（backend 的 {@code ReportRetryPolicy}），
 * 而 sim 原先只能注入 500——500 永远可重试，**根本造不出终态场景**，那条路径在 e2e 里
 * 从未被触发过。现在 {@code --error-status} 可注入任意状态码，且 {@code GET /api/stats}
 * 给出精确请求计数（否则只能靠耗时推断重试次数，那是弱信号）。
 */
@DisplayName("regulator-sim：故障注入状态码可配 + 请求计数")
class FaultInjectionTest {

    @Test
    @DisplayName("--error-status 默认 500（不破坏既有故障注入语义）") void defaultErrorStatus() {
        assertThat(SimConfig.parse(new String[]{"--port=18181"}).errorStatus).isEqualTo(500);
    }

    @Test
    @DisplayName("--error-status 可注入 4xx（验终态不重试的前提）") void configurableErrorStatus() {
        assertThat(SimConfig.parse(new String[]{"--port=18181", "--error-status", "403"}).errorStatus)
                .isEqualTo(403);
        assertThat(SimConfig.parse(new String[]{"--port=18181", "--error-status", "429"}).errorStatus)
                .isEqualTo(429);
    }

    @Test
    @DisplayName("非法 --error-status 回落默认 500，不炸进程") void invalidErrorStatusFallsBack() {
        assertThat(SimConfig.parse(new String[]{"--port=18181", "--error-status", "abc"}).errorStatus)
                .isEqualTo(500);
        assertThat(SimConfig.parse(new String[]{"--port=18181", "--error-status", "99"}).errorStatus)
                .isEqualTo(500);
        assertThat(SimConfig.parse(new String[]{"--port=18181", "--error-status", "600"}).errorStatus)
                .isEqualTo(500);
    }

    @Test
    @DisplayName("请求计数：按 方法+路径 累计，reset 清零") void requestCountsAreTracked() throws Exception {
        SimConfig config = SimConfig.parse(new String[]{"--port=18182"});
        MockUomServer server = new MockUomServer(config);
        try {
            server.start();
            int port = config.port;

            // 两个不同端点各打一次 + 其中一个再打一次
            get(port, "/api/verify?serialNo=TEST-001&certNo=CERT-001");
            get(port, "/api/verify?serialNo=TEST-001&certNo=CERT-001");
            get(port, "/api/records?type=telemetry");

            String stats = get(port, "/api/stats");
            assertThat(stats).contains("GET /api/verify");
            assertThat(countOf(stats, "GET /api/verify")).isEqualTo(2);
            assertThat(countOf(stats, "GET /api/records")).isEqualTo(1);

            // reset 后计数归零（e2e 用例间隔离）
            post(port, "/api/reset");
            String after = get(port, "/api/stats");
            assertThat(after).doesNotContain("GET /api/verify");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("参数两种写法等价：--port N 与 --port=N（此前只认空格式）") void bothArgForms() {
        assertThat(SimConfig.parse(new String[]{"--port", "18183"}).port).isEqualTo(18183);
        assertThat(SimConfig.parse(new String[]{"--port=18183"}).port).isEqualTo(18183);
        assertThat(SimConfig.parse(new String[]{"--error-status", "409"}).errorStatus).isEqualTo(409);
        assertThat(SimConfig.parse(new String[]{"--error-status=409"}).errorStatus).isEqualTo(409);
        assertThat(SimConfig.parse(new String[]{"--delay-ms=50"}).delayMs).isEqualTo(50);
    }

    @Test
    @DisplayName("未知参数只警告、不 exit（否则调用方进程被一起杀掉）") void unknownArgDoesNotExit() {
        assertThat(SimConfig.parse(new String[]{"--port=18184", "--unknown-flag", "x"}).port)
                .as("未知参数被忽略，其余参数照常生效")
                .isEqualTo(18184);
    }

    // ---- HTTP 辅助 ----

    private static String get(int port, String path) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URI("http://127.0.0.1:" + port + path).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(),
                java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static void post(int port, String path) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URI("http://127.0.0.1:" + port + path).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.getResponseCode();
    }

    /** stats 形如 {"requests":{"GET /api/verify":2,...}}，取某个 key 的计数值。 */
    private static int countOf(String statsJson, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote("\"" + key + "\"") + "\\s*:\\s*(\\d+)")
                .matcher(statsJson);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
