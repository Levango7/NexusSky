package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RF geometry tests (batch E1): distance monotonicity, terrain occlusion
 * penalty, unit conversion sanity. All deterministic.
 */
class RadioEnvironmentTest {

    private static RadioEnvironment flat() {
        return new RadioEnvironment(TerrainModel.flat(), 0, 0, 1.5);
    }

    @Test
    void rssiDecreasesWithDistance() {
        RadioEnvironment rf = flat();
        double near = rf.rssiDbm(100, 0, 50);
        double far = rf.rssiDbm(1000, 0, 50);
        double veryFar = rf.rssiDbm(5000, 0, 50);
        assertTrue(near > far, "100m must beat 1000m: " + near + " vs " + far);
        assertTrue(far > veryFar, "1000m must beat 5000m: " + far + " vs " + veryFar);
    }

    @Test
    void nearFieldIsStrong() {
        RadioEnvironment rf = flat();
        // 100 m @ 2.4 GHz: FSPL ~ 80 dB -> RSSI ~ -60 dBm
        double r = rf.rssiDbm(100, 0, 50);
        assertTrue(r > -70 && r < -50, "100m link should sit near -60 dBm, got " + r);
    }

    @Test
    void hillBehindGcsShadowsVehicle() {
        // Hill between home and a far vehicle: LOS broken -> penalty
        TerrainModel hill = TerrainModel.parse("hill:500:0:150:120");
        RadioEnvironment rf = new RadioEnvironment(hill, 0, 0, 1.5);
        // Vehicle 1200 m out, low altitude: chord passes through the hill
        double shadowed = rf.rssiDbm(1200, 0, 30);
        // Same geometry, flat world for comparison
        double clear = flat().rssiDbm(1200, 0, 30);
        assertTrue(shadowed < clear - RadioEnvironment.SHADOW_DB + 1,
                "occlusion must cost the full penalty: " + shadowed + " vs " + clear);
        assertTrue(rf.occluded(1200, 0, 30), "LOS report must agree with the penalty");
    }

    @Test
    void highAltitudeSeesOverTheHill() {
        // Geometry care: the chord from a 1.5 m GCS mast climbs slowly -
        // at the x~500 m hill it is still low. A 200 m vehicle at 1200 m
        // is STILL occluded by the near slope (chord ~75-95 m under a 119 m
        // hillside) - physically right! Climb to 400 m so the chord clears
        // the 120 m ridge by a wide margin, and the shadow must lift.
        TerrainModel hill = TerrainModel.parse("hill:500:0:150:120");
        RadioEnvironment rf = new RadioEnvironment(hill, 0, 0, 1.5);
        assertTrue(rf.occluded(1200, 0, 200),
                "low chord through a near hillside IS occluded (sanity)");
        assertFalse(rf.occluded(1200, 0, 400),
                "a 400 m chord must clear a 120 m hill");
        double high = rf.rssiDbm(1200, 0, 400);
        double clear = flat().rssiDbm(1200, 0, 400);
        assertEquals(clear, high, 0.5, "no shadow when LOS is clear");
    }

    @Test
    void sikUnitsRoundTripWithinRange() {
        // In-range dBm values survive the raw<->dBm mapping
        for (double dbm = -100; dbm <= -30; dbm += 5) {
            int raw = RadioEnvironment.toSikUnits(dbm);
            assertTrue(raw >= 0 && raw <= 254, "raw in [0,254]: " + raw);
            double back = RadioEnvironment.fromSikUnits(raw);
            assertEquals(dbm, back, 0.51, "round trip within quantization");
        }
    }

    @Test
    void sikUnitsClampOutOfRange() {
        assertEquals(0, RadioEnvironment.toSikUnits(-140));   // dead
        assertEquals(254, RadioEnvironment.toSikUnits(+30));  // saturated
    }
}
