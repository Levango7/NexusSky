package io.aerofleet.cloud.dock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 机巢利用率与运维健康度度量（spec R5）。
 * <p>
 * 数据源：{@code dock_state_log}（可用时长/开关门/故障/温度告警/换电/充电时长）
 * 与 {@code dock_run_log}（架次/飞行分钟，来自无人值守任务）。
 * 日结聚合幂等重算（回溯 metricsRollupDays 天），不从内存遥测现算；
 * 分母为零的比率返回 null（F1 口径沿用，不造假数）。
 */
@Service
public class DockMetricsService {

    private static final Logger log = LoggerFactory.getLogger(DockMetricsService.class);

    /** 利用率基准：8 小时/天。 */
    static final double DAILY_FLIGHT_BASELINE_MIN = 480.0;

    private final DockRepository docks;
    private final DockStateLogRepository stateLog;
    private final DockRunLogRepository runLog;
    private final DockDailyMetricsRepository daily;
    private final DockProperties props;

    public DockMetricsService(DockRepository docks, DockStateLogRepository stateLog,
                              DockRunLogRepository runLog, DockDailyMetricsRepository daily,
                              DockProperties props) {
        this.docks = docks;
        this.stateLog = stateLog;
        this.runLog = runLog;
        this.daily = daily;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // 日结重算（幂等）
    // ------------------------------------------------------------------

    /** 每小时对最近 N 天做一次幂等重算（含当天，随时修正）。 */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    @Transactional
    public void rollupScheduled() {
        try {
            rollup(props.getMetricsRollupDays());
        } catch (Exception e) {
            log.warn("dock metrics rollup failed: {}", e.getMessage());
        }
    }

    @Transactional
    public void rollup(int days) {
        ZoneId zone = ZoneId.systemDefault();
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < Math.max(1, days); i++) {
            dates.add(LocalDate.now(zone).minusDays(i));
        }
        for (DockEntity d : docks.findAll()) {
            for (LocalDate day : dates) {
                recomputeDay(d, day, zone);
            }
        }
    }

    void recomputeDay(DockEntity d, LocalDate day, ZoneId zone) {
        long dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        String dayStr = day.toString();

        List<DockStateLogEntity> logs = stateLog.findByDockIdAndTsBetweenOrderByTs(d.id, dayStart, dayEnd);

        DockDailyMetricsEntity m = daily.findByDockIdAndDay(d.id, dayStr)
                .orElseGet(() -> {
                    DockDailyMetricsEntity fresh = new DockDailyMetricsEntity();
                    fresh.dockId = d.id;
                    fresh.day = dayStr;
                    return fresh;
                });

        // 状态区间扫描：结合"dayStart 前最后一跳"得到当天起始状态
        DockState cursor = stateAt(d, dayStart);
        long cursorTs = dayStart;
        long onlineMs = 0;
        int doorCycles = 0;
        int faultCount = 0;
        int tempExcursions = 0;
        int batterySwaps = 0;
        double chargingMs = 0;
        int chargingStarts = 0;

        for (DockStateLogEntity e : logs) {
            long ts = Math.min(e.ts, dayEnd);
            if (cursor != DockState.OFFLINE) {
                onlineMs += Math.max(0, ts - cursorTs);
            }
            if (cursor == DockState.CHARGING || (cursor == DockState.EXCHANGING)) {
                chargingMs += Math.max(0, ts - cursorTs);
            }
            if (e.toState == DockState.CHARGING) {
                chargingStarts++;
            }
            switch (e.toState) {
                case OPENING -> doorCycles++;
                case FAULT -> faultCount++;
                case EXCHANGING -> batterySwaps++;
                default -> { /* 其余迁移不影响日结计数 */ }
            }
            if (e.reason != null && e.reason.startsWith("temp-warning:")) {
                tempExcursions++;
            }
            cursor = e.toState;
            cursorTs = ts;
        }
        if (cursor != DockState.OFFLINE) {
            onlineMs += Math.max(0, dayEnd - cursorTs);
        }
        if (cursor == DockState.CHARGING || cursor == DockState.EXCHANGING) {
            chargingMs += Math.max(0, dayEnd - cursorTs);
        }

        List<DockRunLogEntity> runs = runLog.findByDockIdAndStartedAtBetween(d.id, dayStart, dayEnd);
        int sorties = 0;
        double flightMinutes = 0;
        for (DockRunLogEntity r : runs) {
            if ("OK".equals(r.result)) {
                sorties++;
                flightMinutes += r.flightMinutes == null ? 0 : r.flightMinutes;
            }
        }

        m.onlineSeconds = onlineMs / 1000;
        m.doorCycles = doorCycles;
        m.faultCount = faultCount;
        m.tempExcursions = tempExcursions;
        m.batterySwaps = batterySwaps;
        m.sorties = sorties;
        m.flightMinutes = flightMinutes;
        m.avgChargeTimeMin = chargingStarts == 0 ? null
                : (chargingMs / chargingStarts) / 60_000.0;
        daily.save(m);
    }

    /** dayStart 时刻的状态：取之前最后一跳的目标态；无记录则视为 OFFLINE。 */
    DockState stateAt(DockEntity d, long dayStart) {
        List<DockStateLogEntity> before = stateLog.findByDockIdAndTsBetweenOrderByTs(d.id, 0, dayStart - 1);
        return before.isEmpty() ? DockState.OFFLINE : before.get(before.size() - 1).toState;
    }

    // ------------------------------------------------------------------
    // 查询聚合（spec R5 响应形状）
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> metrics(Long dockId, int days) {
        DockEntity d = docks.findById(dockId)
                .orElseThrow(() -> new IllegalArgumentException("unknown dock " + dockId));
        int window = Math.min(Math.max(days, 1), 90);
        ZoneId zone = ZoneId.systemDefault();
        // 查询前先重算当天：小时级定时重算有最长 1h 的滞后，
        // 而"今天的数字"是用户与 e2e 都会立刻查的——按需重算是幂等的，代价只有几行。
        recomputeDay(d, LocalDate.now(zone), zone);
        List<String> dayStrs = new ArrayList<>();
        for (int i = 0; i < window; i++) {
            dayStrs.add(LocalDate.now(zone).minusDays(i).toString());
        }
        List<DockDailyMetricsEntity> rows =
                daily.findByDockIdAndDayInOrderByDayDesc(dockId, dayStrs);

        long totalOnline = 0;
        int totalSorties = 0;
        double totalFlight = 0;
        int totalDoorCycles = 0;
        int totalFaults = 0;
        int totalTempExc = 0;
        int totalSwaps = 0;
        double chargeSum = 0;
        int chargeCount = 0;
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (DockDailyMetricsEntity r : rows) {
            totalOnline += r.onlineSeconds;
            totalSorties += r.sorties;
            totalFlight += r.flightMinutes;
            totalDoorCycles += r.doorCycles;
            totalFaults += r.faultCount;
            totalTempExc += r.tempExcursions;
            totalSwaps += r.batterySwaps;
            if (r.avgChargeTimeMin != null) {
                chargeSum += r.avgChargeTimeMin;
                chargeCount++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", r.day);
            row.put("sorties", r.sorties);
            row.put("flightMinutes", r.flightMinutes);
            row.put("doorCycles", r.doorCycles);
            row.put("onlineSeconds", r.onlineSeconds);
            row.put("faultCount", r.faultCount);
            row.put("tempExcursions", r.tempExcursions);
            row.put("batterySwaps", r.batterySwaps);
            row.put("avgChargeTimeMin", r.avgChargeTimeMin);
            perDay.add(row);
        }

        double windowSeconds = window * 86_400.0;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("days", window);
        summary.put("sorties", totalSorties);
        summary.put("flightMinutes", totalFlight);
        // 分母是窗口时长而非"有数据的行数"：缺失的天=不在线，不豁免
        summary.put("availabilityPct", round2(totalOnline / windowSeconds * 100));
        summary.put("utilizationPct",
                round2(totalFlight / (DAILY_FLIGHT_BASELINE_MIN * window) * 100));
        summary.put("doorCycles", totalDoorCycles);
        summary.put("faultCount", totalFaults);
        summary.put("tempExcursions", totalTempExc);
        summary.put("batterySwaps", totalSwaps);
        summary.put("avgChargeTimeMin",
                chargeCount == 0 ? null : round2(chargeSum / chargeCount));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dockId", dockId);
        body.put("sn", d.sn);
        body.put("state", d.state.name());
        body.put("summary", summary);
        body.put("perDay", perDay);
        return body;
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 供调度器/测试读取（未使用则保留为扩展点）。 */
    Optional<DockDailyMetricsEntity> todayRow(Long dockId, ZoneId zone) {
        return daily.findByDockIdAndDay(dockId, LocalDate.now(zone).toString());
    }

    static Instant now() {
        return Instant.now();
    }
}
