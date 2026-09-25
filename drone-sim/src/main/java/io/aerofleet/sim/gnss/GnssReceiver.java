package io.aerofleet.sim.gnss;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class GnssReceiver {

    public enum FixMode {
        SINGLE,
        RTK_FIXED,
        RTK_FLOAT
    }

    public static final double ACCURACY_SINGLE_MIN = 2.0;
    public static final double ACCURACY_SINGLE_MAX = 5.0;
    public static final double ACCURACY_RTK_FIXED = 0.02;
    public static final double ACCURACY_RTK_FLOAT = 0.5;
    public static final double ELEVATION_MASK_DEG = 5.0;

    public volatile FixMode fixMode = FixMode.SINGLE;
    public volatile double receiverLatDeg;
    public volatile double receiverLonDeg;
    public volatile double receiverAltM;
    public volatile int satellitesVisible;
    public volatile double hdop;
    public volatile double vdop;
    public volatile double pdop;
    public volatile double positionAccuracyM;
    public volatile boolean rtkCorrectionsReceived;
    public volatile boolean jammingActive;
    public volatile boolean spoofingActive;

    private final List<GnssSatellite> allSatellites;
    private List<GnssSatellite> visibleSatellites = new ArrayList<>();

    public GnssReceiver(double latDeg, double lonDeg, double altM) {
        this.receiverLatDeg = latDeg;
        this.receiverLonDeg = lonDeg;
        this.receiverAltM = altM;
        this.allSatellites = new ArrayList<>();
    }

    public void addSatellite(GnssSatellite sat) {
        allSatellites.add(sat);
    }

    public void addSatellites(List<GnssSatellite> sats) {
        allSatellites.addAll(sats);
    }

    public List<GnssSatellite> computeVisibleSatellites() {
        List<GnssSatellite> visible = new ArrayList<>();
        double latRad = Math.toRadians(receiverLatDeg);
        double lonRad = Math.toRadians(receiverLonDeg);

        for (GnssSatellite sat : allSatellites) {
            double elev = sat.elevationDeg(latRad, lonRad, receiverAltM);
            if (elev >= ELEVATION_MASK_DEG && !jammingActive) {
                visible.add(sat);
            } else if (elev >= ELEVATION_MASK_DEG && jammingActive) {
                if (ThreadLocalRandom.current().nextDouble() > 0.7) {
                    visible.add(sat);
                }
            }
        }
        this.visibleSatellites = visible;
        this.satellitesVisible = visible.size();
        return visible;
    }

    public void computeDOP() {
        if (visibleSatellites.size() < 4) {
            hdop = 99.0;
            vdop = 99.0;
            pdop = 99.0;
            return;
        }

        double latRad = Math.toRadians(receiverLatDeg);
        double lonRad = Math.toRadians(receiverLonDeg);
        int n = visibleSatellites.size();

        double[][] H = new double[n][4];
        for (int i = 0; i < n; i++) {
            GnssSatellite sat = visibleSatellites.get(i);
            double[] enu = sat.enuPosition(latRad, lonRad, receiverAltM);
            double range = Math.sqrt(enu[0] * enu[0] + enu[1] * enu[1] + enu[2] * enu[2]);
            if (range < 1e-6) range = 1e-6;
            H[i][0] = enu[0] / range;
            H[i][1] = enu[1] / range;
            H[i][2] = enu[2] / range;
            H[i][3] = 1.0;
        }

        double[][] HtH = matMul(transpose(H), H);
        double[][] inv = invert4x4(HtH);
        if (inv == null) {
            hdop = 99.0;
            vdop = 99.0;
            pdop = 99.0;
            return;
        }

        hdop = Math.sqrt(Math.max(0, inv[0][0] + inv[1][1]));
        vdop = Math.sqrt(Math.max(0, inv[2][2]));
        pdop = Math.sqrt(Math.max(0, inv[0][0] + inv[1][1] + inv[2][2]));
    }

    public void solvePosition() {
        computeVisibleSatellites();
        computeDOP();

        if (satellitesVisible < 4) {
            positionAccuracyM = 99.0;
            return;
        }

        switch (fixMode) {
            case SINGLE:
                positionAccuracyM = ACCURACY_SINGLE_MIN + (hdop - 1.0) * 0.5;
                if (positionAccuracyM < ACCURACY_SINGLE_MIN) positionAccuracyM = ACCURACY_SINGLE_MIN;
                if (positionAccuracyM > ACCURACY_SINGLE_MAX) positionAccuracyM = ACCURACY_SINGLE_MAX;
                break;
            case RTK_FIXED:
                positionAccuracyM = ACCURACY_RTK_FIXED;
                break;
            case RTK_FLOAT:
                positionAccuracyM = ACCURACY_RTK_FLOAT;
                break;
        }

        if (jammingActive) {
            positionAccuracyM *= 5.0;
        }
        if (spoofingActive) {
            positionAccuracyM *= 10.0;
        }
    }

    public int[] constellationBreakdown() {
        int[] counts = new int[4];
        for (GnssSatellite sat : visibleSatellites) {
            switch (sat.constellation) {
                case GPS: counts[0]++; break;
                case GLONASS: counts[1]++; break;
                case GALILEO: counts[2]++; break;
                case BEIDOU: counts[3]++; break;
            }
        }
        return counts;
    }

    public void setRtkCorrectionsReceived(boolean received) {
        this.rtkCorrectionsReceived = received;
        if (received && fixMode == FixMode.SINGLE) {
            fixMode = FixMode.RTK_FLOAT;
        }
    }

    public void upgradeToRtkFixed() {
        if (rtkCorrectionsReceived) {
            fixMode = FixMode.RTK_FIXED;
        }
    }

    private static double[][] transpose(double[][] m) {
        int rows = m.length;
        int cols = m[0].length;
        double[][] t = new double[cols][rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                t[j][i] = m[i][j];
            }
        }
        return t;
    }

    private static double[][] matMul(double[][] a, double[][] b) {
        int rows = a.length;
        int cols = b[0].length;
        int inner = b.length;
        double[][] c = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double sum = 0;
                for (int k = 0; k < inner; k++) {
                    sum += a[i][k] * b[k][j];
                }
                c[i][j] = sum;
            }
        }
        return c;
    }

    private static double[][] invert4x4(double[][] m) {
        double[][] inv = new double[4][4];
        double det = determinant4x4(m);
        if (Math.abs(det) < 1e-12) {
            return null;
        }
        double invDet = 1.0 / det;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double[][] minor = minorMatrix(m, i, j);
                double cofactor = determinant3x3(minor);
                inv[j][i] = cofactor * invDet;
                if ((i + j) % 2 != 0) {
                    inv[j][i] = -inv[j][i];
                }
            }
        }
        return inv;
    }

    private static double determinant4x4(double[][] m) {
        double det = 0;
        for (int j = 0; j < 4; j++) {
            double[][] minor = minorMatrix(m, 0, j);
            double cofactor = determinant3x3(minor);
            if (j % 2 != 0) cofactor = -cofactor;
            det += m[0][j] * cofactor;
        }
        return det;
    }

    private static double determinant3x3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    private static double[][] minorMatrix(double[][] m, int row, int col) {
        double[][] minor = new double[3][3];
        int mi = 0, mj = 0;
        for (int i = 0; i < 4; i++) {
            if (i == row) continue;
            mj = 0;
            for (int j = 0; j < 4; j++) {
                if (j == col) continue;
                minor[mi][mj] = m[i][j];
                mj++;
            }
            mi++;
        }
        return minor;
    }
}