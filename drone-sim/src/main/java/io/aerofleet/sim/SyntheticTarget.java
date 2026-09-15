package io.aerofleet.sim;

/**
 * 合成空域目标（M4 硬件抽象，FR-01/FR-05）。
 * <p>
 * 雷达扫描的输入：合成目标世界中的目标，由 {@link TargetSimulator} 转换产出。
 * 位置为相对雷达的本地北/东/高度（米），速度为北/东/垂直分量（m/s）。
 *
 * @param id    目标 ID
 * @param north 北向坐标（米）
 * @param east  东向坐标（米）
 * @param alt   高度（米，相对起飞点）
 * @param velN  北向速度（m/s）
 * @param velE  东向速度（m/s）
 * @param velZ  垂直速度（m/s，+up）
 * @param kind  目标类型（"vehicle"/"person"/"building"）
 */
public record SyntheticTarget(int id, double north, double east, double alt,
                              double velN, double velE, double velZ, String kind) {
}