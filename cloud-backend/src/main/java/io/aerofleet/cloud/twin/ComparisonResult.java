package io.aerofleet.cloud.twin;

public class ComparisonResult {
    public final int sysid;
    public final double positionErrorMeters;
    public final double velocityError;
    public final double headingError;

    public ComparisonResult(int sysid, double posErr, double velErr, double headErr) {
        this.sysid = sysid; this.positionErrorMeters = posErr; this.velocityError = velErr; this.headingError = headErr;
    }
}
