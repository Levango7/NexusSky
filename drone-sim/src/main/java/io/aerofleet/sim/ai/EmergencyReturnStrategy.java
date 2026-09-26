package io.aerofleet.sim.ai;

import io.aerofleet.sim.GeoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EmergencyReturnStrategy {

    private static final Logger log = LoggerFactory.getLogger(EmergencyReturnStrategy.class);

    private static final double BATTERY_THRESHOLD = 25.0;
    private static final long LINK_LOSS_TIMEOUT_MS = 30_000L;
    private static final double EXTREME_WIND_SPEED = 15.0;
    private static final int EXTREME_WEATHER_CODE = 3;
    private static final double CRUISE_SPEED_MPS = 15.0;
    private static final double SAFETY_ALTITUDE_M = 30.0;
    private static final double LANDING_SITE_RADIUS_M = 50.0;

    public volatile double batteryPct = 100.0;
    public volatile boolean linkLost = false;
    public volatile long linkLostSinceMs = 0L;
    public volatile boolean gpsLost = false;
    public volatile int weatherCode = 0;
    public volatile double windSpeed = 0.0;
    public volatile double windDirection = 0.0;
    public volatile double currentLat = Double.NaN;
    public volatile double currentLon = Double.NaN;
    public volatile double currentAlt = Double.NaN;
    public volatile double currentHeading = 0.0;
    public volatile double homeLat = Double.NaN;
    public volatile double homeLon = Double.NaN;
    public volatile double homeAlt = 0.0;
    public volatile List<double[]> safeLandingSites = Collections.emptyList();
    public volatile List<double[]> terrainObstacles = Collections.emptyList();

    public static final class EmergencyReturnResult {
        public final List<double[]> waypoints;
        public final double estimatedTimeSec;
        public final String triggerReason;
        public final double confidence;
        public final boolean reachable;
        public final double targetLat;
        public final double targetLon;
        public final double targetAlt;

        public EmergencyReturnResult(List<double[]> waypoints, double estimatedTimeSec,
                                     String triggerReason, double confidence, boolean reachable,
                                     double targetLat, double targetLon, double targetAlt) {
            this.waypoints = waypoints;
            this.estimatedTimeSec = estimatedTimeSec;
            this.triggerReason = triggerReason;
            this.confidence = confidence;
            this.reachable = reachable;
            this.targetLat = targetLat;
            this.targetLon = targetLon;
            this.targetAlt = targetAlt;
        }
    }

    public boolean shouldTrigger() {
        if (batteryPct < BATTERY_THRESHOLD) return true;
        if (linkLost && (System.currentTimeMillis() - linkLostSinceMs) > LINK_LOSS_TIMEOUT_MS) return true;
        if (gpsLost) return true;
        if (weatherCode >= EXTREME_WEATHER_CODE) return true;
        if (windSpeed > EXTREME_WIND_SPEED) return true;
        return false;
    }

    public String determineTriggerReason() {
        if (batteryPct < BATTERY_THRESHOLD) return "low battery";
        if (linkLost && (System.currentTimeMillis() - linkLostSinceMs) > LINK_LOSS_TIMEOUT_MS) return "link lost";
        if (gpsLost) return "GPS lost";
        if (weatherCode >= EXTREME_WEATHER_CODE) return "extreme weather";
        if (windSpeed > EXTREME_WIND_SPEED) return "extreme wind";
        return null;
    }

    public EmergencyReturnResult evaluate() {
        if (!shouldTrigger()) return null;

        String reason = determineTriggerReason();
        double confidence = computeConfidence(reason);

        double[] target = selectLandingSite();
        List<double[]> path = computeReturnPath(target[0], target[1], target[2]);
        double eta = computeETA(path);

        boolean canReach = checkReachability(eta);

        log.warn("[EmergencyReturn] triggered: reason={} confidence={} target=({},{},{}) eta={}s reachable={}",
                reason, confidence, target[0], target[1], target[2], String.format("%.1f", eta), canReach);

        return new EmergencyReturnResult(path, eta, reason, confidence, canReach,
                target[0], target[1], target[2]);
    }

    public DecisionResult evaluateAsDecisionResult() {
        EmergencyReturnResult result = evaluate();
        if (result == null) return null;
        String type = "GPS lost".equals(result.triggerReason) ? "EMERGENCY_LAND" : "RTL";
        return new DecisionResult(type, result.triggerReason, batteryPct, result.confidence, result.waypoints);
    }

    private double computeConfidence(String reason) {
        switch (reason) {
            case "low battery":
                if (batteryPct < 10.0) return 0.98;
                if (batteryPct < 15.0) return 0.95;
                return 0.9;
            case "link lost":
                return 0.92;
            case "GPS lost":
                return 0.88;
            case "extreme weather":
                return 0.85;
            case "extreme wind":
                return 0.82;
            default:
                return 0.7;
        }
    }

    private double[] selectLandingSite() {
        if (gpsLost) {
            return new double[]{currentLat, currentLon, Math.max(currentAlt, SAFETY_ALTITUDE_M)};
        }

        double[] homeSite = new double[]{homeLat, homeLon, homeAlt};
        double homeDist = horizontalDistance(currentLat, currentLon, homeLat, homeLon);

        if (safeLandingSites == null || safeLandingSites.isEmpty()) {
            return homeSite;
        }

        double minDist = homeDist;
        double[] best = homeSite;

        for (double[] site : safeLandingSites) {
            double dist = horizontalDistance(currentLat, currentLon, site[0], site[1]);
            if (dist < minDist) {
                minDist = dist;
                best = new double[]{site[0], site[1], site.length > 2 ? site[2] : 0.0};
            }
        }

        if (batteryPct < 10.0 && minDist > homeDist * 0.5) {
            double[] emergencySite = findEmergencyLandingSpot();
            if (emergencySite != null) return emergencySite;
        }

        return best;
    }

    private double[] findEmergencyLandingSpot() {
        if (safeLandingSites == null || safeLandingSites.isEmpty()) return null;

        double bestDist = Double.MAX_VALUE;
        double[] best = null;

        for (double[] site : safeLandingSites) {
            double dist = horizontalDistance(currentLat, currentLon, site[0], site[1]);
            if (dist < bestDist) {
                bestDist = dist;
                best = new double[]{site[0], site[1], site.length > 2 ? site[2] : 0.0};
            }
        }

        return best;
    }

    private List<double[]> computeReturnPath(double targetLat, double targetLon, double targetAlt) {
        List<double[]> path = new ArrayList<>();
        path.add(new double[]{currentLat, currentLon, currentAlt});

        double north = GeoUtil.north(currentLat, currentLon, targetLat, targetLon);
        double east = GeoUtil.east(currentLat, currentLon, targetLat, targetLon);
        double horizontalDist = Math.sqrt(north * north + east * east);

        if (horizontalDist < 1e-6) {
            return path;
        }

        double directHeading = normalizeHeading(Math.toDegrees(Math.atan2(east, north)));

        if (terrainObstacles != null && !terrainObstacles.isEmpty()) {
            List<double[]> detourPath = computeObstacleAvoidingPath(targetLat, targetLon, targetAlt, directHeading);
            if (detourPath != null) return detourPath;
        }

        if (currentAlt < SAFETY_ALTITUDE_M) {
            double climbLat = GeoUtil.latOf(currentLat, currentLon,
                    Math.cos(Math.toRadians(directHeading)) * 20.0,
                    Math.sin(Math.toRadians(directHeading)) * 20.0);
            double climbLon = GeoUtil.lonOf(currentLat, currentLon,
                    Math.cos(Math.toRadians(directHeading)) * 20.0,
                    Math.sin(Math.toRadians(directHeading)) * 20.0);
            path.add(new double[]{climbLat, climbLon, SAFETY_ALTITUDE_M});
        }

        path.add(new double[]{targetLat, targetLon, targetAlt});
        return path;
    }

    private List<double[]> computeObstacleAvoidingPath(double targetLat, double targetLon,
                                                       double targetAlt, double directHeading) {
        double north = GeoUtil.north(currentLat, currentLon, targetLat, targetLon);
        double east = GeoUtil.east(currentLat, currentLon, targetLat, targetLon);
        double horizontalDist = Math.sqrt(north * north + east * east);

        List<double[]> path = new ArrayList<>();
        path.add(new double[]{currentLat, currentLon, currentAlt});

        double perpHeading = normalizeHeading(directHeading + 90.0);
        double offsetDist = 0.0;
        boolean needsDetour = false;

        for (double[] obstacle : terrainObstacles) {
            double obsLat = obstacle[0];
            double obsLon = obstacle[1];
            double obsAlt = obstacle[2];
            double obsRadius = obstacle.length > 3 ? obstacle[3] : LANDING_SITE_RADIUS_M;

            double obsNorth = GeoUtil.north(currentLat, currentLon, obsLat, obsLon);
            double obsEast = GeoUtil.east(currentLat, currentLon, obsLat, obsLon);

            double projection = (obsNorth * north + obsEast * east) / horizontalDist;
            if (projection < 0 || projection > horizontalDist) continue;

            double perpDist = Math.abs(obsNorth * east - obsEast * north) / horizontalDist;
            if (perpDist < obsRadius + 20.0) {
                offsetDist = Math.max(offsetDist, obsRadius + 30.0);
                needsDetour = true;
            }
        }

        if (needsDetour) {
            double midProgress = horizontalDist * 0.5;
            double midNorth = north * 0.5;
            double midEast = east * 0.5;

            double perpRad = Math.toRadians(perpHeading);
            midNorth += offsetDist * Math.cos(perpRad);
            midEast += offsetDist * Math.sin(perpRad);

            double detourLat = GeoUtil.latOf(currentLat, currentLon, midNorth, midEast);
            double detourLon = GeoUtil.lonOf(currentLat, currentLon, midNorth, midEast);
            double detourAlt = Math.max(currentAlt, targetAlt) + 10.0;

            path.add(new double[]{detourLat, detourLon, detourAlt});
        }

        path.add(new double[]{targetLat, targetLon, targetAlt});
        return path;
    }

    private double computeETA(List<double[]> path) {
        if (path.size() < 2) return 0.0;

        double totalDist = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            double[] a = path.get(i);
            double[] b = path.get(i + 1);
            double dn = GeoUtil.north(a[0], a[1], b[0], b[1]);
            double de = GeoUtil.east(a[0], a[1], b[0], b[1]);
            double da = b[2] - a[2];
            totalDist += Math.sqrt(dn * dn + de * de + da * da);
        }

        double effectiveSpeed = CRUISE_SPEED_MPS;
        if (windSpeed > 0) {
            double windAngle = normalizeAngleDiff(windDirection - currentHeading);
            double headwindComponent = windSpeed * Math.cos(Math.toRadians(windAngle));
            effectiveSpeed = Math.max(2.0, CRUISE_SPEED_MPS - headwindComponent * 0.5);
        }

        if (batteryPct < 15.0) {
            effectiveSpeed *= 0.8;
        }

        return totalDist / effectiveSpeed;
    }

    private boolean checkReachability(double etaSec) {
        double maxFlightTimeSec = (batteryPct / 100.0) * 25.0 * 60.0;
        return etaSec <= maxFlightTimeSec;
    }

    private double horizontalDistance(double lat1, double lon1, double lat2, double lon2) {
        double dn = GeoUtil.north(lat1, lon1, lat2, lon2);
        double de = GeoUtil.east(lat1, lon1, lat2, lon2);
        return Math.sqrt(dn * dn + de * de);
    }

    private static double normalizeHeading(double angle) {
        angle = angle % 360.0;
        if (angle < 0.0) angle += 360.0;
        return angle;
    }

    private static double normalizeAngleDiff(double angle) {
        angle = angle % 360.0;
        if (angle >= 180.0) angle -= 360.0;
        if (angle < -180.0) angle += 360.0;
        return angle;
    }
}