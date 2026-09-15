package io.aerofleet.cloud.vision;

/**
 * 相机位姿辅助 record（M3 感知成像增强，FR-01）。
 * <p>
 * VisionSource.detect() 的相机位姿输入，承载无人机姿态 + 云台姿态。
 * 字段单位均为度（与 drone-sim CameraModel.Shot 一致）。
 *
 * @param roll         无人机 roll（度）
 * @param pitch        无人机 pitch（度）
 * @param yaw          无人机 yaw（度）
 * @param gimbalPitch  云台 pitch（度，0=正下，90=水平）
 * @param gimbalYaw    云台 yaw（度）
 */
public record CameraPose(double roll, double pitch, double yaw,
                         double gimbalPitch, double gimbalYaw) {
}