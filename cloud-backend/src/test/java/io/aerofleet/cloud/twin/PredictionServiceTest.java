package io.aerofleet.cloud.twin;

import io.aerofleet.sim.twin.TrajectoryPredictor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PredictionService 预测性模拟单测（M13）。
 * <p>
 * 直接实例化 Service（无 Spring 上下文），覆盖轨迹预测/航向/边界，
 * 以及卡尔曼滤波 + 运动学模型增强（CV/CA/CT）+ 不确定性量化。
 */
@DisplayName("PredictionService 预测性模拟 (M13)")
class PredictionServiceTest {

    private PredictionService service;

    @BeforeEach
    void setUp() {
        service = new PredictionService();
    }

    @Test
    @DisplayName("predict 返回的 sysid 与输入一致")
    void predictPreservesSysid() {
        PredictionResult result = service.predict(7, 30.0, 120.0, 100.0, 0, 10, 10);

        assertThat(result.sysid).isEqualTo(7);
    }

    @Test
    @DisplayName("predict horizonSec 内点数 = min(horizonSec, 30)")
    void predictPointCountCappedAt30() {
        PredictionResult small = service.predict(1, 30.0, 120.0, 100.0, 0, 10, 5);
        assertThat(small.trajectoryPoints).hasSize(5);
        assertThat(small.horizonSec).isEqualTo(5);

        PredictionResult large = service.predict(1, 30.0, 120.0, 100.0, 0, 10, 60);
        assertThat(large.trajectoryPoints).hasSize(30);
        assertThat(large.horizonSec).isEqualTo(60);
    }

    @Test
    @DisplayName("predict 航向 0°（正北）时纬度递增、经度不变")
    void predictHeadingNorthMovesLatitudeOnly() {
        PredictionResult result = service.predict(1, 30.0, 120.0, 100.0, 0, 10, 3);

        List<double[]> pts = result.trajectoryPoints;
        assertThat(pts).hasSize(3);
        for (double[] p : pts) {
            assertThat(p[0]).isGreaterThan(30.0); // lat 递增
            assertThat(p[1]).isEqualTo(120.0);    // lon 不变
            assertThat(p[2]).isEqualTo(100.0);    // alt 不变
        }
        // 纬度递增顺序
        assertThat(pts.get(1)[0]).isGreaterThan(pts.get(0)[0]);
        assertThat(pts.get(2)[0]).isGreaterThan(pts.get(1)[0]);
    }

    @Test
    @DisplayName("predict 航向 90°（正东）时经度递增、纬度不变")
    void predictHeadingEastMovesLongitudeOnly() {
        PredictionResult result = service.predict(1, 30.0, 120.0, 100.0, 90, 10, 3);

        List<double[]> pts = result.trajectoryPoints;
        assertThat(pts).hasSize(3);
        for (double[] p : pts) {
            assertThat(p[0]).isEqualTo(30.0);    // lat 不变
            assertThat(p[1]).isGreaterThan(120.0); // lon 递增
        }
    }

    @Test
    @DisplayName("predict 速度越大，同 horizon 下位移越大")
    void predictHigherSpeedGivesLargerDisplacement() {
        PredictionResult slow = service.predict(1, 30.0, 120.0, 100.0, 0, 5, 10);
        PredictionResult fast = service.predict(1, 30.0, 120.0, 100.0, 0, 20, 10);

        double slowLastLat = slow.trajectoryPoints.get(slow.trajectoryPoints.size() - 1)[0];
        double fastLastLat = fast.trajectoryPoints.get(fast.trajectoryPoints.size() - 1)[0];
        assertThat(fastLastLat).isGreaterThan(slowLastLat);
    }

    @Test
    @DisplayName("predict 置信度固定为 0.8")
    void predictConfidenceAlways08() {
        PredictionResult result = service.predict(1, 30.0, 120.0, 100.0, 45, 10, 10);
        assertThat(result.confidence).isEqualTo(0.8);
    }

    @Test
    @DisplayName("predict horizonSec 为 0 时轨迹为空")
    void predictZeroHorizonGivesEmptyTrajectory() {
        PredictionResult result = service.predict(1, 30.0, 120.0, 100.0, 0, 10, 0);
        assertThat(result.trajectoryPoints).isEmpty();
    }

    // ===================== 卡尔曼滤波 + 运动学模型增强 =====================

    @Nested
    @DisplayName("predictWithKalman 卡尔曼滤波预测")
    class KalmanPrediction {

        @Test
        @DisplayName("返回的 sysid 与输入一致")
        void preservesSysid() {
            KalmanPredictionResult r = service.predictWithKalman(42, 30.0, 120.0, 100.0, 0, 10, 10);
            assertThat(r.sysid).isEqualTo(42);
        }

        @Test
        @DisplayName("点数 = min(horizonSec, 30)，horizonSec=0 时为空")
        void pointCount() {
            KalmanPredictionResult r = service.predictWithKalman(1, 30.0, 120.0, 100.0, 0, 10, 5);
            assertThat(r.points).hasSize(5);
            assertThat(r.horizonSec).isEqualTo(5);

            KalmanPredictionResult capped = service.predictWithKalman(1, 30.0, 120.0, 100.0, 0, 10, 60);
            assertThat(capped.points).hasSize(30);

            KalmanPredictionResult empty = service.predictWithKalman(1, 30.0, 120.0, 100.0, 0, 10, 0);
            assertThat(empty.points).isEmpty();
        }

        @Test
        @DisplayName("正北航向时纬度递增、经度近似不变")
        void headingNorthMovesLatitude() {
            KalmanPredictionResult r = service.predictWithKalman(1, 30.0, 120.0, 100.0, 0, 10, 5);
            assertThat(r.points).hasSize(5);
            for (TrajectoryPredictor.PredictedPoint p : r.points) {
                assertThat(p.lat).isGreaterThan(30.0);
                assertThat(p.lon).isCloseTo(120.0, org.assertj.core.data.Offset.offset(1e-6));
            }
            // 纬度单调递增
            for (int i = 1; i < r.points.size(); i++) {
                assertThat(r.points.get(i).lat).isGreaterThan(r.points.get(i - 1).lat);
            }
        }

        @Test
        @DisplayName("正东航向时经度递增、纬度近似不变")
        void headingEastMovesLongitude() {
            KalmanPredictionResult r = service.predictWithKalman(1, 30.0, 120.0, 100.0, 90, 10, 5);
            for (TrajectoryPredictor.PredictedPoint p : r.points) {
                assertThat(p.lon).isGreaterThan(120.0);
                assertThat(p.lat).isCloseTo(30.0, org.assertj.core.data.Offset.offset(1e-6));
            }
        }

        @Test
        @DisplayName("CV 模型匀速：速度分量保持近似不变")
        void cvKeepsVelocityConstant() {
            KalmanPredictionResult r = service.predictWithKalman(1, 30.0, 120.0, 100.0, 0, 10, 8,
                    TrajectoryPredictor.MotionModel.CV);
            assertThat(r.model).isEqualTo(TrajectoryPredictor.MotionModel.CV);
            double vLat0 = r.points.get(0).vLat;
            double vLatLast = r.points.get(r.points.size() - 1).vLat;
            // 匀速模型无加速度，速度应保持（过程噪声仅影响协方差不影响状态均值）
            assertThat(vLatLast).isCloseTo(vLat0, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("不确定性随预测时间增大：椭圆半长轴单调不减")
        void uncertaintyGrowsOverTime() {
            KalmanPredictionResult r = service.predictWithKalman(1, 30.0, 120.0, 100.0, 45, 10, 10);
            for (int i = 1; i < r.points.size(); i++) {
                assertThat(r.points.get(i).confSemiMajorMeters)
                        .isGreaterThanOrEqualTo(r.points.get(i - 1).confSemiMajorMeters - 1e-9);
            }
            // 整体首末显著增长
            assertThat(r.points.get(r.points.size() - 1).confSemiMajorMeters)
                    .isGreaterThan(r.points.get(0).confSemiMajorMeters);
        }

        @Test
        @DisplayName("95% 置信椭圆半长轴 >= 半短轴")
        void ellipseSemiMajorNotSmallerThanMinor() {
            KalmanPredictionResult r = service.predictWithKalman(1, 30.0, 120.0, 100.0, 30, 12, 6);
            for (TrajectoryPredictor.PredictedPoint p : r.points) {
                assertThat(p.confSemiMajorMeters)
                        .isGreaterThanOrEqualTo(p.confSemiMinorMeters - 1e-9);
                assertThat(p.confSemiMajorMeters).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("置信度落在 [0.5, 0.99]")
        void confidenceInRange() {
            KalmanPredictionResult r = service.predictWithKalman(1, 30.0, 120.0, 100.0, 0, 10, 10);
            assertThat(r.confidence).isBetween(0.5, 0.99);
        }

        @Test
        @DisplayName("每点位置协方差为 3×3 对称半正定（对角非负）")
        void positionCovarianceValid() {
            KalmanPredictionResult r = service.predictWithKalman(1, 30.0, 120.0, 100.0, 0, 10, 5);
            for (TrajectoryPredictor.PredictedPoint p : r.points) {
                double[][] c = p.positionCovariance;
                assertThat(c).hasDimensions(3, 3);
                for (int i = 0; i < 3; i++) assertThat(c[i][i]).isGreaterThanOrEqualTo(0);
                // 对称
                assertThat(c[0][1]).isCloseTo(c[1][0], org.assertj.core.data.Offset.offset(1e-12));
                assertThat(c[0][2]).isCloseTo(c[2][0], org.assertj.core.data.Offset.offset(1e-12));
                assertThat(c[1][2]).isCloseTo(c[2][1], org.assertj.core.data.Offset.offset(1e-12));
            }
        }

        @Test
        @DisplayName("CA 模型带正加速度时纬度位移比 CV 更大")
        void caWithAccelerationDisplacesMore() {
            // 通过直接构造 TrajectoryPredictor 对比 CV 与 CA
            TrajectoryPredictor predictor = new TrajectoryPredictor();
            double lat = 30.0, lon = 120.0, alt = 100.0;
            double[] vc = TrajectoryPredictor.velocityComponents(0, 10, lat); // 正北 10m/s
            int horizon = 10, steps = 10;
            List<TrajectoryPredictor.PredictedPoint> cv = predictor.predictTrajectory(
                    lat, lon, alt, vc[0], vc[1], 0, TrajectoryPredictor.MotionModel.CV, horizon, steps);
            // CA 给纬度方向 0.5 m/s² 加速度，位移应比 CV 更大
            List<TrajectoryPredictor.PredictedPoint> ca = predictor.predictTrajectory(
                    lat, lon, alt, vc[0], vc[1], 0, 0.5 / 111000, 0, 0, 0,
                    TrajectoryPredictor.MotionModel.CA, horizon, steps,
                    0.5, 0.5, 0.2, 0.01, 3.0);
            double cvLastLat = cv.get(cv.size() - 1).lat;
            double caLastLat = ca.get(ca.size() - 1).lat;
            assertThat(caLastLat).isGreaterThan(cvLastLat);
        }

        @Test
        @DisplayName("CT 模型转弯：航向角随时间改变")
        void ctTurnsHeading() {
            TrajectoryPredictor predictor = new TrajectoryPredictor();
            double lat = 30.0, lon = 120.0, alt = 100.0;
            double[] vc = TrajectoryPredictor.velocityComponents(0, 10, lat); // 初始正北
            double turnRate = Math.toRadians(10); // 10°/s
            List<TrajectoryPredictor.PredictedPoint> pts = predictor.predictTrajectory(
                    lat, lon, alt, vc[0], vc[1], 0, 0, 0, 0, turnRate,
                    TrajectoryPredictor.MotionModel.CT, 5, 5,
                    0.5, 0.5, 0.2, 0.01, 3.0);
            assertThat(pts).hasSize(5);
            // 初始航向 0°（正北），5 秒后应偏转约 50°，经度应有明显增加
            assertThat(pts.get(pts.size() - 1).lon).isGreaterThan(120.0 + 1e-6);
            // 模型标记为 CT
            for (TrajectoryPredictor.PredictedPoint p : pts) {
                assertThat(p.model).isEqualTo(TrajectoryPredictor.MotionModel.CT);
            }
        }
    }

    @Nested
    @DisplayName("predictWithAutoModel 自动模型选择")
    class AutoModelSelection {

        @Test
        @DisplayName("直线段观测选择 CV")
        void straightLineSelectsCV() {
            TrajectoryPredictor predictor = new TrajectoryPredictor();
            List<double[]> obs = new ArrayList<>();
            // 正北匀速 1m/s，dt=1s，1m ≈ 9e-6 度
            double dLat = 1.0 / 111000.0;
            for (int i = 0; i < 6; i++) obs.add(new double[]{30.0 + i * dLat, 120.0, 100.0});
            assertThat(predictor.selectModel(obs, 1.0)).isEqualTo(TrajectoryPredictor.MotionModel.CV);
        }

        @Test
        @DisplayName("转弯段观测选择 CT")
        void turningSelectsCT() {
            TrajectoryPredictor predictor = new TrajectoryPredictor();
            List<double[]> obs = new ArrayList<>();
            // 沿圆弧采样：半径 10m，角速度 20°/s，dt=0.5s
            double r = 10.0, w = Math.toRadians(20), dt = 0.5;
            for (int i = 0; i < 8; i++) {
                double ang = w * i * dt;
                double dx = r * Math.sin(ang);
                double dy = r * (1 - Math.cos(ang));
                obs.add(new double[]{30.0 + dy / 111000.0, 120.0 + dx / (111000.0 * Math.cos(Math.toRadians(30.0))), 100.0});
            }
            assertThat(predictor.selectModel(obs, dt)).isEqualTo(TrajectoryPredictor.MotionModel.CT);
        }

        @Test
        @DisplayName("加减速段观测选择 CA")
        void acceleratingSelectsCA() {
            TrajectoryPredictor predictor = new TrajectoryPredictor();
            List<double[]> obs = new ArrayList<>();
            // 正北方向加速度 2 m/s²，dt=1s：s(t)=0.5*a*t²
            double a = 2.0, dt = 1.0;
            for (int i = 0; i < 6; i++) {
                double s = 0.5 * a * i * dt * i * dt;
                obs.add(new double[]{30.0 + s / 111000.0, 120.0, 100.0});
            }
            assertThat(predictor.selectModel(obs, dt)).isEqualTo(TrajectoryPredictor.MotionModel.CA);
        }

        @Test
        @DisplayName("predictWithAutoModel 直线场景使用 CV 模型")
        void autoPredictStraightLineUsesCV() {
            List<double[]> obs = new ArrayList<>();
            double dLat = 1.0 / 111000.0;
            for (int i = 0; i < 6; i++) obs.add(new double[]{30.0 + i * dLat, 120.0, 100.0});
            KalmanPredictionResult r = service.predictWithAutoModel(1, 30.0, 120.0, 100.0, 0, 1, 5, obs, 1.0);
            assertThat(r.model).isEqualTo(TrajectoryPredictor.MotionModel.CV);
            assertThat(r.points).hasSize(5);
        }
    }
}
