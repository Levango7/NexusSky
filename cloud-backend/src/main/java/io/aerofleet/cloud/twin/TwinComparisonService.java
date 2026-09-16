package io.aerofleet.cloud.twin;

import org.springframework.stereotype.Service;

/** M13 虚实对比 */
@Service
public class TwinComparisonService {
    public ComparisonResult compare(TwinState actual, TwinState predicted) {
        double posErr = Math.sqrt(Math.pow(actual.lat - predicted.lat, 2) + Math.pow(actual.lon - predicted.lon, 2)) * 111000;
        double velErr = Math.abs(actual.velocity - predicted.velocity);
        double headErr = Math.abs(actual.heading - predicted.heading);
        return new ComparisonResult(actual.sysid, posErr, velErr, headErr);
    }
}
