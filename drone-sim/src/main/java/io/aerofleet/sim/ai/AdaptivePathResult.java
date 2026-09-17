package io.aerofleet.sim.ai;

import java.util.List;

/**
 * 自适应航线结果。
 * <p>
 * 由 {@link AdaptivePathStrategy#adaptPath} 返回，包含风补偿 + 能耗优化 + Dubins 平滑后的
 * 修正路径、各段巡航速度、各段风修正航向与总能耗估算。
 * <p>
 * 不可变值对象（字段均为 final，集合引用不强制不可变但调用方不应修改）。
 */
public class AdaptivePathResult {
    /** 修正后路径点列表，每点为 [lat, lon, alt] */
    public final List<double[]> correctedPath;
    /** 各段巡航速度（m/s），长度 = correctedPath.size() - 1 */
    public final List<Double> segmentSpeeds;
    /** 各段风修正航向（度，0=正北，顺时针），长度 = correctedPath.size() - 1 */
    public final List<Double> correctedHeadings;
    /** 总能耗估算（归一化，无量纲，≥0） */
    public final double totalEnergyEstimate;

    public AdaptivePathResult(List<double[]> correctedPath, List<Double> segmentSpeeds,
                              List<Double> correctedHeadings, double totalEnergyEstimate) {
        this.correctedPath = correctedPath;
        this.segmentSpeeds = segmentSpeeds;
        this.correctedHeadings = correctedHeadings;
        this.totalEnergyEstimate = totalEnergyEstimate;
    }
}