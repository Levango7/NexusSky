package io.aerofleet.sim.terrain;

import io.aerofleet.sim.GeoUtil;
import io.aerofleet.sim.RadioEnvironment;
import io.aerofleet.sim.TerrainModel;

import java.util.List;

/**
 * 增强 RF 传播模型（FR-12, FR-13, FR-14, §6.2）。
 * <p>
 * 在既有 {@link RadioEnvironment} 基础上叠加地形类型相关的衰减分量：
 * 植被衰减 / 建筑穿透 / 多径损耗，总衰减为 5 项叠加（FR-12）。
 * <p>
 * 退化兼容（FR-14, AC-N05）：当 {@code terrainGrid == null}（未建图或数据缺失）时，
 * 增强模型退化为现有 {@code RadioEnvironment.rssiDbm()} 输出，完全一致。
 * <p>
 * 衰减叠加公式：
 * <pre>
 *   RSSI = TX_EIRP - FSPL - L_shadow - L_veg - L_bld - L_multipath
 * </pre>
 */
public final class EnhancedRadioEnvironment {

    /** 默认频率 (MHz, §7.6 DEFAULT_FREQ_MHZ)。 */
    public static final double DEFAULT_FREQ_MHZ = RadioEnvironment.FREQ_MHZ;
    /** 路径采样数（与 RadioEnvironment.SAMPLES 一致）。 */
    private static final int PATH_SAMPLES = 32;

    private final RadioEnvironment legacy;        // 退化委托
    private final TerrainGrid terrainGrid;        // null = 退化模式
    private final double freqMhz;                 // 工作频率
    private final double gcsLat;
    private final double gcsLon;
    private final double gcsAntennaM;             // GCS 天线高度 (m)

    /**
     * @param legacy     既有 RadioEnvironment（退化委托）
     * @param terrainGrid 地形分区图（null = 退化模式）
     * @param freqMhz    工作频率 (MHz)
     * @param gcsLat     GCS 纬度
     * @param gcsLon     GCS 经度
     * @param gcsAntennaM GCS 天线高度 (m, above ground)
     */
    public EnhancedRadioEnvironment(RadioEnvironment legacy, TerrainGrid terrainGrid,
                                    double freqMhz, double gcsLat, double gcsLon,
                                    double gcsAntennaM) {
        this.legacy = legacy;
        this.terrainGrid = terrainGrid;
        this.freqMhz = freqMhz > 0 ? freqMhz : DEFAULT_FREQ_MHZ;
        this.gcsLat = gcsLat;
        this.gcsLon = gcsLon;
        this.gcsAntennaM = gcsAntennaM;
    }

    /**
     * 增强 RSSI 估算 + 分量分解（FR-12/13）。
     * <p>
     * 退化兼容（FR-14）：{@code terrainGrid == null} 时各衰减分量均为 0/legacy 值。
     *
     * @param north 无人机 north 偏移 (m)
     * @param east  无人机 east 偏移 (m)
     * @param alt   无人机高度 (m AMSL)
     * @return RSSI 分量分解
     */
    public RssiDecomposed rssiDecomposed(double north, double east, double alt) {
        if (terrainGrid == null) {
            // 退化模式：委托给 legacy RadioEnvironment（FR-14）
            double rssi = legacy.rssiDbm(north, east, alt);
            boolean occluded = legacy.occluded(north, east, alt);
            double fspl = RadioEnvironment.TX_EIRP_DBM - rssi - (occluded ? RadioEnvironment.SHADOW_DB : 0);
            return new RssiDecomposed(
                    rssi, fspl,
                    occluded ? RadioEnvironment.SHADOW_DB : 0,
                    0, 0, 0,
                    !occluded);
        }
        // 增强模式：计算 5 项衰减分量
        double dx = north;
        double dy = east;
        double gcsAlt = gcsAntennaM;  // GCS 天线高度（假设地面为 0）
        double dz = alt - gcsAlt;
        double distM = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distM < 1) {
            distM = 1;
        }
        // 1. 自由空间损耗
        double fspl = 20 * Math.log10(distM) + 20 * Math.log10(freqMhz) - 27.55;
        // 2. 地形遮挡
        boolean los = !legacy.occluded(north, east, alt);
        double shadowLoss = los ? 0 : RadioEnvironment.SHADOW_DB;
        // 3. 路径沿线地形类型
        double vehLat = GeoUtil.latOf(gcsLat, gcsLon, north, east);
        double vehLon = GeoUtil.lonOf(gcsLat, gcsLon, north, east);
        List<TerrainType> types = terrainGrid.typesAlongPath(gcsLat, gcsLon, vehLat, vehLon, PATH_SAMPLES);
        // 4. 植被衰减
        double vegDist = VegetationAttenuation.vegetationDistance(types, distM);
        TerrainType dominantType = terrainGrid.typeAt(vehLat, vehLon);
        double vegLoss = VegetationAttenuation.lossDb(dominantType, vegDist);
        // 5. 建筑穿透
        double bldLoss = BuildingPenetration.lossDbAlongPath(types);
        // 6. 多径损耗
        double multipathLoss = MultipathModel.lossDb(dominantType, !los);
        // 衰减叠加（FR-12）
        double rssi = RadioEnvironment.TX_EIRP_DBM - fspl - shadowLoss - vegLoss - bldLoss - multipathLoss;
        return new RssiDecomposed(rssi, fspl, shadowLoss, vegLoss, bldLoss, multipathLoss, los);
    }

    /**
     * 增强 RSSI 估算（简化接口）。
     * <p>
     * 退化兼容（FR-14）：{@code terrainGrid == null} 时返回 {@code legacy.rssiDbm()}。
     */
    public double rssiDbm(double north, double east, double alt) {
        return rssiDecomposed(north, east, alt).rssiDbm();
    }

    /** 是否视距。 */
    public boolean los(double north, double east, double alt) {
        return !legacy.occluded(north, east, alt);
    }

    public TerrainGrid terrainGrid() {
        return terrainGrid;
    }

    public double freqMhz() {
        return freqMhz;
    }
}