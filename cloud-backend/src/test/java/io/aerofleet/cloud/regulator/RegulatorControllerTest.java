package io.aerofleet.cloud.regulator;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.regulator.model.ActivationRequest;
import io.aerofleet.cloud.regulator.model.ActivationResult;
import io.aerofleet.cloud.regulator.model.CancellationRequest;
import io.aerofleet.cloud.regulator.model.CancellationResult;
import io.aerofleet.cloud.regulator.model.ComplianceState;
import io.aerofleet.cloud.regulator.model.VerifyRequest;
import io.aerofleet.cloud.regulator.model.VerifyResult;
import io.aerofleet.cloud.regulator.model.VerifyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RegulatorController} REST 端点单元测试（直接实例化，无 MockMvc / Spring 上下文）。
 * <p>
 * Mock {@link RegulatorReportSink} 和 {@link TelemetryReportService}，
 * 使用真实 {@link ComplianceStateManager}，验证端点逻辑与状态码。
 */
@DisplayName("RegulatorController REST 端点")
class RegulatorControllerTest {

    private RegulatorReportSink sink;
    private ComplianceStateManager stateManager;
    private TelemetryReportService telemetryReportService;
    private RegulatorController controller;

    @BeforeEach
    void setUp() {
        sink = Mockito.mock(RegulatorReportSink.class);
        stateManager = new ComplianceStateManager();
        telemetryReportService = Mockito.mock(TelemetryReportService.class);
        controller = new RegulatorController(sink, stateManager, telemetryReportService);
    }

    // ─── POST /verify 实名验证 ───

    @Nested
    @DisplayName("POST /verify 实名验证")
    class VerifyEndpoint {

        @Test
        @DisplayName("验证成功返回 200 和 VERIFIED 状态")
        void verify_success() {
            when(sink.verifyRealNameStatus(any())).thenReturn(
                    new VerifyResult(VerifyStatus.VERIFIED, "张*", "2024-01-01", null));

            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            ResponseEntity<Map<String, Object>> resp = controller.verify(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("sysid")).isEqualTo(1);
            assertThat(resp.getBody().get("verifyStatus")).isEqualTo("VERIFIED");
            assertThat(resp.getBody().get("ownerName")).isEqualTo("张*");
            assertThat(resp.getBody().get("registerDate")).isEqualTo("2024-01-01");
        }

        @Test
        @DisplayName("验证未通过返回 200 和 UNVERIFIED 状态")
        void verify_unverified() {
            when(sink.verifyRealNameStatus(any())).thenReturn(
                    new VerifyResult(VerifyStatus.UNVERIFIED, null, null, null));

            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            ResponseEntity<Map<String, Object>> resp = controller.verify(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("verifyStatus")).isEqualTo("UNVERIFIED");
        }

        @Test
        @DisplayName("已 CANCELLED 的无人机拒绝验证返回 409")
        void verify_cancelled_returns409() {
            // 先将状态流转到 CANCELLED
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);
            stateManager.transition(1, ComplianceState.CANCELLED);

            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            ResponseEntity<Map<String, Object>> resp = controller.verify(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).asString().contains("已注销");
        }

        @Test
        @DisplayName("验证成功后状态自动流转为 VERIFIED")
        void verify_success_transitionsToVerified() {
            when(sink.verifyRealNameStatus(any())).thenReturn(
                    new VerifyResult(VerifyStatus.VERIFIED, "张*", "2024-01-01", null));

            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            controller.verify(request);

            assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.VERIFIED);
        }

        @Test
        @DisplayName("验证未通过时状态保持 UNVERIFIED")
        void verify_unverified_staysUnverified() {
            when(sink.verifyRealNameStatus(any())).thenReturn(
                    new VerifyResult(VerifyStatus.UNVERIFIED, null, null, null));

            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            controller.verify(request);

            assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.UNVERIFIED);
        }

        @Test
        @DisplayName("脱敏输出：ownerName 字段为脱敏后的值")
        void verify_returnsMaskedOwnerName() {
            when(sink.verifyRealNameStatus(any())).thenReturn(
                    new VerifyResult(VerifyStatus.VERIFIED, "李*", "2024-03-15", null));

            VerifyRequest request = new VerifyRequest(1, "SN-001", "CERT-001");
            ResponseEntity<Map<String, Object>> resp = controller.verify(request);

            assertThat(resp.getBody().get("ownerName")).isEqualTo("李*");
        }
    }

    // ─── POST /activate 激活上报 ───

    @Nested
    @DisplayName("POST /activate 激活上报")
    class ActivateEndpoint {

        @Test
        @DisplayName("激活成功返回 200 和 activationId")
        void activate_success() {
            // 先验证
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);

            when(sink.reportActivation(any())).thenReturn(
                    new ActivationResult(true, "ACT-001", null));

            ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
            ResponseEntity<Map<String, Object>> resp = controller.activate(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("success")).isEqualTo(true);
            assertThat(resp.getBody().get("activationId")).isEqualTo("ACT-001");
        }

        @Test
        @DisplayName("激活成功后状态流转为 ACTIVATED 并启动遥测上报")
        void activate_success_transitionsAndStartsTelemetry() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);

            when(sink.reportActivation(any())).thenReturn(
                    new ActivationResult(true, "ACT-001", null));

            ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
            controller.activate(request);

            assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.ACTIVATED);
            verify(telemetryReportService).startReporting(1);
        }

        @Test
        @DisplayName("未验证状态激活返回 409")
        void activate_notVerified_returns409() {
            ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
            ResponseEntity<Map<String, Object>> resp = controller.activate(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).asString().contains("VERIFIED");
        }

        @Test
        @DisplayName("已 CANCELLED 的无人机拒绝激活返回 409")
        void activate_cancelled_returns409() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);
            stateManager.transition(1, ComplianceState.CANCELLED);

            ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
            ResponseEntity<Map<String, Object>> resp = controller.activate(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).asString().contains("已注销");
        }

        @Test
        @DisplayName("激活失败时不流转状态")
        void activate_failure_noTransition() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);

            when(sink.reportActivation(any())).thenReturn(
                    new ActivationResult(false, null, "激活被拒绝"));

            ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
            ResponseEntity<Map<String, Object>> resp = controller.activate(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("success")).isEqualTo(false);
            assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.VERIFIED);
            verify(telemetryReportService, never()).startReporting(1);
        }

        @Test
        @DisplayName("UNVERIFIED 状态激活返回 409")
        void activate_unverified_returns409() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);

            ActivationRequest request = new ActivationRequest(1, "SN-001", 0, 39.9, 116.3);
            ResponseEntity<Map<String, Object>> resp = controller.activate(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
        }
    }

    // ─── POST /cancel 注销上报 ───

    @Nested
    @DisplayName("POST /cancel 注销上报")
    class CancelEndpoint {

        @Test
        @DisplayName("注销成功返回 200 和 cancellationId")
        void cancel_success() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);

            when(sink.reportCancellation(any())).thenReturn(
                    new CancellationResult(true, "CAN-001", null));

            CancellationRequest request = new CancellationRequest(1, "SN-001", "正常注销");
            ResponseEntity<Map<String, Object>> resp = controller.cancel(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("success")).isEqualTo(true);
            assertThat(resp.getBody().get("cancellationId")).isEqualTo("CAN-001");
        }

        @Test
        @DisplayName("注销成功后状态流转为 CANCELLED 并停止遥测上报")
        void cancel_success_transitionsAndStopsTelemetry() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);

            when(sink.reportCancellation(any())).thenReturn(
                    new CancellationResult(true, "CAN-001", null));

            CancellationRequest request = new CancellationRequest(1, "SN-001", "正常注销");
            controller.cancel(request);

            assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.CANCELLED);
            verify(telemetryReportService).stopReporting(1);
        }

        @Test
        @DisplayName("OPERATING 状态注销成功")
        void cancel_fromOperating_success() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);
            stateManager.transition(1, ComplianceState.OPERATING);

            when(sink.reportCancellation(any())).thenReturn(
                    new CancellationResult(true, "CAN-001", null));

            CancellationRequest request = new CancellationRequest(1, "SN-001", "正常注销");
            ResponseEntity<Map<String, Object>> resp = controller.cancel(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody().get("success")).isEqualTo(true);
        }

        @Test
        @DisplayName("未激活状态注销返回 409")
        void cancel_notActivated_returns409() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);

            CancellationRequest request = new CancellationRequest(1, "SN-001", "正常注销");
            ResponseEntity<Map<String, Object>> resp = controller.cancel(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).asString().contains("ACTIVATED/OPERATING");
        }

        @Test
        @DisplayName("已 CANCELLED 的无人机拒绝注销返回 409")
        void cancel_alreadyCancelled_returns409() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);
            stateManager.transition(1, ComplianceState.CANCELLED);

            CancellationRequest request = new CancellationRequest(1, "SN-001", "重复注销");
            ResponseEntity<Map<String, Object>> resp = controller.cancel(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
            assertThat(resp.getBody().get("error")).asString().contains("已注销");
        }

        @Test
        @DisplayName("不存在状态记录的注销返回 409")
        void cancel_noStatus_returns409() {
            CancellationRequest request = new CancellationRequest(1, "SN-001", "正常注销");
            ResponseEntity<Map<String, Object>> resp = controller.cancel(request);

            assertThat(resp.getStatusCode().value()).isEqualTo(409);
        }

        @Test
        @DisplayName("注销失败时不流转状态")
        void cancel_failure_noTransition() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);

            when(sink.reportCancellation(any())).thenReturn(
                    new CancellationResult(false, null, "注销被拒绝"));

            CancellationRequest request = new CancellationRequest(1, "SN-001", "正常注销");
            ResponseEntity<Map<String, Object>> resp = controller.cancel(request);

            assertThat(resp.getBody().get("success")).isEqualTo(false);
            assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.ACTIVATED);
        }
    }

    // ─── GET /status/{sysid} 合规状态详情 ───

    @Nested
    @DisplayName("GET /status/{sysid} 合规状态详情")
    class GetStatusEndpoint {

        @Test
        @DisplayName("查询存在的 sysid 返回状态详情")
        void getStatus_existing() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);

            Map<String, Object> result = controller.getStatus(1);

            assertThat(result.get("sysid")).isEqualTo(1);
            assertThat(result.get("status")).isEqualTo("UNVERIFIED");
            assertThat(result).containsKey("productSerialNo");
            assertThat(result).containsKey("activationId");
            assertThat(result).containsKey("cancellationId");
        }

        @Test
        @DisplayName("查询不存在的 sysid 抛出 NotFoundException")
        void getStatus_notFound_throws() {
            assertThatThrownBy(() -> controller.getStatus(99))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // ─── GET /status 合规状态列表 ───

    @Nested
    @DisplayName("GET /status 合规状态列表")
    class GetAllStatusEndpoint {

        @Test
        @DisplayName("查询空列表返回空数组")
        void getAllStatus_empty() {
            List<Map<String, Object>> result = controller.getAllStatus();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("查询多个 sysid 返回状态列表")
        void getAllStatus_multiple() {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(2, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);

            List<Map<String, Object>> result = controller.getAllStatus();

            assertThat(result).hasSize(2);
            // 按 sysid 升序排列
            assertThat(result.get(0).get("sysid")).isEqualTo(1);
            assertThat(result.get(0).get("status")).isEqualTo("VERIFIED");
            assertThat(result.get(1).get("sysid")).isEqualTo(2);
            assertThat(result.get(1).get("status")).isEqualTo("UNVERIFIED");
        }
    }

    // ─── 综合场景 ───

    @Test
    @DisplayName("完整生命周期：验证 → 激活 → 注销")
    void fullLifecycle() {
        // 1. 验证
        when(sink.verifyRealNameStatus(any())).thenReturn(
                new VerifyResult(VerifyStatus.VERIFIED, "张*", "2024-01-01", null));
        controller.verify(new VerifyRequest(1, "SN-001", "CERT-001"));
        assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.VERIFIED);

        // 2. 激活
        when(sink.reportActivation(any())).thenReturn(
                new ActivationResult(true, "ACT-001", null));
        controller.activate(new ActivationRequest(1, "SN-001", 0, 39.9, 116.3));
        assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.ACTIVATED);

        // 3. 注销
        when(sink.reportCancellation(any())).thenReturn(
                new CancellationResult(true, "CAN-001", null));
        controller.cancel(new CancellationRequest(1, "SN-001", "正常注销"));
        assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.CANCELLED);
    }

    @Test
    @DisplayName("CANCELLED 后所有写操作返回 409")
    void cancelled_rejectsAllWriteOperations() {
        stateManager.transition(1, ComplianceState.UNVERIFIED);
        stateManager.transition(1, ComplianceState.VERIFIED);
        stateManager.transition(1, ComplianceState.ACTIVATED);
        stateManager.transition(1, ComplianceState.CANCELLED);

        // verify 返回 409
        ResponseEntity<Map<String, Object>> verifyResp = controller.verify(
                new VerifyRequest(1, "SN-001", "CERT-001"));
        assertThat(verifyResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // activate 返回 409
        ResponseEntity<Map<String, Object>> activateResp = controller.activate(
                new ActivationRequest(1, "SN-001", 0, 39.9, 116.3));
        assertThat(activateResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // cancel 返回 409
        ResponseEntity<Map<String, Object>> cancelResp = controller.cancel(
                new CancellationRequest(1, "SN-001", "重复注销"));
        assertThat(cancelResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}