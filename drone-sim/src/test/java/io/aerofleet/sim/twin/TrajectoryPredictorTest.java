package io.aerofleet.sim.twin;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TrajectoryPredictorTest {
    @Test
    void testPredict() {
        TrajectoryPredictor predictor = new TrajectoryPredictor();
        List<double[]> points = predictor.predict(30.0, 120.0, 100, 0, 10, 30, 10);
        assertEquals(10, points.size());
        // Moving north (heading=0), lat should increase
        assertTrue(points.get(0)[0] > 30.0);
    }

    @Test
    void testPredictZeroVelocity() {
        TrajectoryPredictor predictor = new TrajectoryPredictor();
        List<double[]> points = predictor.predict(30.0, 120.0, 100, 0, 0, 30, 5);
        for (double[] p : points) {
            assertEquals(30.0, p[0], 0.001);
            assertEquals(120.0, p[1], 0.001);
        }
    }
}
