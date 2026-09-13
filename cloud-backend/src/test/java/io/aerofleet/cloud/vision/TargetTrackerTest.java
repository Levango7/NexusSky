package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.vision.TargetTracker.Observation;
import io.aerofleet.cloud.vision.TargetTracker.Track;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TargetTracker unit tests: association, gating, lifecycle, prediction.
 * Pure logic - no network, no Spring.
 */
class TargetTrackerTest {

    private static final double LAT = 22.5907;
    private static final double LON = 113.9345;
    /** 0.001 deg lat ~ 111.3 m: handy step for tests. */
    private static final double STEP = 0.0002;   // ~22.3 m lat (inside the 30 m gate)
    /** Absolute-ish base so the tracker's wall-clock aging works naturally. */
    private static final long T0 = System.currentTimeMillis() - 10_000;

    private final TargetTracker tracker = new TargetTracker();

    @Test
    void singleDetectionOpensTrack() {
        List<Track> out = tracker.ingest(1, List.of(new Observation(1, T0 + 1000, LAT, LON, "vehicle")));
        assertEquals(1, out.size());
        assertEquals(1, out.get(0).hits);
        assertEquals(TargetTracker.TrackState.ACTIVE, out.get(0).state);
        assertEquals("vehicle", out.get(0).last.kind);
    }

    @Test
    void movingTargetStaysOneTrack() {
        // 3 shots, ~55m apart, 1s apart: well within the gate per step
        tracker.ingest(1, List.of(new Observation(1, T0, LAT, LON, "vehicle")));
        tracker.ingest(1, List.of(new Observation(2, T0 + 1000, LAT + STEP, LON, "vehicle")));
        List<Track> out = tracker.ingest(1, List.of(new Observation(3, T0 + 2000, LAT + 2 * STEP, LON, "vehicle")));
        assertEquals(1, out.size(), "one continuous track");
        assertEquals(3, out.get(0).hits);
    }

    @Test
    void farDetectionOpensNewTrack() {
        tracker.ingest(1, List.of(new Observation(1, T0, LAT, LON, "vehicle")));
        // 0.01 deg lat ~ 1.1 km: way outside the 30 m gate
        List<Track> out = tracker.ingest(1, List.of(
                new Observation(2, T0 + 1000, LAT, LON, "vehicle"),
                new Observation(2, T0 + 1000, LAT + 0.01, LON, "vehicle")));
        assertEquals(2, out.size(), "far detection must open a new track");
    }

    @Test
    void twoSimultaneousTargetsBothTracked() {
        tracker.ingest(1, List.of(
                new Observation(1, T0, LAT, LON, "vehicle"),
                new Observation(1, T0, LAT + 0.004, LON + 0.004, "pedestrian")));
        List<Track> out = tracker.ingest(1, List.of(
                new Observation(2, T0 + 1000, LAT + STEP, LON, "vehicle"),
                new Observation(2, T0 + 1000, LAT + 0.004, LON + 0.004, "pedestrian")));
        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(t -> t.hits == 2), "both updated");
    }

    @Test
    void predictionExtrapolatesConstantVelocity() {
        tracker.ingest(1, List.of(new Observation(1, T0, LAT, LON, "vehicle")));
        tracker.ingest(1, List.of(new Observation(2, T0 + 1000, LAT + STEP, LON, "vehicle")));
        Track t = tracker.tracksOf(1).get(0);
        // predict 1s past the last hit: another STEP north
        double[] p = t.predictLatLon(T0 + 2000);
        assertEquals(LAT + 2 * STEP, p[0], 1e-9, "constant-velocity lat");
        assertEquals(LON, p[1], 1e-9);
    }

    @Test
    void predictionWithoutHistoryIsLastPosition() {
        tracker.ingest(1, List.of(new Observation(1, T0, LAT, LON, "static")));
        Track t = tracker.tracksOf(1).get(0);
        double[] p = t.predictLatLon(T0 + 5000);
        assertEquals(LAT, p[0], 1e-12);
        assertEquals(LON, p[1], 1e-12);
    }

    @Test
    void distanceSanity() {
        assertEquals(0, TargetTracker.distanceM(LAT, LON, LAT, LON), 1e-9);
        double d = TargetTracker.distanceM(LAT, LON, LAT + 0.001, LON);
        assertEquals(111.32, d, 0.5, "0.001 deg lat ~ 111.3 m");
    }

    @Test
    void dronesAreIsolated() {
        tracker.ingest(1, List.of(new Observation(1, T0, LAT, LON, "vehicle")));
        List<Track> out = tracker.ingest(2, List.of(new Observation(1, T0, LAT + STEP, LON, "vehicle")));
        assertEquals(1, out.size(), "sysid 2 has its own namespace");
        assertEquals(1, tracker.tracksOf(1).size());
        assertEquals(1, tracker.tracksOf(2).size());
    }
}
