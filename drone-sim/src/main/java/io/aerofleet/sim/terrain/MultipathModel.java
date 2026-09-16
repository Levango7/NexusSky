package io.aerofleet.sim.terrain;

/**
 * 多径效应模型（FR-09, FR-10, §7.6）。
 * <p>
 * NLOS 场景下多径效应的严重程度系数，影响多径延迟扩展与信号衰落。
 * <p>
 * 模型：
 * <ul>
 *   <li>沼泽：地面反射系数 0.7-0.9（§7.6 SWAMP_REFLECTION_MIN/MAX），多径因子 0.9，
 *       延迟扩展 0.5-1.0 μs</li>
 *   <li>NLOS 一般：延迟扩展 0.1-1.0 μs（§7.6 MULTIPATH_DELAY_MIN/MAX），
 *       损耗 = {@code multipathFactor × 10 dB}</li>
 *   <li>LOS：延迟扩展 0，损耗 0</li>
 * </ul>
 */
public final class MultipathModel {

    /** 沼泽反射系数下限 (§7.6)。 */
    public static final double SWAMP_REFLECTION_MIN = 0.7;
    /** 沼泽反射系数上限 (§7.6)。 */
    public static final double SWAMP_REFLECTION_MAX = 0.9;
    /** 多径延迟扩展下限 (μs, §7.6)。 */
    public static final double MULTIPATH_DELAY_MIN = 0.1;
    /** 多径延迟扩展上限 (μs, §7.6)。 */
    public static final double MULTIPATH_DELAY_MAX = 1.0;
    /** 多径损耗基准 (dB)。 */
    private static final double MULTIPATH_LOSS_BASE_DB = 10.0;

    private MultipathModel() {
    }

    /**
     * 计算多径延迟扩展 (μs)。
     *
     * @param type 地形类型
     * @param nlos 是否 NLOS
     * @return 延迟扩展 (μs)，LOS 时为 0
     */
    public static double delaySpreadUs(TerrainType type, boolean nlos) {
        if (!nlos) {
            return 0;  // LOS 无多径
        }
        double factor = type.rfProfile.multipathFactor();
        if (factor <= 0) {
            return 0;
        }
        // 按多径因子线性映射到 [MIN, MAX]
        double delay = MULTIPATH_DELAY_MIN + factor * (MULTIPATH_DELAY_MAX - MULTIPATH_DELAY_MIN);
        return Math.max(MULTIPATH_DELAY_MIN, Math.min(MULTIPATH_DELAY_MAX, delay));
    }

    /**
     * 计算多径损耗 (dB)。
     *
     * @param type 地形类型
     * @param nlos 是否 NLOS
     * @return 多径损耗 (dB)，非负；LOS 时为 0
     */
    public static double lossDb(TerrainType type, boolean nlos) {
        if (!nlos) {
            return 0;  // LOS 无多径损耗
        }
        double factor = type.rfProfile.multipathFactor();
        if (factor <= 0) {
            return 0;
        }
        return factor * MULTIPATH_LOSS_BASE_DB;
    }

    /**
     * 沼泽场景判定：反射系数是否在沼泽范围。
     *
     * @param type 地形类型
     * @return true 当反射系数 ∈ [0.7, 0.9]
     */
    public static boolean isSwampReflection(TerrainType type) {
        double r = type.rfProfile.groundReflectionCoeff();
        return r >= SWAMP_REFLECTION_MIN && r <= SWAMP_REFLECTION_MAX;
    }
}
