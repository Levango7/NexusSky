package io.aerofleet.cloud.vision;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VisionSource + ProjectionVisionSource + SimulatedVisionSource 单测
 * （M3 感知成像增强，FR-01/FR-02/FR-03/FR-04/FR-37）。
 */
class VisionSourceTest {

    private static CameraShot shotWithTargets(CameraShot.ProjectedTarget... targets) {
        return new CameraShot(1L, System.currentTimeMillis(),
                22.59, 113.93, 50.0, 0, 0, 0, 0, 0,
                List.of(targets));
    }

    private static CameraShot emptyShot() {
        return new CameraShot(1L, System.currentTimeMillis(),
                22.59, 113.93, 50.0, 0, 0, 0, 0, 0, List.of());
    }

    private static CameraPose pose() {
        return new CameraPose(0, 0, 0, 0, 0);
    }

    // ---- FR-01 ProjectionVisionSource ----

    @Test
    void projectionVisionSourceReturnsTargets() {
        CameraShot shot = shotWithTargets(
                new CameraShot.ProjectedTarget(1, "vehicle", 960, 540, 22.591, 113.935),
                new CameraShot.ProjectedTarget(2, "person", 320, 200, 22.592, 113.936));
        ProjectionVisionSource src = new ProjectionVisionSource();
        List<VisionDetection> out = src.detect(shot, pose());
        assertEquals(2, out.size());
        assertEquals(960, out.get(0).u());
        assertEquals(540, out.get(0).v());
        assertEquals("vehicle", out.get(0).kind());
        assertEquals(1.0, out.get(0).confidence());  // 投影简化：confidence=1.0
        assertEquals(1, out.get(0).trackId());        // trackId=targetId
    }

    @Test
    void emptyShotReturnsEmptyList() {
        ProjectionVisionSource src = new ProjectionVisionSource();
        List<VisionDetection> out = src.detect(emptyShot(), pose());
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void nullShotReturnsEmptyList() {
        ProjectionVisionSource src = new ProjectionVisionSource();
        List<VisionDetection> out = src.detect(null, pose());
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    // ---- FR-02 数据契约校验 ----

    @Test
    void invalidConfidenceRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisionDetection(100, 100, "vehicle", 1.5, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VisionDetection(100, 100, "vehicle", -0.1, 1));
    }

    @Test
    void invalidKindRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisionDetection(100, 100, null, 0.9, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VisionDetection(100, 100, "", 0.9, 1));
    }

    @Test
    void invalidTrackIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisionDetection(100, 100, "vehicle", 0.9, -2));
    }

    @Test
    void validTrackIdMinusOneAccepted() {
        VisionDetection d = new VisionDetection(100, 100, "vehicle", 0.9, -1);
        assertEquals(-1, d.trackId());
    }

    // ---- FR-04 SimulatedVisionSource ----

    @Test
    void simulatedVisionSourceReturnsSyntheticConfidence() {
        CameraShot shot = shotWithTargets(
                new CameraShot.ProjectedTarget(1, "vehicle", 960, 540, 22.591, 113.935));
        SimulatedVisionSource src = new SimulatedVisionSource(new Random(42));
        List<VisionDetection> out = src.detect(shot, pose());
        assertEquals(1, out.size());
        double conf = out.get(0).confidence();
        assertTrue(conf >= 0.7 && conf <= 1.0,
                "confidence must be in [0.7, 1.0], got " + conf);
        assertEquals(1, out.get(0).trackId());
    }

    @Test
    void simulatedVisionSourceEmptyShotReturnsEmpty() {
        SimulatedVisionSource src = new SimulatedVisionSource();
        List<VisionDetection> out = src.detect(emptyShot(), pose());
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void simulatedVisionSourceMultipleTargetsAllInConfidenceRange() {
        CameraShot shot = shotWithTargets(
                new CameraShot.ProjectedTarget(1, "vehicle", 100, 100, 22.591, 113.935),
                new CameraShot.ProjectedTarget(2, "person", 200, 200, 22.592, 113.936),
                new CameraShot.ProjectedTarget(3, "animal", 300, 300, 22.593, 113.937));
        SimulatedVisionSource src = new SimulatedVisionSource(new Random(42));
        List<VisionDetection> out = src.detect(shot, pose());
        assertEquals(3, out.size());
        for (VisionDetection d : out) {
            assertTrue(d.confidence() >= 0.7 && d.confidence() <= 1.0,
                    "confidence must be in [0.7, 1.0], got " + d.confidence());
        }
    }
}