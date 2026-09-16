package io.aerofleet.cloud.twin;

import java.util.List;

public class PredictionResult {
    public final int sysid;
    public final List<double[]> trajectoryPoints; // [lat, lon, alt] per point
    public final int horizonSec;
    public final double confidence;

    public PredictionResult(int sysid, List<double[]> points, int horizon, double conf) {
        this.sysid = sysid; this.trajectoryPoints = points; this.horizonSec = horizon; this.confidence = conf;
    }
}
