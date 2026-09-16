package io.aerofleet.sim.edge;

/** M12 传感器融合引擎：GPS + IMU + 视觉 + LiDAR 加权融合 */
public class SensorFusionEngine {
    /** 融合多传感器数据 */
    public FusedState fuse(double gpsLat, double gpsLon, double gpsAlt,
                           double imuHeading, double imuVelocity,
                           double visionLat, double visionLon,
                           double lidarAlt,
                           boolean hasGps, boolean hasImu, boolean hasVision, boolean hasLidar) {
        int mask = 0;
        double lat = 0, lon = 0, alt = 0, heading = 0, velocity = 0;
        double wSum = 0;

        if (hasGps) { mask |= 1; lat += gpsLat * 0.5; lon += gpsLon * 0.5; alt += gpsAlt * 0.3; wSum += 0.5; }
        if (hasImu) { mask |= 2; heading += imuHeading * 0.4; velocity += imuVelocity * 0.5; wSum += 0.3; }
        if (hasVision) { mask |= 4; lat += visionLat * 0.3; lon += visionLon * 0.3; wSum += 0.2; }
        if (hasLidar) { mask |= 8; alt += lidarAlt * 0.4; wSum += 0.2; }

        if (wSum > 0) { lat /= wSum; lon /= wSum; }
        double accuracy = 1.0 / (1 + Integer.bitCount(mask));
        return new FusedState(lat, lon, alt, heading, velocity, accuracy, mask);
    }
}
