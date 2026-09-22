package io.aerofleet.cloud.mission.spray;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SprayTask 喷洒任务规划单测（FR-13~FR-15）。
 * <p>
 * 覆盖航点切段、段长和=总长、totalArea、查询字段、状态推进。
 */
class SprayTaskTest {

    // ---- FR-13 3航点→2段 ----

    @Test
    void threeWaypointsProduceTwoSegments() {
        // 3 个航点 → 2 段
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0},   // ~111m 向北
                new double[]{30.001, 120.001}   // ~85m 向东
        );
        SprayTask task = new SprayTask(1, 1, waypoints, 100, 10000, 5.0);
        assertEquals(2, task.segmentCount(), "3 waypoints → 2 segments");
        assertEquals(1, task.taskId());
        assertEquals(1, task.targetSysid());
    }

    // ---- FR-13 段长和=总长 ----

    @Test
    void segmentLengthsSumToTotalLength() {
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0},
                new double[]{30.001, 120.001}
        );
        SprayTask task = new SprayTask(1, 1, waypoints, 100, 10000, 5.0);
        List<SpraySegment> segs = task.segments();
        double sumLen = segs.stream().mapToDouble(SpraySegment::segmentLengthM).sum();
        double totalArea = task.totalArea();
        // totalArea = totalLen × sprayWidth → totalLen = totalArea / sprayWidth
        assertEquals(sumLen, totalArea / 5.0, 1e-6, "sum of segment lengths = totalLen");
    }

    // ---- FR-13 totalArea = totalLen × sprayWidth ----

    @Test
    void totalAreaEqualsTotalLenTimesWidth() {
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0}   // ~111m
        );
        double sprayWidth = 5.0;
        SprayTask task = new SprayTask(1, 1, waypoints, 100, 10000, sprayWidth);
        double segLen = task.segments().get(0).segmentLengthM();
        assertEquals(segLen * sprayWidth, task.totalArea(), 1e-6);
    }

    // ---- FR-13 段索引连续 ----

    @Test
    void segmentIndicesAreSequential() {
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0},
                new double[]{30.002, 120.0},
                new double[]{30.003, 120.0}
        );
        SprayTask task = new SprayTask(1, 1, waypoints, 100, 10000, 5.0);
        List<SpraySegment> segs = task.segments();
        for (int i = 0; i < segs.size(); i++) {
            assertEquals(i, segs.get(i).index(), "segment index should be sequential");
        }
    }

    // ---- FR-15 查询字段 ----

    @Test
    void queryFieldsReturnConstructorValues() {
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0}
        );
        SprayTask task = new SprayTask(42, 7, waypoints, 150, 8000, 4.0);
        assertEquals(42, task.taskId());
        assertEquals(7, task.targetSysid());
        assertEquals(150, task.targetRate());
        assertEquals(8000, task.capacityMl());
        assertEquals(4.0, task.sprayWidth(), 1e-9);
        assertEquals(8000, task.remainingChemical(), 1e-9, "initial remaining = capacity");
        assertEquals(0.0, task.coveredArea(), 1e-9, "initial covered = 0");
    }

    @Test
    void coverageRateInitiallyZero() {
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0}
        );
        SprayTask task = new SprayTask(1, 1, waypoints, 100, 10000, 5.0);
        assertEquals(0.0, task.coverageRate(), 1e-9);
        assertEquals(100.0, task.chemicalPercent(), 1e-9, "initial chemical = 100%");
    }

    // ---- FR-14 状态推进 ----

    @Test
    void stateTransitions() {
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0}
        );
        SprayTask task = new SprayTask(1, 1, waypoints, 100, 10000, 5.0);
        assertEquals(SprayTask.State.PENDING, task.state());

        task.start();
        assertEquals(SprayTask.State.RUNNING, task.state());

        task.pause();
        assertEquals(SprayTask.State.PAUSED, task.state());

        task.resume();
        assertEquals(SprayTask.State.RUNNING, task.state());

        task.stop();
        assertEquals(SprayTask.State.COMPLETED, task.state());
    }

    @Test
    void startFromNonPendingIsNoOp() {
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0}
        );
        SprayTask task = new SprayTask(1, 1, waypoints, 100, 10000, 5.0);
        task.start();
        task.start();  // second start is no-op
        assertEquals(SprayTask.State.RUNNING, task.state());
    }

    @Test
    void failOverridesState() {
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0}
        );
        SprayTask task = new SprayTask(1, 1, waypoints, 100, 10000, 5.0);
        task.start();
        task.fail();
        assertEquals(SprayTask.State.FAILED, task.state());
    }

    // ---- updateProgress ----

    @Test
    void updateProgressUpdatesFields() {
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0}
        );
        SprayTask task = new SprayTask(1, 1, waypoints, 100, 10000, 5.0);
        double totalArea = task.totalArea();
        task.updateProgress(totalArea / 2, 5000);
        assertEquals(totalArea / 2, task.coveredArea(), 1e-9);
        assertEquals(5000, task.remainingChemical(), 1e-9);
        assertEquals(50.0, task.coverageRate(), 1e-6, "50% coverage");
        assertEquals(50.0, task.chemicalPercent(), 1e-6, "50% chemical");
    }

    // ---- markSegmentComplete ----

    @Test
    void markSegmentCompleteAdvancesAndCompletes() {
        List<double[]> waypoints = List.of(
                new double[]{30.0, 120.0},
                new double[]{30.001, 120.0},
                new double[]{30.002, 120.0}
        );
        SprayTask task = new SprayTask(1, 1, waypoints, 100, 10000, 5.0);
        task.start();
        assertEquals(0, task.currentSegment());

        task.markSegmentComplete();
        assertEquals(1, task.currentSegment());
        assertEquals(SprayTask.State.RUNNING, task.state());

        task.markSegmentComplete();  // last segment → COMPLETED
        assertEquals(SprayTask.State.COMPLETED, task.state());
    }
}