package io.aerofleet.sim;

import java.util.List;

/**
 * 相控阵雷达数据源抽象接口（M4 硬件抽象，FR-01）。
 * <p>
 * 输入扫描配置 + 空域目标列表，输出目标报告列表。
 * {@link SimulatedRadar} 为模拟实现（合成目标探测）。
 * 未来接真实雷达硬件只需新增实现类，不改硬件数据主流程。
 */
public interface PhasedArrayRadar {

    /**
     * 执行一次扫描，产出目标报告列表（FR-01/FR-05）。
     *
     * @param config  扫描配置（模式/波束参数/探测距离）
     * @param targets 空域目标列表（合成目标世界）
     * @return 目标报告列表（空列表表示无目标，非 null）
     */
    List<RadarTargetReport> scan(RadarScanConfig config, List<SyntheticTarget> targets);

    /** 当前波束方位角（扫描过程中变化，FR-04）。 */
    double currentBeamAzim();
}