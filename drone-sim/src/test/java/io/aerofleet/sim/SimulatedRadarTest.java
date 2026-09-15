package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.ScanMode;
import io.aerofleet.mavlink.enums.TrackState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulatedRadar 单测（M4 硬件抽象，FR-01/FR-04/FR-05/FR-02）。
 */
class SimulatedRadarTest {

    private static final int SYSID = 1;

    private RadarScanConfig config(ScanMode mode, double range) {
        return new RadarScanConfig(SYSID, mode, 0, 60, 0, 10, range, 1000, true);
    }

    private SyntheticTarget target(int id, double north, double east, String kind) {
        return new SyntheticTarget(id, north, east, 0, 0, 0, 0, kind);
    }

    @Test
    void scanReturnsTargetsInRange() {
        SimulatedRadar radar = new SimulatedRadar();
        RadarScanConfig cfg = config(ScanMode.STARE, 1000);
        // 目标在正北 100m，波束指向 0°（正北），在覆盖范围内
        List<SyntheticTarget> targets = List.of(target(1, 100, 0, "vehicle"));
        List<RadarTargetReport> reports = radar.scan(cfg, targets);
        assertEquals(1, reports.size());
        assertEquals(1, reports.get(0).targetId());
        assertTrue(reports.get(0).distance() > 99 && reports.get(0).distance() < 101);
    }

    @Test
    void scanFiltersOutOfRange() {
        SimulatedRadar radar = new SimulatedRadar();
        RadarScanConfig cfg = config(ScanMode.STARE, 100);
        // 目标在 200m，超出探测距离 100m
        List<SyntheticTarget> targets = List.of(target(1, 200, 0, "vehicle"));
        List<RadarTargetReport> reports = radar.scan(cfg, targets);
        assertTrue(reports.isEmpty());
    }

    @Test
    void scanFiltersOutOfBeam() {
        SimulatedRadar radar = new SimulatedRadar();
        // STARE 模式波束指向 0°（正北），宽度 10° → 覆盖 -5°~5°
        RadarScanConfig cfg = config(ScanMode.STARE, 1000);
        // 目标在正东 100m → 方位角 90°，不在波束覆盖内
        List<SyntheticTarget> targets = List.of(target(1, 0, 100, "vehicle"));
        List<RadarTargetReport> reports = radar.scan(cfg, targets);
        assertTrue(reports.isEmpty());
    }

    @Test
    void emptyTargetsReturnsEmpty() {
        SimulatedRadar radar = new SimulatedRadar();
        RadarScanConfig cfg = config(ScanMode.STARE, 1000);
        List<RadarTargetReport> reports = radar.scan(cfg, List.of());
        assertNotNull(reports);
        assertTrue(reports.isEmpty());
    }

    @Test
    void rcsByTypeCorrect() {
        SimulatedRadar radar = new SimulatedRadar();
        RadarScanConfig cfg = config(ScanMode.STARE, 1000);
        List<SyntheticTarget> targets = List.of(
                target(1, 100, 0, "vehicle"),
                target(2, 100, 1, "person"));
        List<RadarTargetReport> reports = radar.scan(cfg, targets);
        assertEquals(2, reports.size());
        // vehicle → rcs=10
        RadarTargetReport r1 = reports.stream().filter(r -> r.targetId() == 1).findFirst().orElseThrow();
        assertEquals(10.0, r1.rcs(), 0.01);
        // person → rcs=-20
        RadarTargetReport r2 = reports.stream().filter(r -> r.targetId() == 2).findFirst().orElseThrow();
        assertEquals(-20.0, r2.rcs(), 0.01);
    }

    @Test
    void trackStateProgression() {
        SimulatedRadar radar = new SimulatedRadar();
        RadarScanConfig cfg = config(ScanMode.STARE, 1000);
        List<SyntheticTarget> targets = List.of(target(1, 100, 0, "vehicle"));
        // 首次探测 → DETECTED
        List<RadarTargetReport> r1 = radar.scan(cfg, targets);
        assertEquals(TrackState.DETECTED, r1.get(0).trackState());
        // 二次探测 → TRACKING
        List<RadarTargetReport> r2 = radar.scan(cfg, targets);
        assertEquals(TrackState.TRACKING, r2.get(0).trackState());
    }

    @Test
    void invalidDistanceRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RadarTargetReport(1, -1, 0, 0, 0, 0, 0, TrackState.DETECTED, 0));
    }

    @Test
    void invalidAzimRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new RadarTargetReport(1, 100, 400, 0, 0, 0, 0, TrackState.DETECTED, 0));
    }
}