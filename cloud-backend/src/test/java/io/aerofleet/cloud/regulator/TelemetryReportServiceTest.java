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

        /**
         * 读取 {@code latestTelemetry} 里某个 sysid 的快照字段。
         * <p>
         * {@code TelemetrySnapshot} 是私有 record，反射取其访问器即可，
         * 免得为了测试把生产可见性放宽。
         */
        private Object[] readSnapshot(int sysid) throws Exception {
            ConcurrentHashMap<Integer, Object> snaps =
                    getPrivateField("latestTelemetry", ConcurrentHashMap.class);
            Object s = snaps.get(sysid);
            if (s == null) {
                return null;
            }
            java.lang.reflect.Field lat = s.getClass().getDeclaredField("lat");
            java.lang.reflect.Field lon = s.getClass().getDeclaredField("lon");
            java.lang.reflect.Field alt = s.getClass().getDeclaredField("alt");
            java.lang.reflect.Field airborne = s.getClass().getDeclaredField("airborne");
            lat.setAccessible(true);
            lon.setAccessible(true);
            alt.setAccessible(true);
            airborne.setAccessible(true);
            return new Object[]{lat.getDouble(s), lon.getDouble(s), alt.getDouble(s),
                    airborne.getBoolean(s)};
        }

        @Test
        @DisplayName("onTelemetry 把遥测写进快照（逐字段核对）")
        void onTelemetry_storesSnapshot() throws Exception {
            service.onTelemetry(1, 39.9, 116.3, 100.0, 10.0, 90.0, true);

            Object[] snap = readSnapshot(1);
            assertThat(snap).as("onTelemetry 应写入快照").isNotNull();
            assertThat((Double) snap[0]).isEqualTo(39.9, org.assertj.core.data.Offset.offset(1e-9));
            assertThat((Double) snap[1]).isEqualTo(116.3, org.assertj.core.data.Offset.offset(1e-9));
            assertThat((Double) snap[2]).isEqualTo(100.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat((Boolean) snap[3]).isTrue();
        }

        @Test
        @DisplayName("onTelemetry 多次调用覆盖为最新快照")
        void onTelemetry_multipleUpdates() throws Exception {
            service.onTelemetry(1, 39.9, 116.3, 100.0, 10.0, 90.0, true);
            service.onTelemetry(1, 40.0, 117.0, 200.0, 20.0, 180.0, false);

            Object[] snap = readSnapshot(1);
            assertThat(snap).as("快照应存在").isNotNull();
            // 后一次必须覆盖前一次 —— 这一条才是"多次调用更新"的语义
            assertThat((Double) snap[0]).isEqualTo(40.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat((Double) snap[1]).isEqualTo(117.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat((Double) snap[2]).isEqualTo(200.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat((Boolean) snap[3]).as("airborne 应被第二次调用改回 false").isFalse();

            ConcurrentHashMap<Integer, Long> times =
                    getPrivateField("lastTelemetryTime", ConcurrentHashMap.class);
            assertThat(times.get(1)).as("lastTelemetryTime 也应被写入").isNotNull();
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
        @DisplayName("startReporting 重复启动忽略：任务句柄不得被替换")
        void startReporting_duplicateIgnored() throws Exception {
            service.startReporting(1);
            ConcurrentHashMap<Integer, ScheduledFuture<?>> tasks =
                    getPrivateField("reportingTasks", ConcurrentHashMap.class);
            ScheduledFuture<?> first = tasks.get(1);
            assertThat((Object) first).isNotNull();

            // 再次启动同一 sysid 的定时任务
            service.startReporting(1);

            // 关键：断言的是「同一个 ScheduledFuture 实例还在」。
            // 只断言 containsKey(1) 是不够的 —— 那条去重守卫整个删掉时，
            // 第二次调用只是 put 覆盖了同一个 key，containsKey 依然为真，
            // 用例照样全绿（实测：删掉守卫后本用例仍通过）。
            assertThat((Object) tasks.get(1))
                    .as("重复启动不得替换已有任务句柄（否则旧的定时任务泄漏、新的重复调度）")
                    .isSameAs(first);
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
        @DisplayName("stopReporting 不存在的 sysid 不抛出异常且不留下条目")
        void stopReporting_nonExistent_noException() throws Exception {
            service.stopReporting(99);

            // 断言的是"没留下任何东西"，而不只是"没抛异常"：
            // 原写法是 assertThat(true).isTrue()，那它对任何实现都成立。
            ConcurrentHashMap<Integer, ScheduledFuture<?>> tasks =
                    getPrivateField("reportingTasks", ConcurrentHashMap.class);
            ConcurrentHashMap<Integer, Object> stats =
                    getPrivateField("reportStats", ConcurrentHashMap.class);
            assertThat(tasks).doesNotContainKey(99);
            assertThat(stats).doesNotContainKey(99);
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

    /**
     * 读取 {@code reportStats} 里某个 sysid 的 successCount/failureCount。
     * <p>
     * {@code ReportStats} 是私有静态类，字段是 volatile int —— 反射读即可，
     * 不必把它提成包级可见（那会为了测试改生产可见性）。
     */
    private int[] readStats(int sysid) throws Exception {
        ConcurrentHashMap<Integer, Object> stats = getPrivateField("reportStats", ConcurrentHashMap.class);
        Object s = stats.get(sysid);
        if (s == null) {
            return null;   // startReporting 没被调用过，reportStats 里根本没这个条目
        }
        java.lang.reflect.Field succ = s.getClass().getDeclaredField("successCount");
        java.lang.reflect.Field fail = s.getClass().getDeclaredField("failureCount");
        succ.setAccessible(true);
        fail.setAccessible(true);
        return new int[]{succ.getInt(s), fail.getInt(s)};
    }

    /** 把状态推进到 ACTIVATED（doReport 的成功分支会尝试转 OPERATING）。 */
    private void activate(int sysid) {
        stateManager.transition(sysid, ComplianceState.UNVERIFIED);
        stateManager.transition(sysid, ComplianceState.VERIFIED);
        stateManager.transition(sysid, ComplianceState.ACTIVATED);
    }

    @Test
    @DisplayName("上报成功时统计计数递增")
    void doReport_success_incrementsStats() throws Exception {
        activate(1);
        when(sink.reportTelemetry(any())).thenReturn(new TelemetryResult(true, null));

        // 关键：reportStats 只在 startReporting 里 putIfAbsent。不先建出这个条目，
        // doReport 里的 `stats` 就是 null，`successCount++` 一次都不会执行——
        // 那样这条用例的名字（"计数递增"）就完全没有被验证。
        // 这一点是实测发现的：删掉生产代码的 successCount++ 后本用例仍然全绿。
        service.startReporting(1);
        try {
            int[] before = readStats(1);
            assertThat(before).as("startReporting 应已建出统计条目").isNotNull();

            TelemetryReport report = new TelemetryReport(1, "SN-001",
                    System.currentTimeMillis(), 39.9, 116.3, 100.0, 10.0, 90.0,
                    FlightStatus.GROUND);
            invokeDoReport(1, report, stateManager.get(1));

            int[] after = readStats(1);
            assertThat(after[0]).as("成功计数应递增").isEqualTo(before[0] + 1);
            assertThat(after[1]).as("成功路径不该动失败计数").isEqualTo(before[1]);
            verify(sink).reportTelemetry(any());
        } finally {
            service.stopReporting(1);
        }
    }

    @Test
    @DisplayName("上报失败时统计计数递增")
    void doReport_failure_incrementsStats() throws Exception {
        activate(1);
        when(sink.reportTelemetry(any())).thenReturn(new TelemetryResult(false, "失败"));

        service.startReporting(1);
        try {
            int[] before = readStats(1);
            assertThat(before).as("startReporting 应已建出统计条目").isNotNull();

            TelemetryReport report = new TelemetryReport(1, "SN-001",
                    System.currentTimeMillis(), 39.9, 116.3, 100.0, 10.0, 90.0,
                    FlightStatus.GROUND);

            invokeDoReport(1, report, stateManager.get(1));

            int[] after = readStats(1);
            assertThat(after[1]).as("失败计数应递增").isEqualTo(before[1] + 1);
            assertThat(after[0]).as("失败路径不该动成功计数").isEqualTo(before[0]);
            verify(sink).reportTelemetry(any());
        } finally {
            service.stopReporting(1);
        }
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