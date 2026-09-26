package io.aerofleet.sim.weather;

public class AirDensityModel {

    public static final double SEA_LEVEL_TEMP = 288.15;
    public static final double SEA_LEVEL_PRESSURE = 101325.0;
    public static final double TEMP_LAPSE_RATE = -0.0065;
    public static final double GAS_CONSTANT = 287.05;
    public static final double TROPOPAUSE_HEIGHT = 11000.0;
    public static final double TROPOPAUSE_TEMP = 216.65;
    public static final double GRAVITY = 9.80665;
    public static final double SEA_LEVEL_DENSITY = SEA_LEVEL_PRESSURE / (GAS_CONSTANT * SEA_LEVEL_TEMP);

    public double getTemperature(double altitude) {
        if (altitude < 0) {
            return SEA_LEVEL_TEMP;
        }
        if (altitude < TROPOPAUSE_HEIGHT) {
            return SEA_LEVEL_TEMP + TEMP_LAPSE_RATE * altitude;
        }
        return TROPOPAUSE_TEMP;
    }

    public double getPressure(double altitude) {
        if (altitude < 0) {
            return SEA_LEVEL_PRESSURE;
        }
        if (altitude < TROPOPAUSE_HEIGHT) {
            double tempRatio = getTemperature(altitude) / SEA_LEVEL_TEMP;
            return SEA_LEVEL_PRESSURE * Math.pow(tempRatio, -GRAVITY / (TEMP_LAPSE_RATE * GAS_CONSTANT));
        }
        double p11 = SEA_LEVEL_PRESSURE * Math.pow(TROPOPAUSE_TEMP / SEA_LEVEL_TEMP,
                -GRAVITY / (TEMP_LAPSE_RATE * GAS_CONSTANT));
        return p11 * Math.exp(-GRAVITY * (altitude - TROPOPAUSE_HEIGHT) / (GAS_CONSTANT * TROPOPAUSE_TEMP));
    }

    public double getDensity(double altitude) {
        double temp = getTemperature(altitude);
        double pressure = getPressure(altitude);
        return pressure / (GAS_CONSTANT * temp);
    }

    public double getThrustFactor(double altitude) {
        double densityRatio = getDensity(altitude) / SEA_LEVEL_DENSITY;
        return Math.pow(densityRatio, 0.7);
    }

    public double getRotorEfficiency(double altitude) {
        double densityRatio = getDensity(altitude) / SEA_LEVEL_DENSITY;
        return Math.pow(densityRatio, 0.5);
    }
}