package io.aerofleet.sim.gnss;

public final class GnssSatellite {

    public final int prn;
    public final GnssConstellation constellation;
    public volatile double inclinationRad;
    public volatile double raanRad;
    public volatile double phaseRad;
    public volatile double orbitRadiusM;

    public GnssSatellite(int prn, GnssConstellation constellation,
                         double inclinationDeg, double raanDeg, double phaseDeg) {
        this.prn = prn;
        this.constellation = constellation;
        this.inclinationRad = Math.toRadians(inclinationDeg);
        this.raanRad = Math.toRadians(raanDeg);
        this.phaseRad = Math.toRadians(phaseDeg);
        this.orbitRadiusM = (constellation.orbitAltitudeKm + 6371.0) * 1000.0;
    }

    public void tick(double dt) {
        double angularVelocity = Math.sqrt(3.986004418e14 / (orbitRadiusM * orbitRadiusM * orbitRadiusM));
        phaseRad += angularVelocity * dt;
        if (phaseRad > Math.PI * 2) {
            phaseRad -= Math.PI * 2;
        }
    }

    public double[] ecefPosition() {
        double cosI = Math.cos(inclinationRad);
        double sinI = Math.sin(inclinationRad);
        double cosRaan = Math.cos(raanRad);
        double sinRaan = Math.sin(raanRad);
        double cosPhase = Math.cos(phaseRad);
        double sinPhase = Math.sin(phaseRad);

        double xOrbit = orbitRadiusM * cosPhase;
        double yOrbit = orbitRadiusM * sinPhase;

        double x = xOrbit * cosRaan - yOrbit * cosI * sinRaan;
        double y = xOrbit * sinRaan + yOrbit * cosI * cosRaan;
        double z = yOrbit * sinI;

        return new double[]{x, y, z};
    }

    public double[] enuPosition(double receiverLatRad, double receiverLonRad, double receiverAltM) {
        double[] ecef = ecefPosition();
        double receiverRadius = (6371000.0 + receiverAltM);
        double rx = receiverRadius * Math.cos(receiverLatRad) * Math.cos(receiverLonRad);
        double ry = receiverRadius * Math.cos(receiverLatRad) * Math.sin(receiverLonRad);
        double rz = receiverRadius * Math.sin(receiverLatRad);

        double dx = ecef[0] - rx;
        double dy = ecef[1] - ry;
        double dz = ecef[2] - rz;

        double sinLat = Math.sin(receiverLatRad);
        double cosLat = Math.cos(receiverLatRad);
        double sinLon = Math.sin(receiverLonRad);
        double cosLon = Math.cos(receiverLonRad);

        double east = -sinLon * dx + cosLon * dy;
        double north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz;
        double up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz;

        return new double[]{east, north, up};
    }

    public double elevationDeg(double receiverLatRad, double receiverLonRad, double receiverAltM) {
        double[] enu = enuPosition(receiverLatRad, receiverLonRad, receiverAltM);
        double horizontal = Math.hypot(enu[0], enu[1]);
        return Math.toDegrees(Math.atan2(enu[2], horizontal));
    }

    public double azimuthDeg(double receiverLatRad, double receiverLonRad, double receiverAltM) {
        double[] enu = enuPosition(receiverLatRad, receiverLonRad, receiverAltM);
        return Math.toDegrees(Math.atan2(enu[0], enu[1]));
    }

    public double pseudoRange(double receiverLatRad, double receiverLonRad, double receiverAltM) {
        double[] enu = enuPosition(receiverLatRad, receiverLonRad, receiverAltM);
        return Math.sqrt(enu[0] * enu[0] + enu[1] * enu[1] + enu[2] * enu[2]);
    }
}