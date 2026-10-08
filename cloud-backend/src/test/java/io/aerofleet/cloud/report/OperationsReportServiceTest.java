package io.aerofleet.cloud.report;

import io.aerofleet.cloud.flightlog.FlightLogEntity;
import io.aerofleet.cloud.flightlog.FlightLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** E6 运营报表：架次分割（R1/R2）+ 三维聚合（R3）+ 成本口径。 */
@DisplayName("OperationsReportService — 架次分割与聚合")
class OperationsReportServiceTest {

    private FlightLogRepository flightLog;
    private OperationsReportService svc;

    @BeforeEach
    void setUp() {
        flightLog = mock(FlightLogRepository.class);
        svc = new OperationsReportService(flightLog, 60.0);   // 60 元/小时，成本可断言
        when(flightLog.findByTypeAndTimestampBetweenOrderByTimestampAscIdAsc(
                any(), any(), any())).thenReturn(new ArrayList<>());
    }

    // ------------------------------------------------------------------
    // 架次分割（纯函数）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("单架次：armed 段聚合时长/里程/能耗/主 mode")
    void singleSortie() {
        // 3 帧 armed，0.001° 纬度差 ≈ 111m × 2 段；电量 90→80
        List<FlightLogEntity> frames = List.of(
                frame(0, 22.590, 113.930, 90, true, "MISSION"),
                frame(1000, 22.5905, 113.930, 85, true, "MISSION"),
                frame(2000, 22.591, 113.930, 80, true, "MISSION"),
                frame(3000, 22.591, 113.930, 80, false, "STANDBY"));

        List<OperationsReportService.Sortie> out = OperationsReportService.splitSorties(9, frames);

        assertThat(out).hasSize(1);
        OperationsReportService.Sortie s = out.get(0);
        assertThat(s.sysid()).isEqualTo(9);
        assertThat(s.minutes()).isEqualTo(0.033, org.assertj.core.api.Assertions.within(0.001)); // 首末帧差（round 3 位）
        assertThat(s.distanceKm()).isGreaterThan(0.1);        // ~111m（0.0005°×2 段）
        assertThat(s.batteryUsed()).isEqualTo(10);
        assertThat(s.mainMode()).isEqualTo("MISSION");
    }

    @Test
    @DisplayName("两次 arm/disarm = 两个架次")
    void twoSorties() {
        List<FlightLogEntity> frames = List.of(
                frame(0, 22.59, 113.93, 90, true, "MISSION"),
                frame(1000, 22.59, 113.93, 88, true, "MISSION"),
                frame(2000, 22.59, 113.93, 88, false, "STANDBY"),
                frame(10_000, 22.59, 113.93, 87, true, "MISSION"),
                frame(11_000, 22.59, 113.93, 85, true, "MISSION"),
                frame(12_000, 22.59, 113.93, 85, false, "STANDBY"));

        assertThat(OperationsReportService.splitSorties(9, frames)).hasSize(2);
    }

    @Test
    @DisplayName("断档 >30s 切分（数据粘连防护）")
    void gapSplits() {
        List<FlightLogEntity> frames = List.of(
                frame(0, 22.59, 113.93, 90, true, "MISSION"),
                frame(1000, 22.59, 113.93, 88, true, "MISSION"),
                frame(40_000, 22.59, 113.93, 80, true, "MISSION"),   // 39s 间隔 > 30s
                frame(41_000, 22.59, 113.93, 78, true, "MISSION"));

        assertThat(OperationsReportService.splitSorties(9, frames)).hasSize(2);
    }

    @Test
    @DisplayName("充电段（电量回升）能耗 clamp 0")
    void batteryGainClamped() {
        List<FlightLogEntity> frames = List.of(
                frame(0, 22.59, 113.93, 50, true, "MISSION"),
                frame(1000, 22.59, 113.93, 60, true, "MISSION"),   // 量回升（异常/换电）
                frame(2000, 22.59, 113.93, 60, false, "STANDBY"));

        List<OperationsReportService.Sortie> out = OperationsReportService.splitSorties(9, frames);
        assertThat(out.get(0).batteryUsed()).isEqualTo(0);
    }

    @Test
    @DisplayName("全 disarm 帧序列 = 0 架次")
    void noSortiesWhenDisarmed() {
        List<FlightLogEntity> frames = List.of(
                frame(0, 22.59, 113.93, 90, false, "STANDBY"),
                frame(1000, 22.59, 113.93, 90, false, "STANDBY"));
        assertThat(OperationsReportService.splitSorties(9, frames)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 聚合与成本（R3）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("报表汇总：成本 = 时长(小时) × costPerHour，窗口兜底校验")
    void reportAggregates() {
        // 1 架次 2 分钟 → 成本 60/30 = 2 元
        // 13 帧、每帧 10s（间隔 < 30s 断档阈值不切分），首末 120s = 2 分钟整段
        List<FlightLogEntity> frames = new ArrayList<>();
        for (long t = 0; t <= 120_000; t += 10_000) {
            frames.add(frame(t, 22.59 + t / 1e9, 113.93, t == 0 ? 90 : 80, true, "MISSION"));
        }
        frames.add(frame(121_000, 22.59, 113.93, 80, false, "STANDBY"));
        when(flightLog.findByTypeAndTimestampBetweenOrderByTimestampAscIdAsc(
                ArgumentMatchers.eq("telemetry"), any(), any())).thenReturn(frames);

        Map<String, Object> out = svc.report(Instant.ofEpochMilli(0),
                Instant.ofEpochMilli(200_000), "drone");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("sorties")).isEqualTo(1);
        assertThat((Double) summary.get("flightMinutes")).isEqualTo(2.0);
        assertThat(summary.get("batteryUsedPct")).isEqualTo(10);
        assertThat(summary.get("cost")).isEqualTo(2.0);   // 2min × 60元/h
        assertThat(out.get("costPerHour")).isEqualTo(60.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) out.get("topSorties");
        assertThat(top).hasSize(1);
    }

    @Test
    @DisplayName("空窗口：结构完整、全 0")
    void emptyWindowShape() {
        Map<String, Object> out = svc.report(Instant.ofEpochMilli(0),
                Instant.ofEpochMilli(1000), "day");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("sorties")).isEqualTo(0);
        assertThat(summary.get("cost")).isEqualTo(0.0);
    }

    // ------------------------------------------------------------------

    /** 造一帧 telemetry（epochMs 起）。 */
    private static FlightLogEntity frame(long epochMs, double lat, double lon,
                                         int battery, boolean armed, String mode) {
        FlightLogEntity f = new FlightLogEntity();
        f.setTimestamp(Instant.ofEpochMilli(epochMs));
        f.setType("telemetry");
        f.setSysid(9);
        f.setLat(lat);
        f.setLon(lon);
        f.setBattery(battery);
        f.setArmed(armed);
        f.setMode(mode);
        f.setOnline(true);
        return f;
    }
}
