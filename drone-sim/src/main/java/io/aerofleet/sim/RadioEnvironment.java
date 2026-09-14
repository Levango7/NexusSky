package io.aerofleet.sim;

/**
 * RF link geometry (batch E1): RSSI as a function of distance and terrain
 * occlusion between the GCS antenna (at home, mast height) and the vehicle.
 *
 * Model (documented honesty, not physics):
 *  - Free-space path loss at 2.4 GHz (channel 6, 2437 MHz):
 *      FSPL(dB) = 20*log10(d_m) + 20*log10(f_MHz) - 27.55
 *  - Received power = TX EIRP - FSPL (EIRP 20 dBm, typical 2.4 GHz module)
 *  - Terrain shadowing: the GCS->vehicle segment is sampled at 32 points;
 *    if any sample point's terrain elevation exceeds the straight line
 *    (chord) height by more than a Fresnel-ish margin, a fixed -20 dB
 *    deep-shadow penalty applies. Single-sample occlusion testing is the
 *    standard cheap LOS approximation; the margin models partial blockage.
 *
 * Deterministic by design: no random walk, so e2e RSSI assertions are
 * reproducible. The value reported is the DOWNLINK (vehicle -> GCS)
 * estimate; uplink is symmetric at this abstraction level.
 */
public final class RadioEnvironment {

    /** 2.4 GHz channel 6 center frequency [MHz]. */
    public static final double FREQ_MHZ = 2437;
    /** Transmit EIRP [dBm] (typical 2.4 GHz telemetry module). */
    public static final double TX_EIRP_DBM = 20;
    /** Deep-shadow penalty when the terrain breaks the line of sight [dB]. */
    public static final double SHADOW_DB = 20;
    /** Fresnel-ish clearance margin above the chord [m]. */
    public static final double CLEARANCE_M = 3.0;
    /** LOS segment sample count (occlusion resolution ~ d/32). */
    private static final int SAMPLES = 32;

    private final TerrainModel terrain;
    private final double gcsNorth;
    private final double gcsEast;
    private final double gcsAntennaM;

    /**
     * @param terrain terrain model (flat when --terrain absent)
     * @param gcsNorth GCS antenna position, local north [m]
     * @param gcsEast  GCS antenna position, local east [m]
     * @param gcsAntennaM antenna height above ground [m]
     */
    public RadioEnvironment(TerrainModel terrain,
                            double gcsNorth, double gcsEast, double gcsAntennaM) {
        this.terrain = terrain;
        this.gcsNorth = gcsNorth;
        this.gcsEast = gcsEast;
        this.gcsAntennaM = gcsAntennaM;
    }

    /** GCS antenna height above sea level (ground assumed 0 at home). */
    private double gcsAlt() {
        return terrain.elevationAt(gcsNorth, gcsEast) + gcsAntennaM;
    }

    /**
     * Estimated downlink RSSI [dBm] at the GCS for a vehicle at
     * (north, east, alt AMSL). Range-correct, terrain-shadowed,
     * deterministic.
     */
    public double rssiDbm(double north, double east, double alt) {
        double dx = north - gcsNorth;
        double dy = east - gcsEast;
        double dz = alt - gcsAlt();
        double distM = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distM < 1) {
            distM = 1;     // avoid log10(0) at the antenna itself
        }
        double fspl = 20 * Math.log10(distM) + 20 * Math.log10(FREQ_MHZ) - 27.55;
        double rssi = TX_EIRP_DBM - fspl;
        if (occluded(north, east, alt)) {
            rssi -= SHADOW_DB;
        }
        return rssi;
    }

    /** True when terrain breaks the GCS->vehicle line of sight. */
    public boolean occluded(double north, double east, double alt) {
        double aAlt = gcsAlt();
        for (int i = 1; i < SAMPLES; i++) {
            double t = (double) i / SAMPLES;
            double pN = gcsNorth + t * (north - gcsNorth);
            double pE = gcsEast + t * (east - gcsEast);
            double chordAlt = aAlt + t * (alt - aAlt);
            if (terrain.elevationAt(pN, pE) > chordAlt + CLEARANCE_M) {
                return true;
            }
        }
        return false;
    }

    /** RSSI in SiK-style raw units (approx 2x dB, per RADIO_STATUS spec). */
    public static int toSikUnits(double dbm) {
        // SiK convention: raw = clamp(2 * (100 + dbm), 0, 254); a 0 dBm signal
        // near the radio reads ~200, -60 dBm reads ~80. This mirrors the
        // "scale as approx 2x dB" note in the common.xml field docs.
        int raw = (int) Math.round(2 * (100 + dbm));
        return Math.max(0, Math.min(254, raw));
    }

    /** SiK raw units back to dBm (for tests / display). */
    public static double fromSikUnits(int raw) {
        return raw / 2.0 - 100;
    }
}
