package io.aerofleet.sim.gnss;

import java.util.concurrent.ThreadLocalRandom;

public final class RtkBaseStation {

    public volatile double baseLatDeg;
    public volatile double baseLonDeg;
    public volatile double baseAltM;
    public volatile boolean broadcasting;
    public volatile double correctionAgeSec;
    public volatile int correctionMsgRateHz;

    private double accumulatedClockError;
    private double ionosphericDelay;

    public RtkBaseStation(double latDeg, double lonDeg, double altM) {
        this.baseLatDeg = latDeg;
        this.baseLonDeg = lonDeg;
        this.baseAltM = altM;
        this.broadcasting = true;
        this.correctionAgeSec = 0.0;
        this.correctionMsgRateHz = 1;
        this.accumulatedClockError = 0.0;
        this.ionosphericDelay = 5.0 + ThreadLocalRandom.current().nextDouble(-1.0, 1.0);
    }

    public void tick(double dt) {
        if (!broadcasting) {
            correctionAgeSec += dt;
            return;
        }
        correctionAgeSec = 0.0;
        accumulatedClockError += ThreadLocalRandom.current().nextDouble(-0.001, 0.001) * dt;
    }

    public double[] generateCorrections(GnssSatellite sat, double roverLatDeg, double roverLonDeg) {
        double baseLatRad = Math.toRadians(baseLatDeg);
        double baseLonRad = Math.toRadians(baseLonDeg);
        double roverLatRad = Math.toRadians(roverLatDeg);
        double roverLonRad = Math.toRadians(roverLonDeg);

        double baseRange = sat.pseudoRange(baseLatRad, baseLonRad, baseAltM);
        double roverRange = sat.pseudoRange(roverLatRad, roverLonRad, 0.0);

        double ionoCorrection = ionosphericDelay * (1.0 - 0.3 * Math.exp(-Math.abs(roverLatDeg - baseLatDeg)));
        double tropoCorrection = 2.4 * Math.exp(-baseAltM / 8000.0);
        double clockCorrection = accumulatedClockError;
        double ephemerisCorrection = ThreadLocalRandom.current().nextDouble(-0.5, 0.5);

        double totalCorrection = ionoCorrection + tropoCorrection + clockCorrection + ephemerisCorrection;

        double correctedBaseRange = baseRange + totalCorrection;
        double correctedRoverRange = roverRange + totalCorrection * 0.95;

        double differentialCorrection = correctedBaseRange - baseRange;

        return new double[]{
                differentialCorrection,
                ionoCorrection,
                tropoCorrection,
                clockCorrection,
                ephemerisCorrection
        };
    }

    public boolean isCorrectionValid() {
        return broadcasting && correctionAgeSec < 5.0;
    }

    public double baselineLengthKm(double roverLatDeg, double roverLonDeg) {
        double dLat = Math.toRadians(roverLatDeg - baseLatDeg);
        double dLon = Math.toRadians(roverLonDeg - baseLonDeg);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(baseLatDeg)) * Math.cos(Math.toRadians(roverLatDeg))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371.0 * c;
    }

    public void startBroadcasting() {
        broadcasting = true;
        correctionAgeSec = 0.0;
    }

    public void stopBroadcasting() {
        broadcasting = false;
    }
}