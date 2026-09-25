package io.aerofleet.sim.gnss;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GnssSimulatorTest {

    private GnssSimulator simulator;

    @BeforeEach
    void setUp() {
        simulator = new GnssSimulator(40.0, 116.0, 50.0);
    }

    @Test
    void testConstellationInitialization() {
        assertEquals(31 + 24 + 30 + 35, simulator.totalSatellites);
        GnssConstellation gps = GnssConstellation.GPS;
        assertEquals(31, gps.satelliteCount);
        assertEquals(1575.42, gps.frequencyMHz, 0.01);
        assertTrue(gps.containsPrn(1));
        assertTrue(gps.containsPrn(32));
        assertFalse(gps.containsPrn(33));

        GnssConstellation glonass = GnssConstellation.GLONASS;
        assertTrue(glonass.containsPrn(33));
        assertTrue(glonass.containsPrn(56));

        GnssConstellation galileo = GnssConstellation.GALILEO;
        assertTrue(galileo.containsPrn(57));
        assertTrue(galileo.containsPrn(86));

        GnssConstellation beidou = GnssConstellation.BEIDOU;
        assertTrue(beidou.containsPrn(87));
        assertTrue(beidou.containsPrn(121));
    }

    @Test
    void testPrnToConstellationMapping() {
        assertEquals(GnssConstellation.GPS, GnssConstellation.fromPrn(15));
        assertEquals(GnssConstellation.GLONASS, GnssConstellation.fromPrn(40));
        assertEquals(GnssConstellation.GALILEO, GnssConstellation.fromPrn(70));
        assertEquals(GnssConstellation.BEIDOU, GnssConstellation.fromPrn(100));
        assertNull(GnssConstellation.fromPrn(200));
    }

    @Test
    void testSatelliteVisibility() {
        simulator.tick(0.1);
        assertTrue(simulator.visibleSatellites >= 4,
                "Should have at least 4 visible satellites, got " + simulator.visibleSatellites);
        assertTrue(simulator.visibleSatellites <= simulator.totalSatellites);
    }

    @Test
    void testSatellitePositionNotZero() {
        GnssSatellite sat = simulator.getSatellites().get(0);
        double[] ecef = sat.ecefPosition();
        assertTrue(Math.abs(ecef[0]) > 1e6 || Math.abs(ecef[1]) > 1e6 || Math.abs(ecef[2]) > 1e6,
                "Satellite ECEF position should be far from origin");
        double radius = Math.sqrt(ecef[0] * ecef[0] + ecef[1] * ecef[1] + ecef[2] * ecef[2]);
        double expectedRadius = (sat.constellation.orbitAltitudeKm + 6371.0) * 1000.0;
        assertEquals(expectedRadius, radius, 1000.0);
    }

    @Test
    void testDopComputation() {
        simulator.tick(0.1);
        assertTrue(simulator.currentHdop > 0 && simulator.currentHdop < 99,
                "HDOP should be valid, got " + simulator.currentHdop);
        assertTrue(simulator.currentVdop > 0 && simulator.currentVdop < 99,
                "VDOP should be valid, got " + simulator.currentVdop);
        assertTrue(simulator.currentPdop > 0 && simulator.currentPdop < 99,
                "PDOP should be valid, got " + simulator.currentPdop);
        assertTrue(simulator.currentPdop >= simulator.currentHdop,
                "PDOP should be >= HDOP");
    }

    @Test
    void testSingleModeAccuracy() {
        simulator.tick(0.1);
        assertEquals(GnssReceiver.FixMode.SINGLE, simulator.currentFixMode);
        assertTrue(simulator.currentPositionAccuracyM >= GnssReceiver.ACCURACY_SINGLE_MIN,
                "Single mode accuracy should be >= 2m, got " + simulator.currentPositionAccuracyM);
        assertTrue(simulator.currentPositionAccuracyM <= GnssReceiver.ACCURACY_SINGLE_MAX,
                "Single mode accuracy should be <= 5m, got " + simulator.currentPositionAccuracyM);
    }

    @Test
    void testRtkFixedAccuracy() {
        RtkBaseStation base = new RtkBaseStation(40.001, 116.001, 50.0);
        simulator.setRtkBaseStation(base);
        simulator.tick(0.1);
        simulator.enableRtkFixed();
        simulator.tick(0.1);
        assertEquals(GnssReceiver.FixMode.RTK_FIXED, simulator.currentFixMode);
        assertEquals(GnssReceiver.ACCURACY_RTK_FIXED, simulator.currentPositionAccuracyM, 0.001,
                "RTK fixed accuracy should be 2cm");
    }

    @Test
    void testRtkFloatAccuracy() {
        RtkBaseStation base = new RtkBaseStation(40.001, 116.001, 50.0);
        simulator.setRtkBaseStation(base);
        simulator.tick(0.1);
        simulator.enableRtkFloat();
        simulator.tick(0.1);
        assertEquals(GnssReceiver.FixMode.RTK_FLOAT, simulator.currentFixMode);
        assertEquals(GnssReceiver.ACCURACY_RTK_FLOAT, simulator.currentPositionAccuracyM, 0.001,
                "RTK float accuracy should be 50cm");
    }

    @Test
    void testRtkFixedBetterThanSingle() {
        simulator.tick(0.1);
        double singleAccuracy = simulator.currentPositionAccuracyM;

        RtkBaseStation base = new RtkBaseStation(40.001, 116.001, 50.0);
        GnssSimulator rtkSim = new GnssSimulator(40.0, 116.0, 50.0);
        rtkSim.setRtkBaseStation(base);
        rtkSim.tick(0.1);
        rtkSim.enableRtkFixed();
        rtkSim.tick(0.1);
        double rtkAccuracy = rtkSim.currentPositionAccuracyM;

        assertTrue(rtkAccuracy < singleAccuracy,
                "RTK fixed (" + rtkAccuracy + ") should be more accurate than single (" + singleAccuracy + ")");
    }

    @Test
    void testJammingReducesVisibleSatellites() {
        simulator.tick(0.1);
        int normalVisible = simulator.visibleSatellites;

        simulator.injectJamming(true);
        simulator.tick(0.1);
        int jammedVisible = simulator.visibleSatellites;

        assertTrue(jammedVisible <= normalVisible,
                "Jamming should reduce or equal visible satellites: normal=" + normalVisible
                        + " jammed=" + jammedVisible);
        assertTrue(jammedVisible < simulator.totalSatellites);
    }

    @Test
    void testJammingDegradesAccuracy() {
        simulator.tick(0.1);
        double normalAccuracy = simulator.currentPositionAccuracyM;

        simulator.injectJamming(true);
        simulator.tick(0.1);
        double jammedAccuracy = simulator.currentPositionAccuracyM;

        assertTrue(jammedAccuracy > normalAccuracy,
                "Jamming should degrade accuracy: normal=" + normalAccuracy + " jammed=" + jammedAccuracy);
    }

    @Test
    void testSpoofingRedirectsPosition() {
        simulator.tick(0.1);
        double normalLat = simulator.getReportedLat();
        double normalLon = simulator.getReportedLon();

        simulator.injectSpoofing(true, 35.0, 120.0);
        simulator.tick(0.1);
        double spoofedLat = simulator.getReportedLat();
        double spoofedLon = simulator.getReportedLon();

        assertNotEquals(normalLat, spoofedLat, 0.01);
        assertNotEquals(normalLon, spoofedLon, 0.01);
        assertEquals(35.0, spoofedLat, 0.01);
        assertEquals(120.0, spoofedLon, 0.01);
    }

    @Test
    void testRtkBaseStationCorrections() {
        RtkBaseStation base = new RtkBaseStation(40.001, 116.001, 50.0);
        assertTrue(base.isCorrectionValid());
        assertTrue(base.broadcasting);

        base.tick(0.1);
        assertEquals(0.0, base.correctionAgeSec, 0.01);

        base.stopBroadcasting();
        assertFalse(base.broadcasting);
        base.tick(1.0);
        assertTrue(base.correctionAgeSec > 0);

        base.startBroadcasting();
        assertTrue(base.broadcasting);
        assertEquals(0.0, base.correctionAgeSec, 0.01);
    }

    @Test
    void testRtkCorrectionsLostDegradesToSingle() {
        RtkBaseStation base = new RtkBaseStation(40.001, 116.001, 50.0);
        simulator.setRtkBaseStation(base);
        simulator.tick(0.1);
        simulator.enableRtkFixed();
        simulator.tick(0.1);
        assertEquals(GnssReceiver.FixMode.RTK_FIXED, simulator.currentFixMode);

        base.stopBroadcasting();
        for (int i = 0; i < 100; i++) {
            simulator.tick(0.1);
        }
        assertEquals(GnssReceiver.FixMode.SINGLE, simulator.currentFixMode,
                "Should degrade to single when RTK corrections lost");
    }

    @Test
    void testGpsFixTypeMapping() {
        simulator.tick(0.1);
        assertEquals(3, simulator.getGpsFixType());

        RtkBaseStation base = new RtkBaseStation(40.001, 116.001, 50.0);
        simulator.setRtkBaseStation(base);
        simulator.tick(0.1);
        simulator.enableRtkFloat();
        simulator.tick(0.1);
        assertEquals(5, simulator.getGpsFixType());

        simulator.enableRtkFixed();
        simulator.tick(0.1);
        assertEquals(6, simulator.getGpsFixType());
    }

    @Test
    void testConstellationBreakdown() {
        simulator.tick(0.1);
        int[] counts = simulator.getConstellationCounts();
        assertEquals(4, counts.length);
        int total = counts[0] + counts[1] + counts[2] + counts[3];
        assertEquals(simulator.visibleSatellites, total,
                "Sum of constellation counts should equal total visible");
    }

    @Test
    void testSatelliteTickAdvancesPhase() {
        GnssSatellite sat = simulator.getSatellites().get(0);
        double initialPhase = sat.phaseRad;
        sat.tick(1.0);
        assertTrue(sat.phaseRad != initialPhase,
                "Satellite phase should advance after tick");
    }

    @Test
    void testBaselineLength() {
        RtkBaseStation base = new RtkBaseStation(40.0, 116.0, 50.0);
        double baseline = base.baselineLengthKm(40.001, 116.001);
        assertTrue(baseline > 0 && baseline < 1.0,
                "Baseline for nearby point should be small, got " + baseline);
    }

    @Test
    void testElevationMask() {
        GnssReceiver receiver = new GnssReceiver(40.0, 116.0, 50.0);
        GnssSatellite sat = new GnssSatellite(1, GnssConstellation.GPS, 55.0, 0.0, 0.0);
        receiver.addSatellite(sat);
        List<GnssSatellite> visible = receiver.computeVisibleSatellites();
        for (GnssSatellite v : visible) {
            double elev = v.elevationDeg(Math.toRadians(40.0), Math.toRadians(116.0), 50.0);
            assertTrue(elev >= GnssReceiver.ELEVATION_MASK_DEG,
                    "All visible satellites should be above elevation mask");
        }
    }

    @Test
    void testMultipleTicksStable() {
        for (int i = 0; i < 100; i++) {
            simulator.tick(0.05);
        }
        assertTrue(simulator.visibleSatellites >= 4);
        assertTrue(simulator.currentHdop < 99);
        assertTrue(simulator.currentPositionAccuracyM > 0);
    }
}