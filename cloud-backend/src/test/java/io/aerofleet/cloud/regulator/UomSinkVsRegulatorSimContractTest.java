package io.aerofleet.cloud.regulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.aerofleet.regulatorsim.MockUomServer;
import io.aerofleet.regulatorsim.SimConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UomReportSink} ↔ regulator-sim（{@link MockUomServer}）跨模块契约：**真实现对仓内标准假 UOM**。
 *
 * <p>为什么需要它：`e2e-regulator.ps1` 全链走的是 `sink-type=sim`（{@link SimReportSink}），
 * 而 {@link UomReportSink} 只在自带的假 UOM（`UomReportSinkContractTest`）里验过线格式——
 * **两个"假 UOM"各测各的**，真实现与仓内带激活状态机的 MockUomServer 从未对话过。
 * 真实 UOM 平台还没接（P3 等外部 #1），仓内能先做的最强验证就是让两者对上：
 * 字段名 / 路径 / 状态机语义一旦漂移，这里立刻红。
 *
 * <p>口径：MockUomServer 以 {@code --port 0} 起在 ephemeral 端口（内核分配，无硬编码端口冲突）；
 * 断言面 = 实名三态与脱敏、激活→遥测→注销全链与 records 落账、未实名不可激活、
 * 幂等（同 ID）、注销后遥测被拒、请求计数（成功与 4xx 终态都不重试）。
 */
class UomSinkVsRegulatorSimContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockUomServer sim;
    private UomReportSink sink;
    private HttpClient http;
    private String base;

    @BeforeEach
    void startFakeUom() throws Exception {
        sim = new MockUomServer(SimConfig.parse(new String[]{"--port", "0"}));
        sim.start();
        base = "http://127.0.0.1:" + sim.getPort();
        http = HttpClient.newHttpClient();

        RegulatorConfig cfg = new RegulatorConfig();
        cfg.setUomBaseUrl(base);
        cfg.setHttpTimeoutMs(2_000);
        cfg.setMaxRetry(2);          // 成功路径与 4xx 终态都不该用到它——计数断言会核
        cfg.setApiKey("contract-key");
        cfg.setSigningKey("contract-signing-key");
        sink = new UomReportSink(cfg, MAPPER);
    }

    @AfterEach
    void stopFakeUom() {
        sim.stop();
    }

    private JsonNode simGet(String path) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("sim GET %s", path).isEqualTo(200);
        return MAPPER.readTree(resp.body());
    }

    private long stat(String key) throws Exception {
        return simGet("/api/stats").path("requests").path(key).asLong();
    }

    @Test
    @DisplayName("实名查询三态：VERIFIED 脱敏 / UNVERIFIED 原样 / 查无此机 NOT_FOUND")
    void verifyMapsPresetData() {
        VerifyResult verified = sink.verifyRealNameStatus(new VerifyRequest(1, "TEST-001", "CERT-001"));
        assertThat(verified.status()).isEqualTo(VerifyStatus.VERIFIED);
        assertThat(verified.ownerName()).isEqualTo("张*");          // 脱敏在 sink 内完成
        assertThat(verified.registerDate()).isEqualTo("2026-01-15");

        VerifyResult unverified = sink.verifyRealNameStatus(new VerifyRequest(2, "TEST-002", "CERT-002"));
        assertThat(unverified.status()).isEqualTo(VerifyStatus.UNVERIFIED);
        assertThat(unverified.ownerName()).isNull();

        VerifyResult notFound = sink.verifyRealNameStatus(new VerifyRequest(3, "NOPE-999", "CERT-000"));
        assertThat(notFound.status()).isEqualTo(VerifyStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("全链：激活 → 遥测 → 注销；records 三表落账；注销后遥测被拒")
    void fullChainAgainstStateMachine() throws Exception {
        ActivationResult activated = sink.reportActivation(
                new ActivationRequest(1, "TEST-001", 1_710_000_000_000L, 22.5, 113.9));
        assertThat(activated.success()).as(activated.errorMessage()).isTrue();
        assertThat(activated.activationId()).startsWith("ACT-");

        TelemetryResult telemetry = sink.reportTelemetry(
                new TelemetryReport(1, "TEST-001", 1_710_000_001_000L, 22.51, 113.91,
                        100.0, 5.0, 90.0, FlightStatus.AIRBORNE));
        assertThat(telemetry.success()).as(telemetry.errorMessage()).isTrue();
        assertThat(simGet("/api/records?type=telemetry").toString()).contains("TEST-001");

        CancellationResult cancelled = sink.reportCancellation(
                new CancellationRequest(1, "TEST-001", "contract test"));
        assertThat(cancelled.success()).as(cancelled.errorMessage()).isTrue();
        assertThat(cancelled.cancellationId()).startsWith("CAN-");
        assertThat(simGet("/api/records?type=cancellation").toString()).contains("TEST-001");

        // 注销后：sim 状态机 403 CANCELLED，sink 必须如实回报失败（不因 4xx 反复重试）
        TelemetryResult afterCancel = sink.reportTelemetry(
                new TelemetryReport(1, "TEST-001", 1_710_000_002_000L, 22.52, 113.92,
                        100.0, 5.0, 90.0, FlightStatus.AIRBORNE));
        assertThat(afterCancel.success()).isFalse();
    }

    @Test
    @DisplayName("未实名不能激活：sim 403 终态，sink 如实失败且只打一次（不重试）")
    void unverifiedCannotActivateAndIsNotRetried() throws Exception {
        ActivationResult rejected = sink.reportActivation(
                new ActivationRequest(2, "TEST-002", 1_710_000_000_000L, 22.5, 113.9));
        assertThat(rejected.success()).isFalse();
        assertThat(rejected.errorMessage()).isNotNull();

        assertThat(stat("POST /api/activate"))
                .as("4xx 是终态：maxRetry=2 也只应发 1 次")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("幂等：重复激活 / 重复注销返回同一 ID（真实 UOM 语义）")
    void idempotentActivateAndCancel() {
        ActivationResult first = sink.reportActivation(
                new ActivationRequest(1, "TEST-003", 1_710_000_000_000L, 30.1, 120.2));
        ActivationResult again = sink.reportActivation(
                new ActivationRequest(1, "TEST-003", 1_710_000_000_500L, 30.1, 120.2));
        assertThat(first.success()).isTrue();
        assertThat(again.success()).isTrue();
        assertThat(again.activationId()).isEqualTo(first.activationId());

        CancellationResult cancelled = sink.reportCancellation(
                new CancellationRequest(1, "TEST-003", "done"));
        CancellationResult cancelledAgain = sink.reportCancellation(
                new CancellationRequest(1, "TEST-003", "done"));
        assertThat(cancelled.success()).isTrue();
        assertThat(cancelledAgain.success()).isTrue();
        assertThat(cancelledAgain.cancellationId()).isEqualTo(cancelled.cancellationId());
    }

    @Test
    @DisplayName("成功路径不重试：全链后每个端点恰各 1 次")
    void happyPathDoesNotRetry() throws Exception {
        sink.verifyRealNameStatus(new VerifyRequest(1, "TEST-001", "CERT-001"));
        sink.reportActivation(new ActivationRequest(1, "TEST-001", 1_710_000_000_000L, 22.5, 113.9));
        sink.reportTelemetry(new TelemetryReport(1, "TEST-001", 1_710_000_001_000L, 22.5, 113.9,
                100.0, 5.0, 90.0, FlightStatus.AIRBORNE));

        assertThat(stat("GET /api/verify")).isEqualTo(1);
        assertThat(stat("POST /api/activate")).isEqualTo(1);
        assertThat(stat("POST /api/telemetry")).isEqualTo(1);
    }
}
