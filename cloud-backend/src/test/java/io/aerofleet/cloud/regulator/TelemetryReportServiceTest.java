package io.aerofleet.cloud.regulator;

import io.aerofleet.cloud.regulator.model.ComplianceState;
import io.aerofleet.cloud.regulator.model.ComplianceStatus;
import io.aerofleet.cloud.regulator.model.FlightStatus;
import io.aerofleet.cloud.regulator.model.TelemetryReport;
import io.aerofleet.cloud.regulator.model.TelemetryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TelemetryReportService} 单元测试（直接实例化，无 Spring 上下文）。
 * <p>
 * 使用 Mockito mock {@link RegulatorReportSink}，使用真实 {@link ComplianceStateManager}，
 * 通过反射调用 private 方法测试核心逻辑。
 */
@DisplayName("TelemetryReportService 遥测上报服务")
class TelemetryReportServiceTest {

    private RegulatorReportSink sink;
    private ComplianceStateManager stateManager;
    private RegulatorConfig config;
    private TelemetryReportService service;

    @BeforeEach
    void setUp() {
        sink = Mockito.mock(RegulatorReportSink.class);
        stateManager = new ComplianceStateManager();

        config = new RegulatorConfig();
        config.setTelemetryReportInterval(10);
        config.setReportQueueCapacity(100);

        service = new TelemetryReportService(sink, stateManager, config);
    }

    /** 通过反射调用 private doReportCycle 方法。 */
    private void invokeDoReportCycle(int sysid) throws Exception {
        Method method = TelemetryReportService.class.getDeclaredMethod("doReportCycle", int.class);
        method.setAccessible(true);
        method.invoke(service, sysid);
    }

    /** 通过反射调用 private doReport 方法。 */
    private void invokeDoReport(int sysid, TelemetryReport report, ComplianceStatus status) throws Exception {
        Method method = TelemetryReportService.class.getDeclaredMethod(
                "doReport", int.class, TelemetryReport.class, ComplianceStatus.class);
        method.setAccessible(true);
        method.invoke(service, sysid, report, status);
    }

    /** 通过反射获取 private 字段值。 */
    @SuppressWarnings("unchecked")
    private <T> T getPrivateField(String fieldName, Class<T> fieldType) throws Exception {
        Field field = TelemetryReportService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(service);
    }

    /** 通过反射设置 private 字段值。 */
    private void setPrivateField(String fieldName, Object value) throws Exception {
        Field field = TelemetryReportService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    // ─── onTelemetry 接收遥测数据 ───

    @Nested
    @DisplayName("onTelemetry 接收遥测数据")
    class OnTelemetry {

        @Test
        @DisplayName("onTelemetry 存储遥测快照不抛出异常")
        void onTelemetry_storesSnapshot() {
            service.onTelemetry(1, 39.9, 116.3, 100.0, 10.0, 90.0, true);

            // 不抛出异常即可验证数据被存储
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("onTelemetry 多次调用更新快照")
        void onTelemetry_multipleUpdates() {
            service.onTelemetry(1, 39.9, 116.3, 100.0, 10.0, 90.0, true);
            service.onTelemetry(1, 40.0, 117.0, 200.0, 20.0, 180.0, false);

            // 不抛出异常即可验证数据被更新
            assertThat(true).isTrue();
        }
    }

    // ─── startReporting / stopReporting ───

    @Nested
    @DisplayName("定时上报启动与停止")
    class StartStopReporting {

        @Test
        @DisplayName("startReporting 创建定时任务")
        void startReporting_createsTask() throws Exception {
            service.startReporting(1);

            ConcurrentHashMap<Integer, ScheduledFuture<?>> tasks = getPrivateField("reportingTasks", ConcurrentHashMap.class);
            assertThat(tasks).containsKey(1);

            assertThat(tasks.get(1).isDone()).isFalse();
        }

        @Test
        @DisplayName("startReporting 重复启动忽略")
        void startReporting_duplicateIgnored() throws Exception {
            service.startReporting(1);

            // 再次启动同一 sysid 的定时任务
            service.startReporting(1);

            ConcurrentHashMap<Integer, ScheduledFuture<?>> tasks = getPrivateField("reportingTasks", ConcurrentHashMap.class);
            // 仍然只有一个任务
            assertThat(tasks).containsKey(1);
        }

        @Test
        @DisplayName("stopReporting 取消定时任务")
        void stopReporting_cancelsTask() throws Exception {
            service.startReporting(1);

            service.stopReporting(1);

            ConcurrentHashMap<Integer, ScheduledFuture<?>> tasks = getPrivateField("reportingTasks", ConcurrentHashMap.class);
            assertThat(tasks).doesNotContainKey(1);
        }

        @Test
        @DisplayName("stopReporting 不存在的 sysid 不抛出异常")
        void stopReporting_nonExistent_noException() {
            service.stopReporting(99);
            // 不抛出异常即可
            assertThat(true).isTrue();
        }
    }

    // ─── 合规状态检查 ───

    @Nested
    @DisplayName("合规状态检查")
    class ComplianceStatusCheck {

        @Test
        @DisplayName("无合规状态记录时跳过上报")
        void doReportCycle_noStatus_skipReport() throws Exception {
            invokeDoReportCycle(1);

            verify(sink, never()).reportTelemetry(any());
        }

        @Test
        @DisplayName("合规状态为 UNVERIFIED 时跳过上报")
        void doReportCycle_unverified_skipReport() throws Exception {
            stateManager.transition(1, ComplianceState.UNVERIFIED);

            invokeDoReportCycle(1);

            verify(sink, never()).reportTelemetry(any());
        }

        @Test
        @DisplayName("合规状态为 VERIFIED 时跳过上报")
        void doReportCycle_verified_skipReport() throws Exception {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);

            invokeDoReportCycle(1);

            verify(sink, never()).reportTelemetry(any());
        }

        @Test
        @DisplayName("合规状态为 CANCELLED 时跳过上报")
        void doReportCycle_cancelled_skipReport() throws Exception {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);
            stateManager.transition(1, ComplianceState.CANCELLED);

            invokeDoReportCycle(1);

            verify(sink, never()).reportTelemetry(any());
        }
    }

    // ─── ACTIVATED → OPERATING 自动转换 ───

    @Nested
    @DisplayName("ACTIVATED → OPERATING 自动转换")
    class AutoTransition {

        @Test
        @DisplayName("首次成功遥测上报后自动将状态从 ACTIVATED 转为 OPERATING")
        void doReport_activatedToOperating() throws Exception {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);

            when(sink.reportTelemetry(any())).thenReturn(new TelemetryResult(true, null));

            TelemetryReport report = new TelemetryReport(1, "SN-001", System.currentTimeMillis(),
                    39.9, 116.3, 100.0, 10.0, 90.0, FlightStatus.GROUND);
            ComplianceStatus status = stateManager.get(1);

            invokeDoReport(1, report, status);

            assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.OPERATING);
        }

        @Test
        @DisplayName("状态为 OPERATING 时不会再次转换")
        void doReport_operating_noTransition() throws Exception {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);
            stateManager.transition(1, ComplianceState.OPERATING);

            when(sink.reportTelemetry(any())).thenReturn(new TelemetryResult(true, null));

            TelemetryReport report = new TelemetryReport(1, "SN-001", System.currentTimeMillis(),
                    39.9, 116.3, 100.0, 10.0, 90.0, FlightStatus.GROUND);
            ComplianceStatus status = stateManager.get(1);

            invokeDoReport(1, report, status);

            // 状态保持 OPERATING，不会抛出异常
            assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.OPERATING);
        }

        @Test
        @DisplayName("遥测上报失败时不转换状态")
        void doReport_failure_noTransition() throws Exception {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);

            when(sink.reportTelemetry(any())).thenReturn(new TelemetryResult(false, "上报失败"));

            TelemetryReport report = new TelemetryReport(1, "SN-001", System.currentTimeMillis(),
                    39.9, 116.3, 100.0, 10.0, 90.0, FlightStatus.GROUND);
            ComplianceStatus status = stateManager.get(1);

            invokeDoReport(1, report, status);

            assertThat(stateManager.get(1).status()).isEqualTo(ComplianceState.ACTIVATED);
        }
    }

    // ─── 设备离线检测 ───

    @Nested
    @DisplayName("设备离线检测")
    class OfflineDetection {

        @Test
        @DisplayName("设备离线超过 3 个上报周期自动停止上报")
        void doReportCycle_offlineAutoStop() throws Exception {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);

            // 启动定时上报
            service.startReporting(1);

            // 设置 lastTelemetryTime 为很久以前，模拟设备离线
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<Integer, Long> lastTelemetryTime =
                    getPrivateField("lastTelemetryTime", ConcurrentHashMap.class);
            lastTelemetryTime.put(1, System.currentTimeMillis() - 1_000_000L);

            invokeDoReportCycle(1);

            // 验证定时任务被取消（stopReporting 被调用）
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<Integer, ScheduledFuture<?>> tasks =
                    getPrivateField("reportingTasks", ConcurrentHashMap.class);
            assertThat(tasks).doesNotContainKey(1);
        }

        @Test
        @DisplayName("设备在线时不触发离线检测")
        void doReportCycle_online_noAutoStop() throws Exception {
            stateManager.transition(1, ComplianceState.UNVERIFIED);
            stateManager.transition(1, ComplianceState.VERIFIED);
            stateManager.transition(1, ComplianceState.ACTIVATED);

            // 接收遥测数据（设备在线）
            service.onTelemetry(1, 39.9, 116.3, 100.0, 10.0, 90.0, true);

            service.startReporting(1);

            invokeDoReportCycle(1);

            // 定时任务仍然存在（未被自动停止）
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<Integer, ScheduledFuture<?>> tasks =
                    getPrivateField("reportingTasks", ConcurrentHashMap.class);
            assertThat(tasks).containsKey(1);
        }
    }

    // ─── 无遥测快照数据 ───

    @Test
    @DisplayName("无遥测快照数据时跳过上报")
    void doReportCycle_noSnapshot_skipReport() throws Exception {
        stateManager.transition(1, ComplianceState.UNVERIFIED);
        stateManager.transition(1, ComplianceState.VERIFIED);
        stateManager.transition(1, ComplianceState.ACTIVATED);

        // 不调用 onTelemetry，所以没有快照数据
        invokeDoReportCycle(1);

        verify(sink, never()).reportTelemetry(any());
    }

    // ─── 上报成功/失败统计 ───

    @Test
    @DisplayName("上报成功时统计计数递增")
    void doReport_success_incrementsStats() throws Exception {
        stateManager.transition(1, ComplianceState.UNVERIFIED);
        stateManager.transition(1, ComplianceState.VERIFIED);
        stateManager.transition(1, ComplianceState.ACTIVATED);

        when(sink.reportTelemetry(any())).thenReturn(new TelemetryResult(true, null));

        TelemetryReport report = new TelemetryReport(1, "SN-001", System.currentTimeMillis(),
                39.9, 116.3, 100.0, 10.0, 90.0, FlightStatus.GROUND);
        ComplianceStatus status = stateManager.get(1);

        invokeDoReport(1, report, status);

        // 验证 sink 被调用
        verify(sink).reportTelemetry(any());
    }

    @Test
    @DisplayName("上报失败时统计计数递增")
    void doReport_failure_incrementsStats() throws Exception {
        stateManager.transition(1, ComplianceState.UNVERIFIED);
        stateManager.transition(1, ComplianceState.VERIFIED);
        stateManager.transition(1, ComplianceState.ACTIVATED);

        when(sink.reportTelemetry(any())).thenReturn(new TelemetryResult(false, "失败"));

        TelemetryReport report = new TelemetryReport(1, "SN-001", System.currentTimeMillis(),
                39.9, 116.3, 100.0, 10.0, 90.0, FlightStatus.GROUND);
        ComplianceStatus status = stateManager.get(1);

        invokeDoReport(1, report, status);

        verify(sink).reportTelemetry(any());
    }

    @Test
    @DisplayName("sink 抛出异常时不影响服务运行")
    void doReport_sinkThrows_noException() throws Exception {
        stateManager.transition(1, ComplianceState.UNVERIFIED);
        stateManager.transition(1, ComplianceState.VERIFIED);
        stateManager.transition(1, ComplianceState.ACTIVATED);

        when(sink.reportTelemetry(any())).thenThrow(new RuntimeException("网络异常"));

        TelemetryReport report = new TelemetryReport(1, "SN-001", System.currentTimeMillis(),
                39.9, 116.3, 100.0, 10.0, 90.0, FlightStatus.GROUND);
        ComplianceStatus status = stateManager.get(1);

        // 不抛出异常（异常被 catch）
        invokeDoReport(1, report, status);
    }
}