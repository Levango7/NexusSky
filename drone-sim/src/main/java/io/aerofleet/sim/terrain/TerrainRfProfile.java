package io.aerofleet.sim.terrain;

/**
 * 地形 RF 特性参数（FR-02, §7.2）。
 * <p>
 * 每种 {@link TerrainType} 关联一组 RF 特性参数，用于增强 RF 传播估算。
 * <p>
 * 字段约束（§7.2）：
 * <ul>
 *   <li>{@code vegetationAttenuationCoeff}：非负，单位 dB/m，森林典型 0.05-0.2，非植被地形为 0</li>
 *   <li>{@code buildingPenetrationLoss}：非负，单位 dB，混凝土墙典型 10-30，无建筑地形为 0</li>
 *   <li>{@code groundReflectionCoeff}：[0, 1]，沼泽典型 0.7-0.9</li>
 *   <li>{@code multipathFactor}：非负，NLOS 典型 0.1-1.0，LOS 为 0</li>
 * </ul>
 *
 * @param vegetationAttenuationCoeff 植被衰减系数 (dB/m)
 * @param buildingPenetrationLoss   建筑穿透损耗 (dB)
 * @param groundReflectionCoeff     地面反射系数 [0,1]
 * @param multipathFactor           多径因子
 */
public record TerrainRfProfile(
        double vegetationAttenuationCoeff,
        double buildingPenetrationLoss,
        double groundReflectionCoeff,
        double multipathFactor
) {
    /** 安全构造：钳制到物理合理范围。 */
    public TerrainRfProfile {
        vegetationAttenuationCoeff = Math.max(0, vegetationAttenuationCoeff);
        buildingPenetrationLoss = Math.max(0, buildingPenetrationLoss);
        groundReflectionCoeff = Math.max(0, Math.min(1, groundReflectionCoeff));
        multipathFactor = Math.max(0, multipathFactor);
    }
}