package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Terrain and geofence model unit tests.
 */
class TerrainFenceTest {

    // ---- terrain ----

    @Test
    void flatWorldIsZero() {
        TerrainModel t = TerrainModel.flat();
        assertEquals(0, t.elevationAt(100, -200), 1e-9);
        assertFalse(t.collided(100, -200, 0));
        assertFalse(t.collided(100, -200, 0.6));   // half-meter guard band
    }

    @Test
    void hillPeakAtCenter() {
        TerrainModel t = TerrainModel.parse("hill:300:100:150:80");
        assertEquals(80, t.elevationAt(300, 100), 1e-6, "center = full height");
        double atHalfR = t.elevationAt(450, 100);   // one radius away
        assertEquals(80 * Math.exp(-0.5), atHalfR, 1e-6, "one radius = exp(-0.5) of peak");
        assertEquals(0, t.elevationAt(-500, -500), 1e-6, "far away: back to 0");
    }

    @Test
    void collisionDetectsUnderside() {
        TerrainModel t = TerrainModel.parse("hill:0:0:100:60");
        assertFalse(t.collided(0, 0, 60), "exactly on the peak surface: safe");
        assertTrue(t.collided(0, 0, 55), "5 m under the peak: collided");
        assertFalse(t.collided(0, 0, 100), "well above: safe");
        assertFalse(t.collided(2000, 2000, 10), "flat area far away: safe");
    }

    @Test
    void malformedSegmentsSkipped() {
        TerrainModel t = TerrainModel.parse("hill:0:0:100:60,bogus,x:y");
        assertEquals(1, t.hills().size(), "one valid hill kept, junk skipped");
    }

    // ---- geofence ----

    @Test
    void disabledFenceAllowsAll() {
        GeoFence f = GeoFence.disabled();
        assertNull(f.violationAt(1e9, 1e9, 1e9));
        assertTrue(f.contains(5000, 5000));
        assertFalse(f.enabled());
    }

    @Test
    void squareFenceContainsAndExcludes() {
        GeoFence f = GeoFence.parse("-600,-600:600,-600:600,600:-600,600:120");
        assertTrue(f.contains(0, 0), "home is inside");
        assertTrue(f.contains(599, 599), "near the edge inside");
        assertFalse(f.contains(601, 0), "just outside laterally");
        assertFalse(f.contains(0, -601), "south outside");
        assertNull(f.violationAt(0, 0, 100), "inside + under ceiling");
        assertEquals(GeoFence.Violation.CEILING, f.violationAt(0, 0, 121),
                "above the ceiling");
        assertEquals(GeoFence.Violation.LATERAL, f.violationAt(700, 0, 50),
                "outside the polygon");
    }

    @Test
    void concavePolygonHandled() {
        // L-shape (concave): the notch area must be OUTSIDE.
        GeoFence f = GeoFence.parse("-100,-100:100,-100:100,0:0,0:0,100:-100,100");
        assertTrue(f.contains(50, -50), "inside the bottom arm");
        assertTrue(f.contains(-50, 50), "inside the left arm");
        assertFalse(f.contains(50, 50), "inside the notch corner: outside");
    }

    @Test
    void badSpecFailsOpen() {
        GeoFence f = GeoFence.parse("1,2:3");
        assertFalse(f.enabled(), "unparseable fence disables itself");
        assertNull(f.violationAt(9999, 9999, 9999));
    }
}
