package io.aerofleet.regulatorsim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 模拟 UOM 监管平台 HTTP API 服务器。
 * <p>
 * 使用 JDK 内置 {@link com.sun.net.httpserver.HttpServer}，无外部 HTTP 框架依赖。
 * 提供五个 API 端点模拟 UOM 平台交互：
 * <ul>
 *   <li>{@code GET /api/verify?serialNo=...&certNo=...} — 实名验证模拟</li>
 *   <li>{@code POST /api/activate} — 激活上报模拟</li>
 *   <li>{@code POST /api/cancel} — 注销模拟</li>
 *   <li>{@code POST /api/telemetry} — 遥测上报模拟</li>
 *   <li>{@code GET /api/records?type=...} — 上报记录查询</li>
 * </ul>
 * 支持响应延迟模拟（{@code Thread.sleep(delayMs)}）和随机错误模拟（{@code errorRate} 概率返回 HTTP 500）。
 */
public final class MockUomServer {

    private static final Logger log = LoggerFactory.getLogger(MockUomServer.class);

    private final HttpServer server;
    private final PresetDataStore presetData;
    private final RecordStore records;
    private final int delayMs;
    private final double errorRate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 激活 ID 序列号。 */
    private final AtomicInteger activationSeq = new AtomicInteger(0);
    /** 注销 ID 序列号。 */
    private final AtomicInteger cancellationSeq = new AtomicInteger(0);

    /**
     * 创建 MockUomServer 实例。
     *
     * @param config 启动配置（端口、延迟、错误率、预置数据文件）
     * @throws IOException HttpServer 创建失败
     */
    public MockUomServer(SimConfig config) throws IOException {
        this.presetData = new PresetDataStore();
        if (config.presetFile != null) {
            try {
                presetData.loadFromFile(config.presetFile);
                log.info("[regulator-sim] loaded preset data from: {} ({} entries)",
                        config.presetFile, presetData.size());
            } catch (IOException e) {
                log.warn("[regulator-sim] failed to load preset file: {}, using builtin data", config.presetFile, e);
                presetData.loadDefault();
            }
        }

        this.records = new RecordStore();
        this.delayMs = config.delayMs;
        this.errorRate = config.errorRate;
        this.server = HttpServer.create(new InetSocketAddress(config.port), 0);

        // 注册五个 API 端点
        server.createContext("/api/verify", this::handleVerify);
        server.createContext("/api/activate", this::handleActivate);
        server.createContext("/api/cancel", this::handleCancel);
        server.createContext("/api/telemetry", this::handleTelemetry);
        server.createContext("/api/records", this::handleRecords);

        // 使用默认执行器（每个请求一个线程）
        server.setExecutor(null);
    }

    /**
     * 启动 HTTP 服务器。
     */
    public void start() {
        log.info("[regulator-sim] MockUomServer starting — port={} delay-ms={} error-rate={}",
                server.getAddress().getPort(), delayMs, errorRate);
        server.start();
        log.info("[regulator-sim] MockUomServer started on port {}", server.getAddress().getPort());
    }

    /**
     * 停止 HTTP 服务器。
     */
    public void stop() {
        log.info("[regulator-sim] MockUomServer stopping...");
        server.stop(0);
        log.info("[regulator-sim] MockUomServer stopped");
    }

    // ==================== Handler 方法 ====================

    /**
     * GET /api/verify?serialNo=...&certNo=...
     * <p>
     * 查询 PresetDataStore，返回实名验证结果。
     * 响应：{status, owner, registerDate}
     */
    private void handleVerify(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        if (simulateDelayAndError(exchange)) {
            return;
        }

        URI uri = exchange.getRequestURI();
        Map<String, String> params = parseQuery(uri.getQuery());
        String serialNo = params.get("serialNo");
        String certNo = params.get("certNo");

        Map<String, Object> response = new HashMap<>();

        if (serialNo == null) {
            response.put("status", "ERROR");
            response.put("owner", null);
            response.put("registerDate", null);
            sendJson(exchange, 400, response);
            return;
        }

        PresetDataStore.PresetEntry entry = presetData.find(serialNo);

        if (entry == null) {
            // NOT_FOUND 场景
            response.put("status", "NOT_FOUND");
            response.put("owner", null);
            response.put("registerDate", null);
        } else {
            response.put("status", entry.status);
            response.put("owner", entry.owner);
            response.put("registerDate", entry.registerDate);
        }

        sendJson(exchange, 200, response);
    }

    /**
     * POST /api/activate
     * <p>
     * 请求体：{serialNo, activationTime, lat, lon}
     * 响应：{success: true, activationId: "ACT-<timestamp>-<seq>"}
     */
    @SuppressWarnings("unchecked")
    private void handleActivate(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        if (simulateDelayAndError(exchange)) {
            return;
        }

        Map<String, Object> requestBody = readJsonBody(exchange);
        String serialNo = (String) requestBody.get("serialNo");

        long timestamp = System.currentTimeMillis();
        int seq = activationSeq.incrementAndGet();
        String activationId = "ACT-" + timestamp + "-" + seq;

        // 存入 RecordStore
        Map<String, Object> recordData = new HashMap<>();
        recordData.put("serialNo", serialNo);
        recordData.put("activationTime", requestBody.get("activationTime"));
        recordData.put("lat", requestBody.get("lat"));
        recordData.put("lon", requestBody.get("lon"));
        recordData.put("activationId", activationId);

        records.add("activation", new RecordStore.Record(
                "activation", serialNo, timestamp, recordData));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("activationId", activationId);

        sendJson(exchange, 200, response);
    }

    /**
     * POST /api/cancel
     * <p>
     * 请求体：{serialNo, reason}
     * 响应：{success: true, cancellationId: "CAN-<timestamp>-<seq>"}
     */
    @SuppressWarnings("unchecked")
    private void handleCancel(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        if (simulateDelayAndError(exchange)) {
            return;
        }

        Map<String, Object> requestBody = readJsonBody(exchange);
        String serialNo = (String) requestBody.get("serialNo");

        long timestamp = System.currentTimeMillis();
        int seq = cancellationSeq.incrementAndGet();
        String cancellationId = "CAN-" + timestamp + "-" + seq;

        // 存入 RecordStore
        Map<String, Object> recordData = new HashMap<>();
        recordData.put("serialNo", serialNo);
        recordData.put("reason", requestBody.get("reason"));
        recordData.put("cancellationId", cancellationId);

        records.add("cancellation", new RecordStore.Record(
                "cancellation", serialNo, timestamp, recordData));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("cancellationId", cancellationId);

        sendJson(exchange, 200, response);
    }

    /**
     * POST /api/telemetry
     * <p>
     * 请求体：{sysid, serialNo, timestamp, lat, lon, ...}
     * 响应：{success: true}
     */
    @SuppressWarnings("unchecked")
    private void handleTelemetry(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        if (simulateDelayAndError(exchange)) {
            return;
        }

        Map<String, Object> requestBody = readJsonBody(exchange);
        String serialNo = (String) requestBody.get("serialNo");

        long timestamp = System.currentTimeMillis();

        // 存入 RecordStore
        records.add("telemetry", new RecordStore.Record(
                "telemetry", serialNo, timestamp, requestBody));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);

        sendJson(exchange, 200, response);
    }

    /**
     * GET /api/records?type=activation|cancellation|telemetry
     * <p>
     * 返回 RecordStore 中对应类型记录列表。
     */
    private void handleRecords(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }

        if (simulateDelayAndError(exchange)) {
            return;
        }

        URI uri = exchange.getRequestURI();
        Map<String, String> params = parseQuery(uri.getQuery());
        String type = params.get("type");

        if (type == null || type.trim().isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "missing 'type' parameter");
            sendJson(exchange, 400, errorResponse);
            return;
        }

        List<RecordStore.Record> result = records.query(type);

        // 将 Record 列表转为可序列化的 Map 列表
        List<Map<String, Object>> serializedList = new ArrayList<>();
        for (RecordStore.Record r : result) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", r.type);
            m.put("serialNo", r.serialNo);
            m.put("timestamp", r.timestamp);
            m.put("data", r.data);
            serializedList.add(m);
        }

        sendJson(exchange, 200, serializedList);
    }

    // ==================== 工具方法 ====================

    /**
     * 模拟响应延迟和随机错误。
     * <p>
     * 1. 如果 {@code delayMs > 0}，Thread.sleep(delayMs) 模拟网络延迟。
     * 2. 如果 {@code errorRate > 0}，以 errorRate 概率返回 HTTP 500。
     *
     * @param exchange HTTP 交换对象
     * @return true 表示已发送错误响应（调用方应直接返回），false 表示继续正常处理
     */
    private boolean simulateDelayAndError(HttpExchange exchange) throws IOException {
        // 模拟延迟
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 模拟随机错误
        if (errorRate > 0.0 && ThreadLocalRandom.current().nextDouble() < errorRate) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "simulated server error");
            errorResponse.put("success", false);
            sendJson(exchange, 500, errorResponse);
            return true;
        }

        return false;
    }

    /**
     * 解析 URL 查询参数。
     *
     * @param query 查询字符串（如 "serialNo=TEST-001&certNo=CERT-001"）
     * @return 参数名 → 参数值的映射
     */
    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.trim().isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String value = pair.substring(idx + 1);
                params.put(key, value);
            }
        }
        return params;
    }

    /**
     * 读取请求体并解析为 JSON Map。
     *
     * @param exchange HTTP 交换对象
     * @return 请求体 JSON 解析后的 Map（空请求体返回空 Map）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        try {
            byte[] bodyBytes = readAllBytes(is);
            if (bodyBytes.length == 0) {
                return new HashMap<>();
            }
            return objectMapper.readValue(bodyBytes, Map.class);
        } finally {
            is.close();
        }
    }

    /**
     * 从 InputStream 读取全部字节（Java 8 兼容，不使用 InputStream.readAllBytes()）。
     */
    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /**
     * 发送 JSON 响应。
     *
     * @param exchange    HTTP 交换对象
     * @param statusCode  HTTP 状态码
     * @param responseObj 响应对象（将被 ObjectMapper 序列化为 JSON）
     */
    private void sendJson(HttpExchange exchange, int statusCode, Object responseObj) throws IOException {
        byte[] responseBytes = objectMapper.writeValueAsBytes(responseObj);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        OutputStream os = exchange.getResponseBody();
        try {
            os.write(responseBytes);
        } finally {
            os.close();
        }
    }

    /**
     * 发送 405 Method Not Allowed 响应。
     */
    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "method not allowed");
        sendJson(exchange, 405, errorResponse);
    }
}