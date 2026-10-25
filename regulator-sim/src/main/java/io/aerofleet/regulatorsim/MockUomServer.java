package io.aerofleet.regulatorsim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
 * 提供六个 API 端点模拟 UOM 平台交互：
 * <ul>
 *   <li>{@code GET /api/verify?serialNo=...&certNo=...} — 实名验证模拟</li>
 *   <li>{@code POST /api/activate} — 激活上报模拟（<b>状态机：幂等 + 实名前置校验</b>）</li>
 *   <li>{@code POST /api/cancel} — 注销模拟（幂等）</li>
 *   <li>{@code POST /api/telemetry} — 遥测上报模拟（<b>需有效激活</b>）</li>
 *   <li>{@code GET /api/records?type=...} — 上报记录查询</li>
 *   <li>{@code POST /api/reset} — 清空激活状态（e2e 用例间隔离）</li>
 * </ul>
 * 支持响应延迟模拟（{@code Thread.sleep(delayMs)}）和随机错误模拟（{@code errorRate} 概率返回 HTTP 500）。
 * <p>
 * <b>C1 高保真模拟（2026-10-12）</b>：activate/cancel/telemetry 不再是"无条件
 * success"，而是经 {@link ActivationStateMachine} 走真实 UOM 的语义——
 * 未实名不可激活、未激活不可上报、注销后拒绝、重试返回同一 ID。cloud-backend
 * 的上报链路在 PoC 环境即可按这些规则验收。
 */
public final class MockUomServer {

    private static final Logger log = LoggerFactory.getLogger(MockUomServer.class);

    private final HttpServer server;
    private final PresetDataStore presetData;
    private final RecordStore records;

    /**
     * 收到的请求总数（按方法+路径计数）。
     * <p>
     * 为什么需要它：e2e 要验"4xx 不再重试"——旧行为会把同一个请求打 4 遍
     * （1 次 + 3 次退避重试）。靠耗时推断（7s vs 立即）是个弱信号，
     * 有精确计数才能断言"只来了 1 次"。{@code POST /api/reset} 一并清零。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            requestCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final int delayMs;
    private final double errorRate;
    /** 故障注入返回的状态码（默认 500；设成 4xx 即可验"语义终态不重试"）。 */
    private final int errorStatus;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 激活 ID 序列号。 */
    private final AtomicInteger activationSeq = new AtomicInteger(0);
    /** 注销 ID 序列号。 */
    private final AtomicInteger cancellationSeq = new AtomicInteger(0);

    /**
     * 激活状态机（serialNo 维度）：C1 高保真模拟的核心——activate 幂等、
     * telemetry 需有效激活、cancel 幂等、注销后拒绝（详见 ActivationStateMachine）。
     */
    private final ActivationStateMachine activations = new ActivationStateMachine();

    /** ID 生成器：ACT-/CAN-<timestamp>-<seq>（与原格式一致，e2e 不断言格式）。 */
    private final ActivationStateMachine.IdGenerator idGenerator = new ActivationStateMachine.IdGenerator() {
        @Override
        public String nextActivation(String serialNo, long nowMs) {
            return "ACT-" + nowMs + "-" + activationSeq.incrementAndGet();
        }

        @Override
        public String nextCancellation(String serialNo, long nowMs) {
            return "CAN-" + nowMs + "-" + cancellationSeq.incrementAndGet();
        }
    };

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
        this.errorStatus = config.errorStatus;
        this.server = HttpServer.create(new InetSocketAddress(config.port), 0);

        // 注册六个 API 端点
        register("/api/verify", this::handleVerify);
        register("/api/activate", this::handleActivate);
        register("/api/cancel", this::handleCancel);
        register("/api/telemetry", this::handleTelemetry);
        register("/api/records", this::handleRecords);
        // 测试隔离：清空激活状态（e2e 用例之间互受影响）
        register("/api/reset", this::handleReset);
        // 只读观测：每个端点收到了多少请求（e2e 验重试次数用）
        register("/api/stats", this::handleStats);

        // 使用默认执行器（每个请求一个线程）
        server.setExecutor(null);
    }

    /**
     * 注册端点，并给每个收到的请求留一行日志（方法 + URI）。
     * <p>
     * mock 平台必须能自证"请求到没到"：com.sun.net.httpserver 在 handler 抛异常时
     * 是静默关闭连接、不回任何字节，客户端侧只能看到"连接被关"，分不清是请求没送达
     * 还是处理中炸了。有这行日志，客户端报无响应时先看 sim 收没收到。
     */
    private void register(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            countRequest(exchange.getRequestMethod(), path);
            log.info("[req] {} {}", exchange.getRequestMethod(), exchange.getRequestURI());
            handler.handle(exchange);
        });
    }

    private void countRequest(String method, String path) {
        String key = method + " " + path;
        requestCounts.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
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

    /**
     * 实际监听端口。
     * <p>
     * 传 {@code --port 0} 时 {@link com.sun.net.httpserver.HttpServer} 由内核分配 ephemeral
     * 端口，构造那一刻就已绑定，所以这个值在 {@code start()} 前后都可用。没有它，调用方
     * 只能猜自己请求要打到哪个端口——测试因此必须写死端口。
     */
    public int getPort() {
        return server.getAddress().getPort();
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
     * <p>
     * <b>状态机语义（2026-10-12 C1 高保真模拟）</b>：
     * <ul>
     *   <li>serialNo 未实名注册 → 403 {success:false, reason:"未实名注册"}</li>
     *   <li>实名状态非 VERIFIED（如 UNVERIFIED/REVOKED）→ 403 {success:false, reason}</li>
     *   <li>已激活（未注销）→ 200 {success:true, activationId:<原ID>, idempotent:true}
     *       （客户端重试/重复上报不新发 ID）</li>
     *   <li>已注销 → 403 {success:false, reason:"已注销，不可重新激活"}</li>
     * </ul>
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
        PresetDataStore.PresetEntry entry = presetData.find(serialNo);
        ActivationStateMachine.VerdictOutcome outcome = activations.activate(
                serialNo, entry != null ? entry.status : null, timestamp, idGenerator);

        if (outcome.verdict() != ActivationStateMachine.Verdict.GRANTED
                && outcome.verdict() != ActivationStateMachine.Verdict.IDEMPOTENT) {
            // 拒绝：真实 UOM 的错误语义（实名/注销前置校验）——不再无条件 success
            Map<String, Object> rejected = new HashMap<>();
            rejected.put("success", false);
            rejected.put("reason", outcome.reason());
            rejected.put("verdict", outcome.verdict().name());
            sendJson(exchange, 403, rejected);
            return;
        }

        // 存入 RecordStore（含幂等标记：重复激活也留痕，便于对账）
        Map<String, Object> recordData = new HashMap<>();
        recordData.put("serialNo", serialNo);
        recordData.put("activationTime", requestBody.get("activationTime"));
        recordData.put("lat", requestBody.get("lat"));
        recordData.put("lon", requestBody.get("lon"));
        recordData.put("activationId", outcome.id());
        recordData.put("idempotent", outcome.verdict() == ActivationStateMachine.Verdict.IDEMPOTENT);

        records.add("activation", new RecordStore.Record(
                "activation", serialNo, timestamp, recordData));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("activationId", outcome.id());
        if (outcome.verdict() == ActivationStateMachine.Verdict.IDEMPOTENT) {
            response.put("idempotent", true);
        }
        sendJson(exchange, 200, response);
    }

    /**
     * POST /api/cancel
     * <p>
     * 请求体：{serialNo, reason}
     * 响应：{success: true, cancellationId: "CAN-<timestamp>-<seq>"}
     * <p>
     * <b>状态机语义</b>：已注销 → 幂等返回原 cancellationId（不新发）；
     * 未激活也可注销（登记即可，真实 UOM 允许空注销）。
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
        ActivationStateMachine.VerdictOutcome outcome = activations.cancel(
                serialNo, timestamp, idGenerator);

        // 存入 RecordStore
        Map<String, Object> recordData = new HashMap<>();
        recordData.put("serialNo", serialNo);
        recordData.put("reason", requestBody.get("reason"));
        recordData.put("cancellationId", outcome.id());

        records.add("cancellation", new RecordStore.Record(
                "cancellation", serialNo, timestamp, recordData));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("cancellationId", outcome.id());
        if (outcome.verdict() == ActivationStateMachine.Verdict.IDEMPOTENT) {
            response.put("idempotent", true);
        }
        sendJson(exchange, 200, response);
    }

    /**
     * POST /api/telemetry
     * <p>
     * 请求体：{sysid, serialNo, timestamp, lat, lon, ...}
     * 响应：{success: true}
     * <p>
     * <b>状态机语义</b>：仅<b>已激活</b>的 serialNo 接收（真实 UOM 的
     * 激活-上报关联）；未激活 → 403 NOT_ACTIVATED；已注销 → 403 CANCELLED。
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

        ActivationStateMachine.TelemOutcome outcome =
                activations.recordTelemetry(serialNo, timestamp);
        if (outcome.verdict() != ActivationStateMachine.TelemVerdict.ACCEPTED) {
            Map<String, Object> rejected = new HashMap<>();
            rejected.put("success", false);
            rejected.put("reason", outcome.reason());
            rejected.put("verdict", outcome.verdict().name());
            sendJson(exchange, 403, rejected);
            return;
        }

        // 存入 RecordStore
        records.add("telemetry", new RecordStore.Record(
                "telemetry", serialNo, timestamp, requestBody));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);

        sendJson(exchange, 200, response);
    }

    /**
     * GET /api/stats —— 各端点的请求计数（e2e 用），形如
     * {@code {"requests": {"POST /api/verify": 1, ...}}}。
     * <p>
     * 只读、不改变任何状态；reset 时一并清零。
     */
    private void handleStats(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> counts = new java.util.LinkedHashMap<>();
        requestCounts.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> counts.put(e.getKey(), e.getValue().get()));
        body.put("requests", counts);
        sendJson(exchange, 200, body);
    }

    /**
     * POST /api/reset —— 清空激活状态（e2e 用例间隔离用，不影响预置数据与记录）。
     */
    private void handleReset(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        activations.reset();
        requestCounts.clear();
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
            sendJson(exchange, errorStatus, errorResponse);
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