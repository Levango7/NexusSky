package io.aerofleet.sim.gnss;

public enum GnssConstellation {
    GPS(31, 20180.0, 1575.42, 1, 32, 55.0),
    GLONASS(24, 19100.0, 1602.0, 33, 56, 64.8),
    GALILEO(30, 23222.0, 1575.42, 57, 86, 56.0),
    BEIDOU(35, 27900.0, 1561.098, 87, 121, 55.0);

    public final int satelliteCount;
    public final double orbitAltitudeKm;
    public final double frequencyMHz;
    public final int prnMin;
    public final int prnMax;
    public final double inclinationDeg;

    GnssConstellation(int satelliteCount, double orbitAltitudeKm, double frequencyMHz,
                      int prnMin, int prnMax, double inclinationDeg) {
        this.satelliteCount = satelliteCount;
        this.orbitAltitudeKm = orbitAltitudeKm;
        this.frequencyMHz = frequencyMHz;
        this.prnMin = prnMin;
        this.prnMax = prnMax;
        this.inclinationDeg = inclinationDeg;
    }

    public boolean containsPrn(int prn) {
        return prn >= prnMin && prn <= prnMax;
    }

    public static GnssConstellation fromPrn(int prn) {
        for (GnssConstellation c : values()) {
            if (c.containsPrn(prn)) {
                return c;
            }
        }
        return null;
    }
}