package io.aerofleet.sim.gnss;

import io.aerofleet.sim.SimLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class GnssSimulator {

    public volatile double simLatDeg;
    public volatile double simLonDeg;
    public volatile double simAltM;
    public volatile double simTimeSec;
    public volatile boolean jammingActive;
    public volatile boolean spoofingActive;
    public volatile boolean rtkEnabled;
    public volatile GnssReceiver.FixMode currentFixMode;
    public volatile int totalSatellites;
    public volatile int visibleSatellites;
    public volatile double currentHdop;
    public volatile double currentVdop;
    public volatile double currentPdop;
    public volatile double currentPositionAccuracyM;
    public volatile double spoofedLatDeg;
    public volatile double spoofedLonDeg;

    private final List<GnssSatellite> satellites = new ArrayList<>();
    private final GnssReceiver receiver;
    private RtkBaseStation baseStation;

    public GnssSimulator(double latDeg, double lonDeg, double altM) {
        this.simLatDeg = latDeg;
        this.simLonDeg = lonDeg;
        this.simAltM = altM;
        this.simTimeSec = 0.0;
        this.receiver = new GnssReceiver(latDeg, lonDeg, altM);
        this.currentFixMode = GnssReceiver.FixMode.SINGLE;
        initializeConstellations();
    }

    private void initializeConstellations() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (GnssConstellation c : GnssConstellation.values()) {
            for (int prn = c.prnMin; prn <= c.prnMin + c.satelliteCount - 1; prn++) {
                double raanDeg = rng.nextDouble(0, 360);
                double phaseDeg = rng.nextDouble(0, 360);
                GnssSatellite sat = new GnssSatellite(prn, c, c.inclinationDeg, raanDeg, phaseDeg);
                satellites.add(sat);
                receiver.addSatellite(sat);
            }
        }
        totalSatellites = satellites.size();
        SimLog.info("GNSS simulator initialized: " + totalSatellites + " satellites across 4 constellations");
    }

    public void setRtkBaseStation(RtkBaseStation station) {
        this.baseStation = station;
        this.rtkEnabled = true;
        SimLog.info("RTK base station enabled at " + station.baseLatDeg + "," + station.baseLonDeg);
    }

    public void enableRtkFixed() {
        if (rtkEnabled && baseStation != null && baseStation.isCorrectionValid()) {
            receiver.fixMode = GnssReceiver.FixMode.RTK_FIXED;
            currentFixMode = GnssReceiver.FixMode.RTK_FIXED;
            SimLog.info("RTK fixed solution achieved");
        }
    }

    public void enableRtkFloat() {
        if (rtkEnabled) {
            receiver.fixMode = GnssReceiver.FixMode.RTK_FLOAT;
            currentFixMode = GnssReceiver.FixMode.RTK_FLOAT;
            SimLog.info("RTK float solution mode");
        }
    }

    public void setSingleMode() {
        receiver.fixMode = GnssReceiver.FixMode.SINGLE;
        currentFixMode = GnssReceiver.FixMode.SINGLE;
    }

    public void injectJamming(boolean active) {
        this.jammingActive = active;
        receiver.jammingActive = active;
        if (active) {
            SimLog.warn("GNSS jamming injected");
        }
    }

    public void injectSpoofing(boolean active, double spoofedLat, double spoofedLon) {
        this.spoofingActive = active;
        receiver.spoofingActive = active;
        this.spoofedLatDeg = spoofedLat;
        this.spoofedLonDeg = spoofedLon;
        if (active) {
            SimLog.warn("GNSS spoofing injected: fake position " + spoofedLat + "," + spoofedLon);
        }
    }

    public void tick(double dt) {
        simTimeSec += dt;

        for (GnssSatellite sat : satellites) {
            sat.tick(dt);
        }

        receiver.receiverLatDeg = simLatDeg;
        receiver.receiverLonDeg = simLonDeg;
        receiver.receiverAltM = simAltM;

        if (baseStation != null) {
            baseStation.tick(dt);
            if (baseStation.isCorrectionValid()) {
                receiver.setRtkCorrectionsReceived(true);
            } else {
                receiver.setRtkCorrectionsReceived(false);
                if (receiver.fixMode != GnssReceiver.FixMode.SINGLE) {
                    receiver.fixMode = GnssReceiver.FixMode.SINGLE;
                    currentFixMode = GnssReceiver.FixMode.SINGLE;
                    SimLog.warn("RTK corrections lost, degraded to single positioning");
                }
            }
        }

        receiver.solvePosition();

        visibleSatellites = receiver.satellitesVisible;
        currentHdop = receiver.hdop;
        currentVdop = receiver.vdop;
        currentPdop = receiver.pdop;
        currentPositionAccuracyM = receiver.positionAccuracyM;
        currentFixMode = receiver.fixMode;
    }

    public int getGpsFixType() {
        if (visibleSatellites < 4) {
            return 0;
        }
        switch (currentFixMode) {
            case SINGLE:
                return 3;
            case RTK_FLOAT:
                return 5;
            case RTK_FIXED:
                return 6;
            default:
                return 3;
        }
    }

    public int[] getConstellationCounts() {
        return receiver.constellationBreakdown();
    }

    public double getReportedLat() {
        if (spoofingActive) {
            return spoofedLatDeg;
        }
        double noiseRadius = currentPositionAccuracyM;
        if (noiseRadius <= 0) {
            return simLatDeg;
        }
        return simLatDeg + ThreadLocalRandom.current().nextDouble(-1, 1) * noiseRadius / 111320.0;
    }

    public double getReportedLon() {
        if (spoofingActive) {
            return spoofedLonDeg;
        }
        double noiseRadius = currentPositionAccuracyM;
        if (noiseRadius <= 0) {
            return simLonDeg;
        }
        double cosLat = Math.cos(Math.toRadians(simLatDeg));
        if (Math.abs(cosLat) < 1e-6) {
            return simLonDeg;
        }
        return simLonDeg + ThreadLocalRandom.current().nextDouble(-1, 1) * noiseRadius / (111320.0 * cosLat);
    }

    public double getReportedAlt() {
        if (spoofingActive) {
            return simAltM;
        }
        double noiseRadius = currentPositionAccuracyM;
        return simAltM + ThreadLocalRandom.current().nextDouble(-1, 1) * noiseRadius;
    }

    public List<GnssSatellite> getSatellites() {
        return satellites;
    }

    public GnssReceiver getReceiver() {
        return receiver;
    }

    public RtkBaseStation getBaseStation() {
        return baseStation;
    }
}