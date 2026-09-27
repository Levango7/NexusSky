package io.aerofleet.cloud.regulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aerofleet.cloud.regulator.model.ActivationRequest;
import io.aerofleet.cloud.regulator.model.ActivationResult;
import io.aerofleet.cloud.regulator.model.CancellationRequest;
import io.aerofleet.cloud.regulator.model.CancellationResult;
import io.aerofleet.cloud.regulator.model.FlightStatus;
import io.aerofleet.cloud.regulator.model.TelemetryReport;
import io.aerofleet.cloud.regulator.model.TelemetryResult;
import io.aerofleet.cloud.regulator.model.VerifyRequest;
import io.aerofleet.cloud.regulator.model.VerifyResult;
import io.aerofleet.cloud.regulator.model.VerifyStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SimReportSink} 单元测试。
 * <p>
 * 使用 JDK 内置 {@link HttpServer} 创建 Mock HTTP 服务器模拟 regulator-sim，
 * 验证四项操作契约、脱敏逻辑和重试逻辑。
 */
@DisplayName("SimReportSink 模拟监管平台上报通道")
class SimReportSinkTest {

    private HttpServer server;
    private SimReportSink sink;
    private ObjectMapper objectMapper;
    private RegulatorConfig config;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        config = new RegulatorConfig();
        config.setSimBaseUrl("http://localhost:" + port);
        config.setHttpTimeoutMs(5000);
        config.setMaxRetry(0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 发送 JSON 响应的辅助方法。 */
    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.sendResponseHeaders(statusCode, json.getBytes().length);
        try (var os = exchange.getResponseBody()) {
            os.write(json.getBytes());
        }
        exchange.close();
    }

    /** 读取请求体并丢弃（用于 POST 请求 handler）。 */
    private static void consumeRequestBody(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
    }

    // ─── 四项操作验证 ───

    @Nested
    @DisplayName("四项操作正常场景")
    class NormalOperations {

        @Test
        @DisplayName("verifyRealNameStatus 正常验证返回 VERIFIED")
        void verifyRealNameStatus_returnsVerified() {
            server.createContext("/api/verify", exchange -> {
                String response = "{\"status\":\"VERIFIED\",\"owner\":\"张三\",\"registerDate\":\"2024-01-01\"}";
                sendJsonResponse(exchange, 200, response);
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            VerifyResult result = sink.verifyRealNameStatus(request);

            assertThat(result.status()).isEqualTo(VerifyStatus.VERIFIED);
            assertThat(result.ownerName()).isEqualTo("张*");
            assertThat(result.registerDate()).isEqualTo("2024-01-01");
            assertThat(result.errorMessage()).isNull();
        }

        @Test
        @DisplayName("reportActivation 正常激活返回成功")
        void reportActivation_returnsSuccess() {
            server.createContext("/api/activate", exchange -> {
                consumeRequestBody(exchange);
                String response = "{\"success\":true,\"activationId\":\"ACT-001\"}";
                sendJsonResponse(exchange, 200, response);
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
            ActivationResult result = sink.reportActivation(request);

            assertThat(result.success()).isTrue();
            assertThat(result.activationId()).isEqualTo("ACT-001");
            assertThat(result.errorMessage()).isNull();
        }

        @Test
        @DisplayName("reportCancellation 正常注销返回成功")
        void reportCancellation_returnsSuccess() {
            server.createContext("/api/cancel", exchange -> {
                consumeRequestBody(exchange);
                String response = "{\"success\":true,\"cancellationId\":\"CAN-001\"}";
                sendJsonResponse(exchange, 200, response);
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            CancellationRequest request = new CancellationRequest(1, "SN-001", "测试注销");
            CancellationResult result = sink.reportCancellation(request);

            assertThat(result.success()).isTrue();
            assertThat(result.cancellationId()).isEqualTo("CAN-001");
            assertThat(result.errorMessage()).isNull();
        }

        @Test
        @DisplayName("reportTelemetry 正常遥测上报返回成功")
        void reportTelemetry_returnsSuccess() {
            server.createContext("/api/telemetry", exchange -> {
                consumeRequestBody(exchange);
                String response = "{\"success\":true}";
                sendJsonResponse(exchange, 200, response);
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            TelemetryReport report = new TelemetryReport(1, "SN-001", System.currentTimeMillis(),
                    39.9, 116.3, 100.0, 10.0, 90.0, FlightStatus.GROUND);
            TelemetryResult result = sink.reportTelemetry(report);

            assertThat(result.success()).isTrue();
            assertThat(result.errorMessage()).isNull();
        }
    }

    // ─── 脱敏逻辑验证 ───

    @Nested
    @DisplayName("脱敏逻辑")
    class MaskingLogic {

        @Test
        @DisplayName("所有者姓名脱敏：仅保留姓氏 + *")
        void verifyRealNameStatus_masksOwnerName() {
            server.createContext("/api/verify", exchange -> {
                String response = "{\"status\":\"VERIFIED\",\"owner\":\"张三丰\",\"registerDate\":\"2024-01-01\"}";
                sendJsonResponse(exchange, 200, response);
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            VerifyResult result = sink.verifyRealNameStatus(request);

            assertThat(result.ownerName()).isEqualTo("张*");
        }

        @Test
        @DisplayName("单字姓名脱敏：保留姓氏 + *")
        void verifyRealNameStatus_masksSingleCharName() {
            server.createContext("/api/verify", exchange -> {
                String response = "{\"status\":\"VERIFIED\",\"owner\":\"王\",\"registerDate\":\"2024-01-01\"}";
                sendJsonResponse(exchange, 200, response);
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            VerifyResult result = sink.verifyRealNameStatus(request);

            assertThat(result.ownerName()).isEqualTo("王*");
        }

        @Test
        @DisplayName("空所有者姓名脱敏返回 null")
        void verifyRealNameStatus_emptyOwnerName_returnsNull() {
            server.createContext("/api/verify", exchange -> {
                String response = "{\"status\":\"VERIFIED\",\"owner\":\"\",\"registerDate\":\"2024-01-01\"}";
                sendJsonResponse(exchange, 200, response);
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            VerifyResult result = sink.verifyRealNameStatus(request);

            assertThat(result.ownerName()).isNull();
        }
    }

    // ─── 重试逻辑验证 ───

    @Nested
    @DisplayName("重试逻辑")
    class RetryLogic {

        @Test
        @DisplayName("服务器不可达时返回 ERROR 结果")
        void unreachableServer_returnsError() {
            // 不启动 server，连接会被拒绝
            sink = new SimReportSink(config, objectMapper);

            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            VerifyResult result = sink.verifyRealNameStatus(request);

            assertThat(result.status()).isEqualTo(VerifyStatus.ERROR);
            assertThat(result.errorMessage()).isNotNull();
        }

        @Test
        @DisplayName("服务器返回 500 时激活上报返回失败")
        void serverError500_returnsFailure() {
            server.createContext("/api/activate", exchange -> {
                consumeRequestBody(exchange);
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
            ActivationResult result = sink.reportActivation(request);

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).isNotNull();
        }

        @Test
        @DisplayName("maxRetry=1 时首次失败后重试成功")
        void retryOnce_thenSuccess() throws InterruptedException {
            config.setMaxRetry(1);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.createContext("/api/activate", exchange -> {
                consumeRequestBody(exchange);
                int count = requestCount.incrementAndGet();
                if (count == 1) {
                    exchange.sendResponseHeaders(500, 0);
                    exchange.close();
                } else {
                    String response = "{\"success\":true,\"activationId\":\"ACT-RETRY\"}";
                    sendJsonResponse(exchange, 200, response);
                }
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
            ActivationResult result = sink.reportActivation(request);

            assertThat(result.success()).isTrue();
            assertThat(result.activationId()).isEqualTo("ACT-RETRY");
            assertThat(requestCount.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("maxRetry=1 时全部失败返回失败结果")
        void retryOnce_allFail_returnsFailure() throws InterruptedException {
            config.setMaxRetry(1);

            AtomicInteger requestCount = new AtomicInteger(0);
            server.createContext("/api/activate", exchange -> {
                consumeRequestBody(exchange);
                requestCount.incrementAndGet();
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
            ActivationResult result = sink.reportActivation(request);

            assertThat(result.success()).isFalse();
            assertThat(requestCount.get()).isEqualTo(2); // 初始 + 1 次重试
        }
    }

    // ─── 状态解析验证 ───

    @Nested
    @DisplayName("验证状态解析")
    class StatusParsing {

        @Test
        @DisplayName("UNVERIFIED 状态正确解析")
        void verifyRealNameStatus_unverified() {
            server.createContext("/api/verify", exchange -> {
                String response = "{\"status\":\"UNVERIFIED\",\"owner\":\"李四\",\"registerDate\":null}";
                sendJsonResponse(exchange, 200, response);
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            VerifyResult result = sink.verifyRealNameStatus(request);

            assertThat(result.status()).isEqualTo(VerifyStatus.UNVERIFIED);
        }

        @Test
        @DisplayName("NOT_FOUND 状态正确解析")
        void verifyRealNameStatus_notFound() {
            server.createContext("/api/verify", exchange -> {
                String response = "{\"status\":\"NOT_FOUND\",\"owner\":null,\"registerDate\":null}";
                sendJsonResponse(exchange, 200, response);
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            VerifyResult result = sink.verifyRealNameStatus(request);

            assertThat(result.status()).isEqualTo(VerifyStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("无法识别的状态字符串映射为 ERROR")
        void verifyRealNameStatus_unknownStatus_mapsToError() {
            server.createContext("/api/verify", exchange -> {
                String response = "{\"status\":\"UNKNOWN\",\"owner\":null,\"registerDate\":null}";
                sendJsonResponse(exchange, 200, response);
            });
            server.start();

            sink = new SimReportSink(config, objectMapper);
            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            VerifyResult result = sink.verifyRealNameStatus(request);

            assertThat(result.status()).isEqualTo(VerifyStatus.ERROR);
        }
    }

    // ─── 激活/注销失败场景 ───

    @Test
    @DisplayName("激活上报被监管平台拒绝时返回失败")
    void reportActivation_rejectedByPlatform() {
        server.createContext("/api/activate", exchange -> {
            consumeRequestBody(exchange);
            String response = "{\"success\":false,\"activationId\":null}";
            sendJsonResponse(exchange, 200, response);
        });
        server.start();

        sink = new SimReportSink(config, objectMapper);
        ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
        ActivationResult result = sink.reportActivation(request);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isNotNull();
    }

    @Test
    @DisplayName("注销上报被监管平台拒绝时返回失败")
    void reportCancellation_rejectedByPlatform() {
        server.createContext("/api/cancel", exchange -> {
            consumeRequestBody(exchange);
            String response = "{\"success\":false,\"cancellationId\":null}";
            sendJsonResponse(exchange, 200, response);
        });
        server.start();

        sink = new SimReportSink(config, objectMapper);
        CancellationRequest request = new CancellationRequest(1, "SN-001", "测试注销");
        CancellationResult result = sink.reportCancellation(request);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isNotNull();
    }

    @Test
    @DisplayName("遥测上报被监管平台拒绝时返回失败")
    void reportTelemetry_rejectedByPlatform() {
        server.createContext("/api/telemetry", exchange -> {
            consumeRequestBody(exchange);
            String response = "{\"success\":false}";
            sendJsonResponse(exchange, 200, response);
        });
        server.start();

        sink = new SimReportSink(config, objectMapper);
        TelemetryReport report = new TelemetryReport(1, "SN-001", System.currentTimeMillis(),
                39.9, 116.3, 100.0, 10.0, 90.0, FlightStatus.GROUND);
        TelemetryResult result = sink.reportTelemetry(report);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isNotNull();
    }
}