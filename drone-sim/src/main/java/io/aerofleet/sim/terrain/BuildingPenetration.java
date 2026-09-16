package io.aerofleet.sim.terrain;

import java.util.List;

/**
 * 建筑穿透损耗模型（FR-08, ITU-R P.2109 简化版）。
 * <p>
 * RF 信号穿过建筑物外墙时由材料与频率决定的穿透损耗。
 * <p>
 * 简化模型（§6.2.1 FR-08）：
 * <pre>
 *   L_bld = N × L_pen
 * </pre>
 * 其中：
 * <ul>
 *   <li>{@code N}：路径穿过建筑数</li>
 *   <li>{@code L_pen}：单次穿透损耗 (dB)，取自 {@link TerrainRfProfile#buildingPenetrationLoss}，
 *       混凝土墙典型 10-30 dB（§7.6 CONCRETE_PENETRATION_MIN/MAX）</li>
 * </ul>
 */
public final class BuildingPenetration {

    /** 混凝土穿透损耗下限 (dB, §7.6)。 */
    public static final double CONCRETE_PENETRATION_MIN = 10;
    /** 混凝土穿透损耗上限 (dB, §7.6)。 */
    public static final double CONCRETE_PENETRATION_MAX = 30;

    private BuildingPenetration() {
    }

    /**
     * 计算建筑穿透损耗 (dB)。
     *
     * @param type              地形类型
     * @param buildingsAlongPath 路径穿过建筑数
     * @return 穿透损耗 (dB)，非负
     */
    public static double lossDb(TerrainType type, int buildingsAlongPath) {
        double lossPerBuilding = type.rfProfile.buildingPenetrationLoss();
        if (lossPerBuilding <= 0 || buildingsAlongPath <= 0) {
            return 0;
        }
        return lossPerBuilding * buildingsAlongPath;
    }

    /**
     * 估算路径穿过建筑数。
     * <p>
     * 统计路径沿线地形类型序列中建筑地形网格数（OLD_CITY_DENSE / SUPER_HIGH_RISE）。
     *
     * @param types 路径沿线地形类型序列
     * @return 穿过建筑数
     */
    public static int countBuildings(List<TerrainType> types) {
        if (types == null || types.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TerrainType t : types) {
            if (t == TerrainType.OLD_CITY_DENSE || t == TerrainType.SUPER_HIGH_RISE) {
                count++;
            }
        }
        return count;
    }

    /**
     * 计算路径沿线建筑穿透总损耗。
     *
     * @param types 路径沿线地形类型序列
     * @return 总穿透损耗 (dB)
     */
    public static double lossDbAlongPath(List<TerrainType> types) {
        if (types == null || types.isEmpty()) {
            return 0;
        }
        double totalLoss = 0;
        for (TerrainType t : types) {
            if (t == TerrainType.OLD_CITY_DENSE || t == TerrainType.SUPER_HIGH_RISE) {
                totalLoss += t.rfProfile.buildingPenetrationLoss();
            }
        }
        return totalLoss;
    }
}