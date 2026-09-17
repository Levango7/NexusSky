package io.aerofleet.cloud.twin;

import io.aerofleet.sim.twin.TrajectoryPredictor;
import java.util.List;

/**
 * 卡尔曼滤波 + 运动学模型增强的预测结果（M13 精化）。
 * <p>
 * 每个轨迹点带位置、速度、协方差与 95% 置信椭圆，并标注所用的运动学模型。
 */
public class KalmanPredictionResult {
    public final int sysid;
    /** 每步预测点（含位置/速度/不确定性） */
    public final List<TrajectoryPredictor.PredictedPoint> points;
    public final int horizonSec;
    /** 使用的运动学模型 */
    public final TrajectoryPredictor.MotionModel model;
    /** 平均 95% 置信椭圆半长轴（米），不确定性度量 */
    public final double avgSemiMajorMeters;
    /** 置信度（0-1），不确定性越小越高 */
    public final double confidence;

    public KalmanPredictionResult(int sysid, List<TrajectoryPredictor.PredictedPoint> points,
                                  int horizonSec, TrajectoryPredictor.MotionModel model,
                                  double avgSemiMajorMeters, double confidence) {
        this.sysid = sysid;
        this.points = points;
        this.horizonSec = horizonSec;
        this.model = model;
        this.avgSemiMajorMeters = avgSemiMajorMeters;
        this.confidence = confidence;
    }
}