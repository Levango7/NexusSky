package io.aerofleet.sim;

import java.util.List;

/**
 * 热成像载荷抽象接口（M3 感知成像增强，FR-08）。
 * <p>
 * 热成像遥感载荷的接入点：输入温度场矩阵，输出温度统计（均值/最小/最高/标准差）
 * + 热点列表（位置 + 温度 + 面积）。
 * <p>
 * 实现类：{@link SimulatedThermalSource}（模拟器合成温度场 + 热点检测）。
 */
public interface ThermalSource {

    /**
     * 分析温度场（FR-08）。
     * <p>
     * 计算均值/最小/最高/标准差 + 热点检测（默认阈值=均值+2σ）。
     *
     * @param thermalMatrix 温度场矩阵（℃，非 null，矩形）
     * @return 温度统计 + 热点列表
     */
    ThermalResult analyze(double[][] thermalMatrix);

    /**
     * 热点检测（FR-08）。
     * <p>
     * 检测局部极大值超阈值的像素，聚合为热点（位置 + 温度 + 面积）。
     * 温度场全均匀时返回空列表。
     *
     * @param matrix     温度场矩阵（℃）
     * @param thresholdC 热点阈值（℃），高于此值且为局部极大值的像素为热点
     * @return 热点列表（空列表表示无热点）
     */
    List<Hotspot> detectHotspots(double[][] matrix, double thresholdC);

    /** 温度场分析结果（统计 + 热点列表）。 */
    record ThermalResult(double mean, double min, double max, double stdDev,
                         List<Hotspot> hotspots) {
    }

    /** 单个热点（位置 + 温度 + 面积）。 */
    record Hotspot(int u, int v, double tempC, int areaPx) {
    }
}