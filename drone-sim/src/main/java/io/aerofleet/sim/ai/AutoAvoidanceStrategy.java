package io.aerofleet.sim.ai;

import io.aerofleet.sim.GeoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AutoAvoidanceStrategy {

    private static final Logger log = LoggerFactory.getLogger(AutoAvoidanceStrategy.class);

    private static final double OBSTACLE_DISTANCE_THRESHOLD = 50.0;
    private static final double DRONE_SAFE_DISTANCE = 30.0;
    private static final double MIN_SPEED_MPS = 2.0;
    private static final double MAX_SPEED_MPS = 20.0;
    private static final double DEFAULT_AIRSPEED = 15.0;
    private static final double CLIMB_RATE_MPS = 2.0;
    private static final double DESCENT_RATE_MPS = 1.5;
    private static final double TIME_HORIZON_SEC = 5.0;
    private static final double SAFETY_MARGIN = 1.5;

    public volatile boolean obstacleDetected = false;
    public volatile double obstacleDistance = Double.MAX_VALUE;
    public volatile double obstacleBearing = 0.0;
    public volatile double obstacleSpeed = 0.0;
    public volatile List<OtherDrone> nearbyDrones = Collections.emptyList();
    public volatile double currentLat = Double.NaN;
    public volatile double currentLon = Double.NaN;
    public volatile double currentAlt = Double.NaN;
    public volatile double currentHeading = 0.0;
    public volatile double currentSpeed = DEFAULT_AIRSPEED;
    public volatile double currentVerticalSpeed = 0.0;

    public static final class OtherDrone {
        public volatile int sysid;
        public volatile double lat;
        public volatile double lon;
        public volatile double alt;
        public volatile double heading;
        public volatile double speed;

        public OtherDrone(int sysid, double lat, double lon, double alt, double heading, double speed) {
            this.sysid = sysid;
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.heading = heading;
            this.speed = speed;
        }
    }

    public static final class AvoidanceResult {
        public final double correctedHeading;
        public final double correctedSpeed;
        public final double correctedAlt;
        public final String avoidanceType;
        public final double confidence;
        public final List<double[]> avoidancePath;

        public AvoidanceResult(double correctedHeading, double correctedSpeed, double correctedAlt,
                               String avoidanceType, double confidence, List<double[]> avoidancePath) {
            this.correctedHeading = correctedHeading;
            this.correctedSpeed = correctedSpeed;
            this.correctedAlt = correctedAlt;
            this.avoidanceType = avoidanceType;
            this.confidence = confidence;
            this.avoidancePath = avoidancePath;
        }
    }

    public boolean shouldTrigger() {
        if (obstacleDetected && obstacleDistance < OBSTACLE_DISTANCE_THRESHOLD) return true;
        if (nearbyDrones != null) {
            for (OtherDrone drone : nearbyDrones) {
                double dist = horizontalDistance(currentLat, currentLon, drone.lat, drone.lon);
                if (dist < DRONE_SAFE_DISTANCE) return true;
            }
        }
        return false;
    }

    public AvoidanceResult evaluate() {
        if (!shouldTrigger()) return null;

        List<VelocityObstacle> vos = new ArrayList<>();

        if (obstacleDetected && obstacleDistance < OBSTACLE_DISTANCE_THRESHOLD) {
            vos.add(computeObstacleVO());
        }

        if (nearbyDrones != null) {
            for (OtherDrone drone : nearbyDrones) {
                double dist = horizontalDistance(currentLat, currentLon, drone.lat, drone.lon);
                if (dist < DRONE_SAFE_DISTANCE) {
                    vos.add(computeDroneVO(drone, dist));
                }
            }
        }

        AvoidanceResult result = resolveVelocityObstacle(vos);

        log.debug("[AutoAvoidance] triggered: vos={} heading={} speed={} alt={} type={}",
                vos.size(), String.format("%.1f", result.correctedHeading),
                String.format("%.1f", result.correctedSpeed),
                String.format("%.1f", result.correctedAlt), result.avoidanceType);

        return result;
    }

    public DecisionResult evaluateAsDecisionResult() {
        AvoidanceResult result = evaluate();
        if (result == null) return null;
        return new DecisionResult("AVOID", result.avoidanceType,
                obstacleDistance, result.confidence, result.avoidancePath);
    }

    private static final class VelocityObstacle {
        final double centerHeading;
        final double halfAngle;
        final double minSpeed;
        final double maxSpeed;
        final String source;

        VelocityObstacle(double centerHeading, double halfAngle,
                         double minSpeed, double maxSpeed, String source) {
            this.centerHeading = centerHeading;
            this.halfAngle = halfAngle;
            this.minSpeed = minSpeed;
            this.maxSpeed = maxSpeed;
            this.source = source;
        }

        boolean contains(double heading, double speed) {
            double angleDiff = Math.abs(normalizeAngleDiff(heading - centerHeading));
            return angleDiff <= halfAngle && speed >= minSpeed && speed <= maxSpeed;
        }
    }

    private VelocityObstacle computeObstacleVO() {
        double halfAngle = Math.toDegrees(Math.atan2(5.0, Math.max(1.0, obstacleDistance)));
        halfAngle *= SAFETY_MARGIN;
        return new VelocityObstacle(obstacleBearing, halfAngle, 0.0, MAX_SPEED_MPS, "obstacle");
    }

    private VelocityObstacle computeDroneVO(OtherDrone drone, double distance) {
        double bearing = computeBearing(currentLat, currentLon, drone.lat, drone.lon);
        double halfAngle = Math.toDegrees(Math.atan2(DRONE_SAFE_DISTANCE / 2.0, Math.max(1.0, distance)));
        halfAngle *= SAFETY_MARGIN;

        double combinedSpeed = currentSpeed + drone.speed;
        double voSpeed = combinedSpeed * (DRONE_SAFE_DISTANCE / Math.max(distance, 1.0));

        return new VelocityObstacle(bearing, halfAngle, 0.0, voSpeed, "drone-" + drone.sysid);
    }

    private AvoidanceResult resolveVelocityObstacle(List<VelocityObstacle> vos) {
        if (vos.isEmpty()) {
            return new AvoidanceResult(currentHeading, currentSpeed, currentAlt,
                    "no conflict", 0.5, Collections.emptyList());
        }

        double bestHeading = currentHeading;
        double bestSpeed = currentSpeed;
        double bestAlt = currentAlt;
        double bestScore = Double.MAX_VALUE;
        String bestType = "unknown";

        double[] headingCandidates = {
                currentHeading,
                normalizeHeading(currentHeading - 30.0),
                normalizeHeading(currentHeading + 30.0),
                normalizeHeading(currentHeading - 60.0),
                normalizeHeading(currentHeading + 60.0),
                normalizeHeading(currentHeading - 90.0),
                normalizeHeading(currentHeading + 90.0)
        };

        double[] speedCandidates = {currentSpeed, currentSpeed * 0.5, currentSpeed * 0.7, currentSpeed * 1.2};

        for (double h : headingCandidates) {
            for (double s : speedCandidates) {
                s = Math.max(MIN_SPEED_MPS, Math.min(MAX_SPEED_MPS, s));

                boolean inAnyVO = false;
                for (VelocityObstacle vo : vos) {
                    if (vo.contains(h, s)) {
                        inAnyVO = true;
                        break;
                    }
                }

                if (inAnyVO) continue;

                double headingCost = Math.abs(normalizeAngleDiff(h - currentHeading));
                double speedCost = Math.abs(s - currentSpeed) / DEFAULT_AIRSPEED;
                double score = headingCost + speedCost * 10.0;

                if (score < bestScore) {
                    bestScore = score;
                    bestHeading = h;
                    bestSpeed = s;
                    bestType = classifyAvoidance(h, currentHeading);
                }
            }
        }

        if (bestScore == Double.MAX_VALUE) {
            bestHeading = normalizeHeading(currentHeading + 90.0);
            bestSpeed = MIN_SPEED_MPS;
            bestAlt = currentAlt + CLIMB_RATE_MPS * TIME_HORIZON_SEC;
            bestType = "climb and turn";
        }

        boolean altitudeConflict = false;
        for (OtherDrone drone : nearbyDrones != null ? nearbyDrones : Collections.<OtherDrone>emptyList()) {
            if (Math.abs(drone.alt - currentAlt) < 10.0) {
                altitudeConflict = true;
                break;
            }
        }

        if (altitudeConflict) {
            double altAbove = currentAlt + CLIMB_RATE_MPS * TIME_HORIZON_SEC;
            double altBelow = currentAlt - DESCENT_RATE_MPS * TIME_HORIZON_SEC;
            bestAlt = (altAbove < 120.0) ? altAbove : altBelow;
        }

        List<double[]> path = computeAvoidancePath(bestHeading, bestSpeed, bestAlt);

        double confidence = vos.size() == 1 ? 0.85 : 0.7;

        return new AvoidanceResult(bestHeading, bestSpeed, bestAlt, bestType, confidence, path);
    }

    private String classifyAvoidance(double newHeading, double currentHdg) {
        double diff = normalizeAngleDiff(newHeading - currentHdg);
        if (diff > 5.0) return "turn right";
        if (diff < -5.0) return "turn left";
        return "slow down";
    }

    private List<double[]> computeAvoidancePath(double heading, double speed, double alt) {
        List<double[]> path = new ArrayList<>();
        path.add(new double[]{currentLat, currentLon, currentAlt});

        double hdgRad = Math.toRadians(heading);
        double distPerStep = speed * TIME_HORIZON_SEC / 3.0;

        for (int step = 1; step <= 3; step++) {
            double north = Math.cos(hdgRad) * distPerStep * step;
            double east = Math.sin(hdgRad) * distPerStep * step;
            double stepAlt = currentAlt + (alt - currentAlt) * step / 3.0;
            double lat = GeoUtil.latOf(currentLat, currentLon, north, east);
            double lon = GeoUtil.lonOf(currentLat, currentLon, north, east);
            path.add(new double[]{lat, lon, stepAlt});
        }

        return path;
    }

    private double computeBearing(double lat1, double lon1, double lat2, double lon2) {
        double north = GeoUtil.north(lat1, lon1, lat2, lon2);
        double east = GeoUtil.east(lat1, lon1, lat2, lon2);
        return normalizeHeading(Math.toDegrees(Math.atan2(east, north)));
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