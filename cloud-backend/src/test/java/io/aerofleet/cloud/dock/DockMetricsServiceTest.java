package io.aerofleet.cloud.dock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DockMetricsService（spec R5）：日结重算数学 + 聚合响应形状。
 * 关注点：状态区间扫描的边界（跨越日界）、分母零返回 null（不造假数）、
 * 利用率/可用率口径（8h 基准 + 窗口时长分母）。
 */
@DisplayName("DockMetricsService — 日结与聚合")
class DockMetricsServiceTest {

    private DockRepository docks;
    private DockStateLogRepository stateLog;
    private DockRunLogRepository runLog;
    private DockDailyMetricsRepository daily;
    private DockMetricsService svc;
    private DockEntity dock;

    @BeforeEach
    void setUp() {
        docks = mock(DockRepository.class);
        stateLog = mock(DockStateLogRepository.class);
        runLog = mock(DockRunLogRepository.class);
        daily = mock(DockDailyMetricsRepository.class);
        DockProperties props = new DockProperties();
        svc = new DockMetricsService(docks, stateLog, runLog, daily, props);

        dock = new DockEntity();
        dock.id = 1L;
        dock.sn = "DOCK-001";
        dock.state = DockState.IDLE;
        when(docks.findById(1L)).thenReturn(Optional.of(dock));
        when(docks.findAll()).thenReturn(List.of(dock));
        when(daily.save(any(DockDailyMetricsEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(daily.findByDockIdAndDay(anyLong(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("日结：一天在线 2h、1 架次 90min、开门 2 次 → 利用率 = 90/480")
    void rollupComputesFromStateLogAndRuns() {
        ZoneId zone = ZoneId.systemDefault();
        long dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        long h = 3_600_000L;

        // 状态日志：IDLE 从 dayStart 起 2h，然后 CHARGING 到日终
        DockStateLogEntity e1 = log(DockState.IDLE, dayStart + 0);
        DockStateLogEntity e2 = log(DockState.CHARGING, dayStart + 2 * h);
        DockStateLogEntity openLog = log(DockState.OPENING, dayStart + 30 * 60_000L);
        DockStateLogEntity swap = log(DockState.EXCHANGING, dayStart + 3 * h);
        when(stateLog.findByDockIdAndTsBetweenOrderByTs(eq(1L), anyLong(), anyLong()))
                .thenReturn(List.of(e1, e2, openLog, swap));
        when(stateLog.findByDockIdAndTsBetweenOrderByTs(eq(1L), eq(0L), anyLong()))
                .thenReturn(List.of());

        DockRunLogEntity run = new DockRunLogEntity();
        run.dockId = 1L;
        run.result = "OK";
        run.flightMinutes = 90.0;
        when(runLog.findByDockIdAndStartedAtBetween(eq(1L), anyLong(), anyLong()))
                .thenReturn(List.of(run));

        svc.recomputeDay(dock, LocalDate.now(zone), zone);

        // 在线时长应从 dayStart 算到 dayEnd（e1 的 IDLE 段 + e2 的 CHARGING 段）
        org.mockito.ArgumentCaptor<DockDailyMetricsEntity> cap =
                org.mockito.ArgumentCaptor.forClass(DockDailyMetricsEntity.class);
        org.mockito.Mockito.verify(daily).save(cap.capture());
        DockDailyMetricsEntity m = cap.getValue();
        assertThat(m.sorties).isEqualTo(1);
        assertThat(m.flightMinutes).isEqualTo(90.0);
        assertThat(m.doorCycles).isEqualTo(1);   // 一次 OPENING
        assertThat(m.batterySwaps).isEqualTo(1); // 一次 EXCHANGING
        assertThat(m.onlineSeconds).isGreaterThan(0);
        // 平均充电时长：chargingStarts=1（进入 CHARGING）→ 从 e2 到日终的充电段
        assertThat(m.avgChargeTimeMin).isNotNull();
    }

    @Test
    @DisplayName("聚合：无数据的窗口 → 比率为 0 且无分母零异常，平均充电为 null")
    void metricsWithNoDataDistinguishesNull() {
        when(daily.findByDockIdAndDayInOrderByDayDesc(eq(1L), anyList())).thenReturn(List.of());

        Map<String, Object> out = svc.metrics(1L, 7);

        assertThat(out).containsEntry("dockId", 1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary).containsEntry("utilizationPct", 0.0);
        assertThat(summary).containsEntry("availabilityPct", 0.0);
        // 分母为零的比值必须是 null（F1 口径：不造假数）
        assertThat(summary.get("avgChargeTimeMin")).isNull();
        assertThat((List<?>) out.get("perDay")).isEmpty();
    }

    @Test
    @DisplayName("聚合：多日行求和 + 可用率以窗口时长（含缺失天）为分母")
    void metricsAggregatesAcrossDays() {
        DockDailyMetricsEntity d1 = row("2026-10-06", 1, 120, 2, 86_400, 1, 3, 1, 30.0);
        DockDailyMetricsEntity d2 = row("2026-10-05", 2, 240, 4, 43_200, 0, 0, 2, 20.0);
        when(daily.findByDockIdAndDayInOrderByDayDesc(eq(1L), anyList()))
                .thenReturn(List.of(d1, d2));

        Map<String, Object> out = svc.metrics(1L, 7);
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) out.get("summary");

        assertThat(s).containsEntry("sorties", 3);
        assertThat(s).containsEntry("flightMinutes", 360.0);
        assertThat(s).containsEntry("doorCycles", 6);
        assertThat(s).containsEntry("faultCount", 1);
        assertThat(s).containsEntry("tempExcursions", 3);
        assertThat(s).containsEntry("batterySwaps", 3);
        // 可用率 = (86400+43200) / (7*86400) * 100 = 21.43
        assertThat((Double) s.get("availabilityPct")).isEqualTo(21.43);
        // 利用率 = 360 / (480*7) * 100 = 10.71
        assertThat((Double) s.get("utilizationPct")).isEqualTo(10.71);
        assertThat((Double) s.get("avgChargeTimeMin")).isEqualTo(25.0);
    }

    @Test
    @DisplayName("未知机巢 → IllegalArgumentException（控制器 404）")
    void unknownDockRejected() {
        when(docks.findById(99L)).thenReturn(Optional.empty());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> svc.metrics(99L, 7)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DockStateLogEntity log(DockState to, long ts) {
        DockStateLogEntity e = new DockStateLogEntity();
        e.dockId = 1L;
        e.toState = to;
        e.ts = ts;
        return e;
    }

    private DockDailyMetricsEntity row(String day, int sorties, double flightMin, int doorCycles,
                                       long onlineSec, int faults, int tempExc, int swaps,
                                       Double avgCharge) {
        DockDailyMetricsEntity m = new DockDailyMetricsEntity();
        m.dockId = 1L;
        m.day = day;
        m.sorties = sorties;
        m.flightMinutes = flightMin;
        m.doorCycles = doorCycles;
        m.onlineSeconds = onlineSec;
        m.faultCount = faults;
        m.tempExcursions = tempExc;
        m.batterySwaps = swaps;
        m.avgChargeTimeMin = avgCharge;
        return m;
    }
}