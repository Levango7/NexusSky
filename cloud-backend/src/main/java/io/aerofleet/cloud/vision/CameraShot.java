package io.aerofleet.cloud.vision;

import java.util.List;

/**
 * 相机帧辅助 record（M3 感知成像增强，FR-01）。
 * <p>
 * VisionSource.detect() 的相机帧输入，承载投影目标列表 + 相机位置/姿态元数据。
 * 与 drone-sim {@code CameraModel.Shot} 适配（CaptureService 转换层）。
 *
 * @param frameSeq        帧序号
 * @param timeMs          帧时间戳（ms）
 * @param lat             相机纬度
 * @param lon             相机经度
 * @param altM            相机高度（米，above ground）
 * @param droneRollDeg    无人机 roll（度）
 * @param dronePitchDeg   无人机 pitch（度）
 * @param droneYawDeg     无人机 yaw（度）
 * @param gimbalPitchDeg  云台 pitch（度）
 * @param gimbalYawDeg    云台 yaw（度）
 * @param projectedTargets 投影目标列表（视野内目标 + 像素坐标）
 */
public record CameraShot(long frameSeq, long timeMs,
                         double lat, double lon, double altM,
                         double droneRollDeg, double dronePitchDeg, double droneYawDeg,
                         double gimbalPitchDeg, double gimbalYawDeg,
                         List<ProjectedTarget> projectedTargets) {

    /** 单个投影目标：像素坐标 + 类别 + 真值 ID + 真值经纬度。 */
    public record ProjectedTarget(int id, String kind,
                                  double u, double v,
                                  double targetLat, double targetLon) {
        public ProjectedTarget {
            if (kind == null || kind.isEmpty()) {
                throw new IllegalArgumentException("kind must be non-empty");
            }
        }
    }
}