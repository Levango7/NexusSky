package io.aerofleet.sim.weather;

import java.util.Random;

public class TurbulenceModel {

    public volatile String severity;
    public volatile double turbulenceIntensity;
    public volatile double scaleLengthU;
    public volatile double scaleLengthV;
    public volatile double scaleLengthW;

    private double stateLowU;
    private double stateLowV;
    private double stateLowW;
    private double stateHighU;
    private double stateHighV;
    private double stateHighW;
    private final Random random;

    public TurbulenceModel(String severity) {
        this.severity = severity;
        this.random = new Random();
        configureSeverity(severity);
    }

    private void configureSeverity(String sev) {
        switch (sev) {
            case "light":
                turbulenceIntensity = 0.5;
                break;
            case "moderate":
                turbulenceIntensity = 1.5;
                break;
            case "severe":
                turbulenceIntensity = 3.0;
                break;
            case "extreme":
                turbulenceIntensity = 5.0;
                break;
            default:
                turbulenceIntensity = 1.0;
                this.severity = "moderate";
        }
        scaleLengthU = 200.0;
        scaleLengthV = 200.0;
        scaleLengthW = 50.0;
    }

    public double[] getTurbulence(double altitude, double airspeed, double dt) {
        if (airspeed < 0.1 || dt <= 0) {
            return new double[]{0, 0, 0};
        }

        double sigmaU = turbulenceIntensity;
        double sigmaV = turbulenceIntensity * 0.8;
        double sigmaW = turbulenceIntensity * 0.6;

        double Lu, Lv, Lw;
        if (altitude > 1000) {
            Lu = 530.0;
            Lv = 530.0;
            Lw = 530.0;
        } else {
            Lu = 200.0;
            Lv = 200.0;
            Lw = 50.0;
        }

        double aLowU = Math.exp(-airspeed * dt / Lu);
        double aLowV = Math.exp(-airspeed * dt / Lv);
        double aLowW = Math.exp(-airspeed * dt / Lw);

        double aHighU = Math.exp(-airspeed * dt / (Lu * 0.1));
        double aHighV = Math.exp(-airspeed * dt / (Lv * 0.1));
        double aHighW = Math.exp(-airspeed * dt / (Lw * 0.1));

        double noiseU = random.nextGaussian();
        double noiseV = random.nextGaussian();
        double noiseW = random.nextGaussian();

        double gainLowU = sigmaU * 0.7 * Math.sqrt(
                Math.max(0, 2.0 * airspeed / (Math.PI * Lu) * (1 - aLowU * aLowU)));
        double gainHighU = sigmaU * 0.3 * Math.sqrt(
                Math.max(0, 2.0 * airspeed / (Math.PI * Lu * 0.1) * (1 - aHighU * aHighU)));

        double gainLowV = sigmaV * 0.7 * Math.sqrt(
                Math.max(0, 2.0 * airspeed / (Math.PI * Lv) * (1 - aLowV * aLowV)));
        double gainHighV = sigmaV * 0.3 * Math.sqrt(
                Math.max(0, 2.0 * airspeed / (Math.PI * Lv * 0.1) * (1 - aHighV * aHighV)));

        double gainLowW = sigmaW * 0.7 * Math.sqrt(
                Math.max(0, 2.0 * airspeed / (Math.PI * Lw) * (1 - aLowW * aLowW)));
        double gainHighW = sigmaW * 0.3 * Math.sqrt(
                Math.max(0, 2.0 * airspeed / (Math.PI * Lw * 0.1) * (1 - aHighW * aHighW)));

        stateLowU = aLowU * stateLowU + gainLowU * noiseU;
        stateHighU = aHighU * stateHighU + gainHighU * noiseU;

        stateLowV = aLowV * stateLowV + gainLowV * noiseV;
        stateHighV = aHighV * stateHighV + gainHighV * noiseV;

        stateLowW = aLowW * stateLowW + gainLowW * noiseW;
        stateHighW = aHighW * stateHighW + gainHighW * noiseW;

        return new double[]{stateLowU + stateHighU, stateLowV + stateHighV, stateLowW + stateHighW};
    }
}