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

    // ===================== 卡尔曼滤波器核心单测 =====================

    @Test
    void kalmanFilterUpdateConvergesToTruePosition() {
        // 真值：从 (30,120,100) 以 10m/s 正北匀速运动，每秒采样一次
        double lat0 = 30.0, lon0 = 120.0, alt0 = 100.0;
        double[] vc = TrajectoryPredictor.velocityComponents(0, 10, lat0); // 正北
        double dt = 1.0;
        TrajectoryPredictor.KalmanFilter kf = TrajectoryPredictor.buildCV(
                lat0, lon0, alt0, vc[0], vc[1], 0, dt, 0.5, 0.5, 3.0);

        double mplLat = 111000.0;
        for (int i = 1; i <= 20; i++) {
            kf.predict();
            // 真值位置 + 小观测噪声
            double trueLat = lat0 + (10 * i) / mplLat;
            kf.update(new double[]{trueLat + 1e-7, lon0, alt0});
        }
        double[] x = kf.getState();
        // 滤波后位置应收敛到第 20 秒真值（容差 1e-4 度 ≈ 11m，足够宽松）
        double expectedLat = lat0 + (10 * 20) / mplLat;
        assertEquals(expectedLat, x[0], 1e-4);
        assertEquals(lon0, x[1], 1e-4);
        assertEquals(alt0, x[2], 1e-4);
    }

    @Test
    void kalmanFilterStateAndCovarianceShapes() {
        TrajectoryPredictor.KalmanFilter kf = TrajectoryPredictor.buildCV(30, 120, 100, 0, 0, 0, 1, 0.5, 0.5, 3);
        assertEquals(6, kf.stateDim());
        assertEquals(3, kf.measDim());
        assertEquals(6, kf.getState().length);
        assertEquals(6, kf.getCovariance().length);
        assertEquals(6, kf.getCovariance()[0].length);
    }

    @Test
    void matrixInverseRecoversIdentity() {
        double[][] A = {{4, 2, 0}, {1, 5, 1}, {0, 2, 6}};
        double[][] inv = TrajectoryPredictor.inverse(A);
        double[][] prod = TrajectoryPredictor.matMul(A, inv);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) {
                double expected = (i == j) ? 1.0 : 0.0;
                assertEquals(expected, prod[i][j], 1e-9);
            }
    }

    @Test
    void confidenceEllipseNonNegativeAndMajorGeqMinor() {
        double[][] cov = {{1e-8, 0, 0}, {0, 2e-8, 0}, {0, 0, 3e-8}};
        double[] ell = TrajectoryPredictor.confidenceEllipseMeters(cov, 30.0);
        assertTrue(ell[0] >= ell[1] - 1e-9, "半长轴应 >= 半短轴");
        assertTrue(ell[0] > 0);
        assertTrue(ell[1] > 0);
    }

    @Test
    void predictTrajectoryEmptyForZeroHorizon() {
        TrajectoryPredictor p = new TrajectoryPredictor();
        List<TrajectoryPredictor.PredictedPoint> pts = p.predictTrajectory(
                30, 120, 100, 0, 0, 0, TrajectoryPredictor.MotionModel.CV, 0, 5);
        assertTrue(pts.isEmpty());
    }

    @Test
    void velocityComponentsNorthSouth() {
        double[] north = TrajectoryPredictor.velocityComponents(0, 10, 30.0);
        assertTrue(north[0] > 0, "正北 vLat>0");
        assertEquals(0, north[1], 1e-12, "正北 vLon=0");
        double[] south = TrajectoryPredictor.velocityComponents(180, 10, 30.0);
        assertTrue(south[0] < 0, "正南 vLat<0");
    }
}
