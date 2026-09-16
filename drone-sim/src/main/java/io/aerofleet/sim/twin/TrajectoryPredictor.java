package io.aerofleet.sim.twin;

import java.util.ArrayList;
import java.util.List;

/** M13 轨迹预测器 */
public class TrajectoryPredictor {
    public List<double[]> predict(double lat, double lon, double alt, double heading, double velocity, int horizonSec, int numPoints) {
        List<double[]> points = new ArrayList<>();
        for (int i = 1; i <= numPoints; i++) {
            double dt = (double) horizonSec * i / numPoints;
            double dLat = (velocity * dt * Math.cos(Math.toRadians(heading))) / 111000;
            double dLon = (velocity * dt * Math.sin(Math.toRadians(heading))) / (111000 * Math.cos(Math.toRadians(lat)));
            points.add(new double[]{lat + dLat, lon + dLon, alt});
        }
        return points;
    }
}
