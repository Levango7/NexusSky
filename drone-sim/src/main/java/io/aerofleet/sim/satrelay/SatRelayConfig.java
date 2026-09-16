package io.aerofleet.sim.satrelay;

/**
 * 星-空-地多层级中继配置（M7，数据约束 6.7）。
 * <p>
 * 所有字段 final 不可变，仿 {@code MeshRouterConfig} 风格。
 * 承载可见仰角阈值、降级滞后阈值、切换策略、星座参数等。
 * <p>
 * 默认值（design.md §8）：
 * <pre>
 * elevationThresholdDeg     = 10
 * hysteresisThresholdMs     = 5000
 * strategy                  = NEAR_FIRST
 * constellationSize         = 24
 * orbitAltitudeKm           = 550
 * inclinationDeg            = 53.0
 * linkWindowScanStepMs      = 60000
 * satLinkReportIntervalMs   = 2000
 * passScheduleHorizonMs     = 86400000
 * </pre>
 */
public final class SatRelayConfig {

    public enum Strategy {
        NEAR_FIRST(0, "近端优先"),
        DELAY_OPTIMAL(1, "延迟最优"),
        BANDWIDTH_OPTIMAL(2, "带宽最优"),
        RELIABILITY_OPTIMAL(3, "可靠性最优");

        private final int ordinalCode;
        private final String label;

        Strategy(int ordinalCode, String label) {
            this.ordinalCode = ordinalCode;
            this.label = label;
        }

        public int code() {
            return ordinalCode;
        }

        public String label() {
            return label;
        }

        public static Strategy fromCode(int code) {
            for (Strategy s : values()) {
                if (s.ordinalCode == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("Unknown strategy code: " + code);
        }

        public static Strategy fromName(String name) {
            try {
                return Strategy.valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown strategy name: " + name
                        + ", valid: NEAR_FIRST/DELAY_OPTIMAL/BANDWIDTH_OPTIMAL/RELIABILITY_OPTIMAL");
            }
        }
    }

    public final double elevationThresholdDeg;
    public final long hysteresisThresholdMs;
    public final Strategy strategy;
    public final int constellationSize;
    public final double orbitAltitudeKm;
    public final double inclinationDeg;
    public final long linkWindowScanStepMs;
    public final long satLinkReportIntervalMs;
    public final long passScheduleHorizonMs;

    public SatRelayConfig(double elevationThresholdDeg, long hysteresisThresholdMs,
                          Strategy strategy, int constellationSize,
                          double orbitAltitudeKm, double inclinationDeg,
                          long linkWindowScanStepMs, long satLinkReportIntervalMs,
                          long passScheduleHorizonMs) {
        if (elevationThresholdDeg <= 0 || elevationThresholdDeg >= 90) {
            throw new IllegalArgumentException(
                    "elevationThresholdDeg must be in (0, 90), got " + elevationThresholdDeg);
        }
        if (hysteresisThresholdMs <= 0) {
            throw new IllegalArgumentException("hysteresisThresholdMs must be > 0, got " + hysteresisThresholdMs);
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        if (constellationSize < LeoConstellation.MIN_SIZE || constellationSize > LeoConstellation.MAX_SIZE) {
            throw new IllegalArgumentException(
                    "constellationSize must be in [" + LeoConstellation.MIN_SIZE + ", "
                            + LeoConstellation.MAX_SIZE + "], got " + constellationSize);
        }
        if (orbitAltitudeKm < 300.0 || orbitAltitudeKm > 1200.0) {
            throw new IllegalArgumentException(
                    "orbitAltitudeKm must be in [300, 1200], got " + orbitAltitudeKm);
        }
        if (inclinationDeg < 0.0 || inclinationDeg > 180.0) {
            throw new IllegalArgumentException(
                    "inclinationDeg must be in [0, 180], got " + inclinationDeg);
        }
        if (linkWindowScanStepMs <= 0) {
            throw new IllegalArgumentException("linkWindowScanStepMs must be > 0, got " + linkWindowScanStepMs);
        }
        if (satLinkReportIntervalMs <= 0) {
            throw new IllegalArgumentException("satLinkReportIntervalMs must be > 0, got " + satLinkReportIntervalMs);
        }
        if (passScheduleHorizonMs <= 0) {
            throw new IllegalArgumentException("passScheduleHorizonMs must be > 0, got " + passScheduleHorizonMs);
        }
        this.elevationThresholdDeg = elevationThresholdDeg;
        this.hysteresisThresholdMs = hysteresisThresholdMs;
        this.strategy = strategy;
        this.constellationSize = constellationSize;
        this.orbitAltitudeKm = orbitAltitudeKm;
        this.inclinationDeg = inclinationDeg;
        this.linkWindowScanStepMs = linkWindowScanStepMs;
        this.satLinkReportIntervalMs = satLinkReportIntervalMs;
        this.passScheduleHorizonMs = passScheduleHorizonMs;
    }

    /** 默认配置。 */
    public static SatRelayConfig defaults() {
        return new SatRelayConfig(
                10.0, 5000L, Strategy.NEAR_FIRST,
                24, 550.0, 53.0,
                60_000L, 2000L, 86_400_000L);
    }

    /**
     * CLI 解析：从 --key value 对中提取 sat-relay 配置。
     * <p>
     * 识别参数：{@code --sat-elevation-threshold} / {@code --sat-hysteresis-ms} /
     * {@code --sat-strategy} / {@code --sat-constellation-size} /
     * {@code --sat-orbit-altitude} / {@code --sat-inclination} /
     * {@code --sat-window-scan-step-ms} / {@code --sat-link-report-ms} / {@code --sat-pass-horizon-ms}
     */
    public static SatRelayConfig parse(String[] args) {
        SatRelayConfig defaults = defaults();
        double elevationThresholdDeg = defaults.elevationThresholdDeg;
        long hysteresisThresholdMs = defaults.hysteresisThresholdMs;
        Strategy strategy = defaults.strategy;
        int constellationSize = defaults.constellationSize;
        double orbitAltitudeKm = defaults.orbitAltitudeKm;
        double inclinationDeg = defaults.inclinationDeg;
        long linkWindowScanStepMs = defaults.linkWindowScanStepMs;
        long satLinkReportIntervalMs = defaults.satLinkReportIntervalMs;
        long passScheduleHorizonMs = defaults.passScheduleHorizonMs;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String key;
            String value;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 2) {
                key = arg.substring(2, eq);
                value = arg.substring(eq + 1);
            } else if (arg.startsWith("--") && i + 1 < args.length) {
                key = arg.substring(2);
                value = args[++i];
            } else {
                continue;
            }
            try {
                switch (key) {
                    case "sat-elevation-threshold" -> elevationThresholdDeg = Double.parseDouble(value);
                    case "sat-hysteresis-ms" -> hysteresisThresholdMs = Long.parseLong(value);
                    case "sat-strategy" -> strategy = Strategy.fromName(value);
                    case "sat-constellation-size" -> constellationSize = Integer.parseInt(value);
                    case "sat-orbit-altitude" -> orbitAltitudeKm = Double.parseDouble(value);
                    case "sat-inclination" -> inclinationDeg = Double.parseDouble(value);
                    case "sat-window-scan-step-ms" -> linkWindowScanStepMs = Long.parseLong(value);
                    case "sat-link-report-ms" -> satLinkReportIntervalMs = Long.parseLong(value);
                    case "sat-pass-horizon-ms" -> passScheduleHorizonMs = Long.parseLong(value);
                    default -> { /* 忽略非 sat-relay 参数 */ }
                }
            } catch (IllegalArgumentException e) {
                io.aerofleet.sim.SimLog.warn("Invalid value for --" + key + ": " + value);
            }
        }
        try {
            return new SatRelayConfig(elevationThresholdDeg, hysteresisThresholdMs, strategy,
                    constellationSize, orbitAltitudeKm, inclinationDeg,
                    linkWindowScanStepMs, satLinkReportIntervalMs, passScheduleHorizonMs);
        } catch (IllegalArgumentException e) {
            io.aerofleet.sim.SimLog.warn("SatRelayConfig invalid, fallback to defaults: " + e.getMessage());
            return defaults;
        }
    }

    @Override
    public String toString() {
        return "SatRelayConfig{elevThreshold=" + elevationThresholdDeg + "°"
                + ", hysteresis=" + hysteresisThresholdMs + "ms"
                + ", strategy=" + strategy
                + ", constellationSize=" + constellationSize
                + ", orbitAlt=" + orbitAltitudeKm + "km"
                + ", incl=" + inclinationDeg + "°"
                + ", scanStep=" + linkWindowScanStepMs + "ms"
                + ", reportInterval=" + satLinkReportIntervalMs + "ms"
                + ", passHorizon=" + passScheduleHorizonMs + "ms}";
    }
}