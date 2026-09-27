package io.aerofleet.regulatorsim;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * regulator-sim 综合单元测试。
 * <p>
 * 覆盖五大验收条件：
 * <ol>
 *   <li>PresetDataStore 测试：默认数据加载、find 查询（已实名/未实名/不存在）</li>
 *   <li>RecordStore 测试：添加/查询/清理</li>
 *   <li>MockUomServer 测试：五个 API 端点的 HTTP 请求验证</li>
 *   <li>延迟模拟测试：--delay-ms 100 → 响应时间 ≥ 100ms</li>
 *   <li>错误率测试：--error-rate 1.0 → 所有请求返回 500</li>
 * </ol>
 */
@DisplayName("regulator-sim 综合单元测试")
class RegulatorSimMainTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 各 Nested 类使用不同端口，避免并行测试时端口冲突
    private static final int PORT_DEFAULT = 19090;
    private static final int PORT_DELAY = 19091;
    private static final int PORT_ERROR = 19092;

    // =====================================================================
    // 1. PresetDataStore 测试
    // =====================================================================

    @Nested
    @DisplayName("PresetDataStore 预置数据存储")
    class PresetDataStoreTests {

        @Test
        @DisplayName("默认加载 3 条预置数据")
        void defaultDataLoaded() {
            PresetDataStore store = new PresetDataStore();
            assertThat(store.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("find 查询已实名条目（TEST-001）返回 VERIFIED 及完整信息")
        void findVerifiedEntry() {
            PresetDataStore store = new PresetDataStore();
            PresetDataStore.PresetEntry entry = store.find("TEST-001");

            assertThat(entry).isNotNull();
            assertThat(entry.serialNo).isEqualTo("TEST-001");
            assertThat(entry.certNo).isEqualTo("CERT-001");
            assertThat(entry.status).isEqualTo("VERIFIED");
            assertThat(entry.owner).isEqualTo("张三");
            assertThat(entry.registerDate).isEqualTo("2026-01-15");
        }

        @Test
        @DisplayName("find 查询未实名条目（TEST-002）返回 UNVERIFIED 且 owner/registerDate 为 null")
        void findUnverifiedEntry() {
            PresetDataStore store = new PresetDataStore();
            PresetDataStore.PresetEntry entry = store.find("TEST-002");

            assertThat(entry).isNotNull();
            assertThat(entry.serialNo).isEqualTo("TEST-002");
            assertThat(entry.certNo).isEqualTo("CERT-002");
            assertThat(entry.status).isEqualTo("UNVERIFIED");
            assertThat(entry.owner).isNull();
            assertThat(entry.registerDate).isNull();
        }

        @Test
        @DisplayName("find 查询不存在的序列号（TEST-999）返回 null")
        void findNonExistentEntry() {
            PresetDataStore store = new PresetDataStore();
            PresetDataStore.PresetEntry entry = store.find("TEST-999");

            assertThat(entry).isNull();
        }
    }

    // =====================================================================
    // 2. RecordStore 测试
    // =====================================================================

    @Nested
    @DisplayName("RecordStore 上报记录存储")
    class RecordStoreTests {

        private RecordStore store;

        @BeforeEach
        void setUp() {
            store = new RecordStore();
        }

        @Test
        @DisplayName("添加记录后能查询到对应类型记录")
        void addAndQuery() {
            Map<String, Object> data = new HashMap<>();
            data.put("serialNo", "TEST-001");
            RecordStore.Record record = new RecordStore.Record(
                    "activation", "TEST-001", System.currentTimeMillis(), data);

            store.add("activation", record);

            List<RecordStore.Record> result = store.query("activation");
            assertThat(result).hasSize(1);
            assertThat(result.get(0).type).isEqualTo("activation");
            assertThat(result.get(0).serialNo).isEqualTo("TEST-001");
            assertThat(result.get(0).data).containsEntry("serialNo", "TEST-001");
        }

        @Test
        @DisplayName("查询不存在的类型返回空列表")
        void queryNonExistentType() {
            List<RecordStore.Record> result = store.query("activation");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("添加多条不同类型记录后分别查询互不干扰")
        void addMultipleTypesAndQuery() {
            Map<String, Object> data1 = new HashMap<>();
            data1.put("serialNo", "TEST-001");
            RecordStore.Record r1 = new RecordStore.Record(
                    "activation", "TEST-001", 1000L, data1);
            store.add("activation", r1);

            Map<String, Object> data2 = new HashMap<>();
            data2.put("serialNo", "TEST-002");
            RecordStore.Record r2 = new RecordStore.Record(
                    "telemetry", "TEST-002", 2000L, data2);
            store.add("telemetry", r2);

            Map<String, Object> data3 = new HashMap<>();
            data3.put("serialNo", "TEST-003");
            RecordStore.Record r3 = new RecordStore.Record(
                    "cancellation", "TEST-003", 3000L, data3);
            store.add("cancellation", r3);

            assertThat(store.query("activation")).hasSize(1);
            assertThat(store.query("telemetry")).hasSize(1);
            assertThat(store.query("cancellation")).hasSize(1);
            assertThat(store.count("activation")).isEqualTo(1);
            assertThat(store.count("telemetry")).isEqualTo(1);
            assertThat(store.count("cancellation")).isEqualTo(1);
        }

        @Test
        @DisplayName("clear 清空所有记录")
        void clearAllRecords() {
            Map<String, Object> data = new HashMap<>();
            RecordStore.Record r = new RecordStore.Record(
                    "activation", "TEST-001", 1000L, data);
            store.add("activation", r);
            assertThat(store.count("activation")).isEqualTo(1);

            store.clear();

            assertThat(store.count("activation")).isEqualTo(0);
            assertThat(store.query("activation")).isEmpty();
        }

        @Test
        @DisplayName("count 对不存在类型返回 0")
        void countNonExistentType() {
            assertThat(store.count("activation")).isEqualTo(0);
        }
    }

    // =====================================================================
    // 3. MockUomServer 五端点 HTTP 测试
    // =====================================================================

    @Nested
    @DisplayName("MockUomServer 五端点 HTTP 测试")
    class MockUomServerTests {

        private MockUomServer server;
        private HttpClient httpClient;

        @BeforeEach
        void setUp() throws IOException {
            SimConfig config = SimConfig.parse(new String[]{"--port", String.valueOf(PORT_DEFAULT)});
            server = new MockUomServer(config);
            server.start();
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }

        @AfterEach
        void tearDown() {
            server.stop();
        }

        // ── GET /api/verify ──

        @Test
        @DisplayName("GET /api/verify 已实名序列号返回 VERIFIED")
        void verifyVerifiedEntry() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_DEFAULT +
                            "/api/verify?serialNo=TEST-001&certNo=CERT-001"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            assertThat(body.get("status")).isEqualTo("VERIFIED");
            assertThat(body.get("owner")).isEqualTo("张三");
            assertThat(body.get("registerDate")).isEqualTo("2026-01-15");
        }

        @Test
        @DisplayName("GET /api/verify 未实名序列号返回 UNVERIFIED")
        void verifyUnverifiedEntry() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_DEFAULT +
                            "/api/verify?serialNo=TEST-002"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            assertThat(body.get("status")).isEqualTo("UNVERIFIED");
            assertThat(body.get("owner")).isNull();
            assertThat(body.get("registerDate")).isNull();
        }

        @Test
        @DisplayName("GET /api/verify 不存在的序列号返回 NOT_FOUND")
        void verifyNotFoundEntry() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_DEFAULT +
                            "/api/verify?serialNo=TEST-999"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            assertThat(body.get("status")).isEqualTo("NOT_FOUND");
            assertThat(body.get("owner")).isNull();
            assertThat(body.get("registerDate")).isNull();
        }

        // ── POST /api/activate ──

        @Test
        @DisplayName("POST /api/activate 返回 success=true 和 activationId")
        void activateEndpoint() throws Exception {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("serialNo", "TEST-001");
            requestBody.put("activationTime", "2026-03-01T10:00:00Z");
            requestBody.put("lat", 39.9);
            requestBody.put("lon", 116.3);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_DEFAULT + "/api/activate"))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            assertThat(body.get("success")).isEqualTo(true);
            assertThat((String) body.get("activationId")).startsWith("ACT-");
        }

        // ── POST /api/cancel ──

        @Test
        @DisplayName("POST /api/cancel 返回 success=true 和 cancellationId")
        void cancelEndpoint() throws Exception {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("serialNo", "TEST-001");
            requestBody.put("reason", "用户主动注销");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_DEFAULT + "/api/cancel"))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            assertThat(body.get("success")).isEqualTo(true);
            assertThat((String) body.get("cancellationId")).startsWith("CAN-");
        }

        // ── POST /api/telemetry ──

        @Test
        @DisplayName("POST /api/telemetry 返回 success=true")
        void telemetryEndpoint() throws Exception {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("sysid", 1);
            requestBody.put("serialNo", "TEST-001");
            requestBody.put("timestamp", System.currentTimeMillis());
            requestBody.put("lat", 39.9);
            requestBody.put("lon", 116.3);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_DEFAULT + "/api/telemetry"))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            assertThat(body.get("success")).isEqualTo(true);
        }

        // ── GET /api/records ──

        @Test
        @DisplayName("GET /api/records?type=activation 返回激活记录列表")
        void recordsEndpoint() throws Exception {
            // 先通过 activate 端点添加一条 activation 记录
            Map<String, Object> activateBody = new HashMap<>();
            activateBody.put("serialNo", "TEST-001");
            activateBody.put("activationTime", "2026-03-01T10:00:00Z");
            activateBody.put("lat", 39.9);
            activateBody.put("lon", 116.3);

            HttpRequest activateRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_DEFAULT + "/api/activate"))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(activateBody)))
                    .header("Content-Type", "application/json")
                    .build();
            httpClient.send(activateRequest, HttpResponse.BodyHandlers.ofString());

            // 查询 records
            HttpRequest recordsRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_DEFAULT + "/api/records?type=activation"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(recordsRequest, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            List<Map<String, Object>> body = objectMapper.readValue(response.body(), List.class);
            assertThat(body).hasSize(1);
            assertThat(body.get(0).get("type")).isEqualTo("activation");
            assertThat(body.get(0).get("serialNo")).isEqualTo("TEST-001");
        }
    }

    // =====================================================================
    // 4. 延迟模拟测试
    // =====================================================================

    @Nested
    @DisplayName("延迟模拟 (--delay-ms)")
    class DelaySimulationTests {

        private MockUomServer server;
        private HttpClient httpClient;

        @BeforeEach
        void setUp() throws IOException {
            SimConfig config = SimConfig.parse(new String[]{
                    "--port", String.valueOf(PORT_DELAY),
                    "--delay-ms", "100"
            });
            server = new MockUomServer(config);
            server.start();
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        @AfterEach
        void tearDown() {
            server.stop();
        }

        @Test
        @DisplayName("--delay-ms 100 → 响应时间 ≥ 100ms")
        void delayAtLeast100ms() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_DELAY + "/api/verify?serialNo=TEST-001"))
                    .GET()
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(elapsed).isGreaterThanOrEqualTo(100);
        }
    }

    // =====================================================================
    // 5. 错误率测试
    // =====================================================================

    @Nested
    @DisplayName("错误率模拟 (--error-rate)")
    class ErrorRateTests {

        private MockUomServer server;
        private HttpClient httpClient;

        @BeforeEach
        void setUp() throws IOException {
            SimConfig config = SimConfig.parse(new String[]{
                    "--port", String.valueOf(PORT_ERROR),
                    "--error-rate", "1.0"
            });
            server = new MockUomServer(config);
            server.start();
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }

        @AfterEach
        void tearDown() {
            server.stop();
        }

        @Test
        @DisplayName("--error-rate 1.0 → 所有 GET 请求返回 500")
        void allGetRequestsReturn500() throws Exception {
            // verify 端点
            HttpRequest verifyRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_ERROR + "/api/verify?serialNo=TEST-001"))
                    .GET()
                    .build();
            HttpResponse<String> verifyResponse = httpClient.send(verifyRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(verifyResponse.statusCode()).isEqualTo(500);
            Map<String, Object> verifyBody = objectMapper.readValue(verifyResponse.body(), Map.class);
            assertThat(verifyBody.get("error")).isEqualTo("simulated server error");
            assertThat(verifyBody.get("success")).isEqualTo(false);

            // records 端点
            HttpRequest recordsRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + PORT_ERROR + "/api/records?type=activation"))
                    .GET()
                    .build();
            HttpResponse<String> recordsResponse = httpClient.send(recordsRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(recordsResponse.statusCode()).isEqualTo(500);
            Map<String, Object> recordsBody = objectMapper.readValue(recordsResponse.body(), Map.class);
            assertThat(recordsBody.get("error")).isEqualTo("simulated server error");
            assertThat(recordsBody.get("success")).isEqualTo(false);
        }

        @Test
        @DisplayName("--error-rate 1.0 → 所有 POST 请求返回 500")
        void allPostRequestsReturn500() throws Exception {
            Map<String, Object> postBody = new HashMap<>();
            postBody.put("serialNo", "TEST-001");

            String[] postEndpoints = {"/api/activate", "/api/cancel", "/api/telemetry"};
            for (String endpoint : postEndpoints) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + PORT_ERROR + endpoint))
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(postBody)))
                        .header("Content-Type", "application/json")
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(500);

                Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
                assertThat(body.get("error")).isEqualTo("simulated server error");
                assertThat(body.get("success")).isEqualTo(false);
            }
        }
    }
}