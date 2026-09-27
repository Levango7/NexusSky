package io.aerofleet.cloud.regulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.regulator.model.ActivationRequest;
import io.aerofleet.cloud.regulator.model.ActivationResult;
import io.aerofleet.cloud.regulator.model.CancellationRequest;
import io.aerofleet.cloud.regulator.model.CancellationResult;
import io.aerofleet.cloud.regulator.model.TelemetryReport;
import io.aerofleet.cloud.regulator.model.TelemetryResult;
import io.aerofleet.cloud.regulator.model.VerifyRequest;
import io.aerofleet.cloud.regulator.model.VerifyResult;
import io.aerofleet.cloud.regulator.model.VerifyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * UOM 平台上报通道实现。
 * <p>
 * 通过 HTTPS 与民航局 UOM（Unmanned aircraft Operation Management）平台通信，
 * 实现 {@link RegulatorReportSink} 的四项操作契约。适用于正式运营部署环境。
 * <p>
 * <b>通信协议</b>：
 * <ul>
 *   <li>{@code GET  /api/verify?serialNo=...&certNo=...} → 实名验证</li>
 *   <li>{@code POST /api/activate} → 激活上报</li>
 *   <li>{@code POST /api/cancel} → 注销上报</li>
 *   <li>{@code POST /api/telemetry} → 遥测上报</li>
 * </ul>
 * <p>
 * <b>认证机制</b>：
 * <ul>
 *   <li>API Key — 通过 {@code X-API-Key} 请求头传递，用于身份标识</li>
 *   <li>HMAC-SHA256 签名 — 当 {@code signingKey} 配置存在时，对每个请求生成签名，
 *       通过 {@code X-Signature} 头传递，用于请求完整性校验。签名内容为
 *       {@code METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + BODY} 的 HMAC-SHA256
 *       Base64 编码值</li>
 * </ul>
 * <p>
 * <b>容错机制</b>：
 * <ul>
 *   <li>HTTP 超时按 {@link RegulatorConfig#getHttpTimeoutMs()} 配置</li>
 *   <li>失败重试最多 {@link RegulatorConfig#getMaxRetry()} 次，指数退避（1s/2s/4s）</li>
 *   <li>所有异常不向调用方抛出，通过返回结果的 {@code errorMessage} 字段传达</li>
 * </ul>
 * <p>
 * <b>隐私保护</b>：{@code verifyRealNameStatus} 返回前对所有者姓名脱敏（仅保留姓氏 + *）。
 * <p>
 * <b>注意</b>：真实 UOM 平台接口需凭资质申请，本实现为接口预留与协议适配，
 * 实际对接在运营部署阶段完成。
 * <p>
 * 激活条件：配置项 {@code aerofleet.regulator.sink-type=uom} 时生效。
 *
 * @see RegulatorReportSink
 * @see RegulatorConfig
 */
@Component
@ConditionalOnProperty(name = "aerofleet.regulator.sink-type", havingValue = "uom")
public class UomReportSink implements RegulatorReportSink {

    private static final Logger log = LoggerFactory.getLogger(UomReportSink.class);

    /** 指数退避基数（毫秒）：1s → 2s → 4s。 */
    private static final long RETRY_BASE_DELAY_MS = 1000L;

    /** HMAC-SHA256 算法名称。 */
    private static final String HMAC_SHA256 = "HmacSHA256";

    /** API Key 认证头名称。 */
    private static final String HEADER_API_KEY = "X-API-Key";

    /** 请求签名头名称。 */
    private static final String HEADER_SIGNATURE = "X-Signature";

    /** 请求时间戳头名称。 */
    private static final String HEADER_TIMESTAMP = "X-Timestamp";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final int maxRetry;
    private final String apiKey;
    private final String signingKey;

    /**
     * 构造 UomReportSink 实例。
     *
     * @param config       监管对接配置
     * @param objectMapper Jackson JSON 序列化器
     */
    public UomReportSink(RegulatorConfig config, ObjectMapper objectMapper) {
        this.baseUrl = config.getUomBaseUrl();
        this.maxRetry = config.getMaxRetry();
        this.objectMapper = objectMapper;
        this.apiKey = config.getApiKey();
        this.signingKey = config.getSigningKey();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getHttpTimeoutMs()))
                .build();
        log.info("UomReportSink 初始化完成，baseUrl={}, httpTimeoutMs={}, maxRetry={}, apiKey={}, signingEnabled={}",
                baseUrl, config.getHttpTimeoutMs(), maxRetry,
                apiKey != null ? "已配置" : "未配置",
                signingKey != null);
    }

    // ─── RegulatorReportSink 实现 ───

    @Override
    public VerifyResult verifyRealNameStatus(VerifyRequest request) {
        log.debug("verifyRealNameStatus: sysid={}, serialNo={}", request.sysid(), request.productSerialNo());

        String path = String.format("/api/verify?serialNo=%s&certNo=%s",
                request.productSerialNo(), request.realNameCertNo());
        String url = baseUrl + path;

        try {
            JsonNode response = executeWithRetry(url, "GET", path, null);
            if (response == null) {
                return new VerifyResult(VerifyStatus.ERROR, null, null,
                        "UOM 平台不可达或响应异常");
            }

            String statusStr = response.path("status").asText("");
            VerifyStatus status = parseVerifyStatus(statusStr);
            String ownerName = response.path("owner").asText(null);
            String registerDate = response.path("registerDate").asText(null);

            // 脱敏：仅保留姓氏 + *
            String maskedOwner = maskOwnerName(ownerName);

            log.info("verifyRealNameStatus 完成: sysid={}, status={}, owner={}",
                    request.sysid(), status, maskedOwner);
            return new VerifyResult(status, maskedOwner, registerDate, null);
        } catch (Exception e) {
            log.warn("verifyRealNameStatus 失败: sysid={}, error={}", request.sysid(), e.getMessage());
            return new VerifyResult(VerifyStatus.ERROR, null, null, e.getMessage());
        }
    }

    @Override
    public ActivationResult reportActivation(ActivationRequest request) {
        log.debug("reportActivation: sysid={}, serialNo={}", request.sysid(), request.productSerialNo());

        String path = "/api/activate";
        String url = baseUrl + path;

        Map<String, Object> body = new HashMap<>();
        body.put("serialNo", request.productSerialNo());
        body.put("activationTime", request.activationTime());
        body.put("lat", request.latitude());
        body.put("lon", request.longitude());

        try {
            JsonNode response = executeWithRetry(url, "POST", path, body);
            if (response == null) {
                return new ActivationResult(false, null,
                        "UOM 平台不可达或响应异常");
            }

            boolean success = response.path("success").asBoolean(false);
            String activationId = response.path("activationId").asText(null);

            log.info("reportActivation 完成: sysid={}, success={}, activationId={}",
                    request.sysid(), success, activationId);
            return new ActivationResult(success, activationId,
                    success ? null : "激活上报被 UOM 平台拒绝");
        } catch (Exception e) {
            log.warn("reportActivation 失败: sysid={}, error={}", request.sysid(), e.getMessage());
            return new ActivationResult(false, null, e.getMessage());
        }
    }

    @Override
    public CancellationResult reportCancellation(CancellationRequest request) {
        log.debug("reportCancellation: sysid={}, serialNo={}", request.sysid(), request.productSerialNo());

        String path = "/api/cancel";
        String url = baseUrl + path;

        Map<String, Object> body = new HashMap<>();
        body.put("serialNo", request.productSerialNo());
        body.put("reason", request.cancellationReason());

        try {
            JsonNode response = executeWithRetry(url, "POST", path, body);
            if (response == null) {
                return new CancellationResult(false, null,
                        "UOM 平台不可达或响应异常");
            }

            boolean success = response.path("success").asBoolean(false);
            String cancellationId = response.path("cancellationId").asText(null);

            log.info("reportCancellation 完成: sysid={}, success={}, cancellationId={}",
                    request.sysid(), success, cancellationId);
            return new CancellationResult(success, cancellationId,
                    success ? null : "注销上报被 UOM 平台拒绝");
        } catch (Exception e) {
            log.warn("reportCancellation 失败: sysid={}, error={}", request.sysid(), e.getMessage());
            return new CancellationResult(false, null, e.getMessage());
        }
    }

    @Override
    public TelemetryResult reportTelemetry(TelemetryReport report) {
        log.debug("reportTelemetry: sysid={}, serialNo={}", report.sysid(), report.productSerialNo());

        String path = "/api/telemetry";
        String url = baseUrl + path;

        Map<String, Object> body = new HashMap<>();
        body.put("sysid", report.sysid());
        body.put("serialNo", report.productSerialNo());
        body.put("timestamp", report.timestamp());
        body.put("lat", report.latitude());
        body.put("lon", report.longitude());
        body.put("alt", report.altitudeM());
        body.put("speed", report.groundSpeedMs());
        body.put("heading", report.headingDeg());
        body.put("status", report.flightStatus().name());

        try {
            JsonNode response = executeWithRetry(url, "POST", path, body);
            if (response == null) {
                return new TelemetryResult(false,
                        "UOM 平台不可达或响应异常");
            }

            boolean success = response.path("success").asBoolean(false);

            log.debug("reportTelemetry 完成: sysid={}, success={}", report.sysid(), success);
            return new TelemetryResult(success,
                    success ? null : "遥测上报被 UOM 平台拒绝");
        } catch (Exception e) {
            log.warn("reportTelemetry 失败: sysid={}, error={}", report.sysid(), e.getMessage());
            return new TelemetryResult(false, e.getMessage());
        }
    }

    // ─── 内部方法 ───

    /**
     * 执行 HTTP 请求，失败时按指数退避策略重试。
     * <p>
     * 重试次数最多 {@code maxRetry} 次，退避间隔为 1s → 2s → 4s（即 {@code 1000 * 2^attempt}）。
     * HTTP 状态码非 2xx 或网络异常均视为失败，触发重试。
     * <p>
     * 每个请求自动附加认证头（API Key）和可选签名头（HMAC-SHA256）。
     *
     * @param url     请求完整 URL
     * @param method  HTTP 方法（GET 或 POST）
     * @param path    请求路径（用于签名计算）
     * @param body    POST 请求体（GET 时为 null）
     * @return 响应 JSON 节点，所有重试均失败时返回 null
     */
    private JsonNode executeWithRetry(String url, String method, String path, Map<String, Object> body) {
        int attempts = 0;
        Exception lastException = null;

        while (attempts <= maxRetry) {
            try {
                String jsonBody = (body != null) ? objectMapper.writeValueAsString(body) : "";
                String timestamp = String.valueOf(System.currentTimeMillis());

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(
                                httpClient.connectTimeout().map(Duration::toMillis).orElse(5000L)));

                // 附加认证头
                if (apiKey != null && !apiKey.isBlank()) {
                    requestBuilder.header(HEADER_API_KEY, apiKey);
                }

                // 附加签名头（可选）
                if (signingKey != null && !signingKey.isBlank()) {
                    String signature = computeSignature(method, path, timestamp, jsonBody);
                    requestBuilder.header(HEADER_TIMESTAMP, timestamp);
                    requestBuilder.header(HEADER_SIGNATURE, signature);
                }

                if ("GET".equals(method)) {
                    requestBuilder.GET();
                } else {
                    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                            .header("Content-Type", "application/json");
                }

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return objectMapper.readTree(response.body());
                }

                log.warn("HTTP {} {} 返回状态码 {}（第 {} 次尝试）",
                        method, url, response.statusCode(), attempts + 1);
            } catch (Exception e) {
                lastException = e;
                log.warn("HTTP {} {} 异常（第 {} 次尝试）: {}",
                        method, url, attempts + 1, e.getMessage());
            }

            attempts++;
            if (attempts <= maxRetry) {
                long delayMs = RETRY_BASE_DELAY_MS * (1L << (attempts - 1)); // 1s, 2s, 4s
                log.debug("等待 {}ms 后重试...", delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("重试等待被中断");
                    return null;
                }
            }
        }

        if (lastException != null) {
            log.error("HTTP {} {} 全部重试失败: {}", method, url, lastException.getMessage());
        }
        return null;
    }

    /**
     * 计算 HMAC-SHA256 请求签名。
     * <p>
     * 签名内容为 {@code METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + BODY}，
     * 使用 {@code signingKey} 作为 HMAC 密钥，输出 Base64 编码的签名值。
     * <p>
     * 当 {@code signingKey} 未配置时返回 null，表示不签名。
     *
     * @param method    HTTP 方法（GET / POST）
     * @param path      请求路径（含查询参数）
     * @param timestamp 请求时间戳（毫秒）
     * @param body      请求体（GET 时为空字符串）
     * @return Base64 编码的 HMAC-SHA256 签名，或 null（未配置签名密钥时）
     * @throws Exception 签名计算异常
     */
    private String computeSignature(String method, String path, String timestamp, String body) throws Exception {
        String payload = method + "\n" + path + "\n" + timestamp + "\n" + body;
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec keySpec = new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        mac.init(keySpec);
        byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hmacBytes);
    }

    /**
     * 解析验证状态字符串为枚举值。
     * <p>
     * UOM 平台返回的状态字符串可能为 VERIFIED / UNVERIFIED / NOT_FOUND，
     * 无法识别的值统一映射为 {@link VerifyStatus#ERROR}。
     *
     * @param statusStr 状态字符串
     * @return 对应的枚举值
     */
    private VerifyStatus parseVerifyStatus(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            return VerifyStatus.ERROR;
        }
        try {
            return VerifyStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            log.warn("无法识别的验证状态: {}", statusStr);
            return VerifyStatus.ERROR;
        }
    }

    /**
     * 对所有者姓名脱敏：仅保留姓氏 + *。
     * <p>
     * 例如："张三" → "张*"，"李四" → "李*"，"王" → "王*"，
     * null 或空字符串返回 null。
     *
     * @param ownerName 原始姓名
     * @return 脱敏后的姓名
     */
    private String maskOwnerName(String ownerName) {
        if (ownerName == null || ownerName.isBlank()) {
            return null;
        }
        return ownerName.charAt(0) + "*";
    }
}