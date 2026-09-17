package io.aerofleet.cloud.twin;

import io.aerofleet.sim.twin.TrajectoryPredictor;
import org.springframework.stereotype.Service;
import java.util.*;

/** M13 预测性模拟 */
@Service
public class PredictionService {

    private final TrajectoryPredictor predictor = new TrajectoryPredictor();

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

    /**
     * 卡尔曼滤波 + 运动学模型增强的轨迹预测（M13 精化）。
     * <p>
     * 默认使用匀速（CV）模型，步数 = min(horizonSec, 30)，每步 1 秒。
     * 输出每步位置/速度/协方差/95% 置信椭圆，并据平均不确定性反算置信度。
     */
    public KalmanPredictionResult predictWithKalman(int sysid, double lat, double lon, double alt,
                                                    double heading, double velocity, int horizonSec) {
        return predictWithKalman(sysid, lat, lon, alt, heading, velocity, horizonSec,
                TrajectoryPredictor.MotionModel.CV);
    }

    /**
     * 指定运动学模型的卡尔曼预测。CV/CA/CT 三种模型可选。
     */
    public KalmanPredictionResult predictWithKalman(int sysid, double lat, double lon, double alt,
                                                    double heading, double velocity, int horizonSec,
                                                    TrajectoryPredictor.MotionModel model) {
        int steps = Math.min(horizonSec, 30);
        double[] vc = TrajectoryPredictor.velocityComponents(heading, velocity, lat);
        List<TrajectoryPredictor.PredictedPoint> pts = predictor.predictTrajectory(
                lat, lon, alt, vc[0], vc[1], 0, model, horizonSec, steps);
        double avgSemiMajor = pts.isEmpty() ? 0
                : pts.stream().mapToDouble(p -> p.confSemiMajorMeters).average().orElse(0);
        // 置信度：不确定性 0 时为 0.99，随椭圆增大衰减；下限 0.5
        double confidence = Math.max(0.5, 0.99 * Math.exp(-avgSemiMajor / 20.0));
        return new KalmanPredictionResult(sysid, pts, horizonSec, model, avgSemiMajor, confidence);
    }

    /**
     * 根据历史观测自动选择模型并预测。
     *
     * @param obs 历史观测，每行 [lat, lon, alt]
     * @param dt  采样间隔（秒）
     */
    public KalmanPredictionResult predictWithAutoModel(int sysid, double lat, double lon, double alt,
                                                      double heading, double velocity, int horizonSec,
                                                      List<double[]> obs, double dt) {
        TrajectoryPredictor.MotionModel model = predictor.selectModel(obs, dt);
        return predictWithKalman(sysid, lat, lon, alt, heading, velocity, horizonSec, model);
    }
}
