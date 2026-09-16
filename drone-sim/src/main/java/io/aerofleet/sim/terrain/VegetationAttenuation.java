package io.aerofleet.sim.terrain;

/**
 * 植被衰减模型（FR-07, ITU-R P.833 简化版）。
 * <p>
 * RF 信号穿过森林/植被时由吸收与散射引起的频率相关路径损耗。
 * <p>
 * 简化模型（§6.2.1 FR-07）：
 * <pre>
 *   L_veg = α_veg × d_veg
 * </pre>
 * 其中：
 * <ul>
 *   <li>{@code α_veg}：植被衰减系数 (dB/m)，取自 {@link TerrainRfProfile#vegetationAttenuationCoeff}，
 *       森林典型 0.05-0.2 dB/m（§7.6 FOREST_ATTENUATION_MIN/MAX）</li>
 *   <li>{@code d_veg}：穿过植被的路径长度 (m)</li>
 * </ul>
 * <p>
 * 异常处理（§6.2.3）：{@code d_veg > pathTotal} 时截断为 {@code pathTotal}。
 */
public final class VegetationAttenuation {

    /** 森林衰减系数下限 (dB/m, §7.6)。 */
    public static final double FOREST_ATTENUATION_MIN = 0.05;
    /** 森林衰减系数上限 (dB/m, §7.6)。 */
    public static final double FOREST_ATTENUATION_MAX = 0.2;

    private VegetationAttenuation() {
    }

    /**
     * 计算植被衰减 (dB)。
     *
     * @param type                         地形类型
     * @param pathLengthThroughVegetationM 穿过植被的路径长度 (m)
     * @return 衰减量 (dB)，非负
     */
    public static double lossDb(TerrainType type, double pathLengthThroughVegetationM) {
        double coeff = type.rfProfile.vegetationAttenuationCoeff();
        if (coeff <= 0 || pathLengthThroughVegetationM <= 0) {
            return 0;
        }
        return coeff * pathLengthThroughVegetationM;
    }

    /**
     * 计算路径穿过植被的有效长度。
     * <p>
     * 统计路径沿线地形类型序列中植被网格占比，乘以总路径长度。
     *
     * @param types       路径沿线地形类型序列
     * @param totalPathM  路径总长度 (m)
     * @return 穿过植被的有效长度 (m)
     */
    public static double vegetationDistance(java.util.List<TerrainType> types, double totalPathM) {
        if (types == null || types.isEmpty() || totalPathM <= 0) {
            return 0;
        }
        int vegCells = 0;
        for (TerrainType t : types) {
            if (t.rfProfile.vegetationAttenuationCoeff() > 0) {
                vegCells++;
            }
        }
        double dist = totalPathM * vegCells / types.size();
        // 异常保护（§6.2.3）：截断为路径总长
        return Math.min(dist, totalPathM);
    }
}