package io.aerofleet.cloud.twin;

import org.springframework.stereotype.Service;
import java.util.*;

/** M13 预测性模拟 */
@Service
public class PredictionService {
    public PredictionResult predict(int sysid, double lat, double lon, double alt, double heading, double velocity, int horizonSec) {
        List<double[]> points = new ArrayList<>();
        int numPoints = Math.min(horizonSec, 30);
        for (int i = 1; i <= numPoints; i++) {
            double dt = i;
            double dLat = (velocity * dt * Math.cos(Math.toRadians(heading))) / 111000;
            double dLon = (velocity * dt * Math.sin(Math.toRadians(heading))) / (111000 * Math.cos(Math.toRadians(lat)));
            points.add(new double[]{lat + dLat, lon + dLon, alt});
        }
        return new PredictionResult(sysid, points, horizonSec, 0.8);
    }
}
