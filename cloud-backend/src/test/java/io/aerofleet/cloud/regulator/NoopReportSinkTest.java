package io.aerofleet.cloud.regulator;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NoopReportSink} 单元测试（直接实例化，无 Spring 上下文）。
 * <p>
 * 验证所有操作返回默认成功结果，不发起任何网络调用。
 */
@DisplayName("NoopReportSink 空实现")
class NoopReportSinkTest {

    private NoopReportSink sink;

    @BeforeEach
    void setUp() {
        sink = new NoopReportSink();
    }

    @Test
    @DisplayName("verifyRealNameStatus 返回 VERIFIED 状态和空字段")
    void verifyRealNameStatus_returnsVerifiedWithEmptyFields() {
        VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");

        VerifyResult result = sink.verifyRealNameStatus(request);

        assertThat(result.status()).isEqualTo(VerifyStatus.VERIFIED);
        assertThat(result.ownerName()).isEmpty();
        assertThat(result.registerDate()).isEmpty();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("reportActivation 返回成功结果和 NOOP 标识")
    void reportActivation_returnsSuccessWithNoopId() {
        ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);

        ActivationResult result = sink.reportActivation(request);

        assertThat(result.success()).isTrue();
        assertThat(result.activationId()).isEqualTo("NOOP");
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("reportCancellation 返回成功结果和 NOOP 标识")
    void reportCancellation_returnsSuccessWithNoopId() {
        CancellationRequest request = new CancellationRequest(1, "SN-001", "测试注销");

        CancellationResult result = sink.reportCancellation(request);

        assertThat(result.success()).isTrue();
        assertThat(result.cancellationId()).isEqualTo("NOOP");
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("reportTelemetry 返回成功结果")
    void reportTelemetry_returnsSuccess() {
        TelemetryReport report = new TelemetryReport(1, "SN-001", System.currentTimeMillis(),
                39.9, 116.3, 100.0, 10.0, 90.0, FlightStatus.GROUND);

        TelemetryResult result = sink.reportTelemetry(report);

        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("所有操作不抛出异常（无网络调用）")
    void allOperations_doNotThrowExceptions() {
        VerifyRequest verifyReq = new VerifyRequest(2, "SN-002", "CERT-002");
        ActivationRequest actReq = new ActivationRequest(2, "SN-002", 0, 40.0, 117.0);
        CancellationRequest cancelReq = new CancellationRequest(2, "SN-002", "正常注销");
        TelemetryReport telReport = new TelemetryReport(2, "SN-002", System.currentTimeMillis(),
                40.0, 117.0, 50.0, 5.0, 180.0, FlightStatus.AIRBORNE);

        assertThat(sink.verifyRealNameStatus(verifyReq).status()).isEqualTo(VerifyStatus.VERIFIED);
        assertThat(sink.reportActivation(actReq).success()).isTrue();
        assertThat(sink.reportCancellation(cancelReq).success()).isTrue();
        assertThat(sink.reportTelemetry(telReport).success()).isTrue();
    }
}