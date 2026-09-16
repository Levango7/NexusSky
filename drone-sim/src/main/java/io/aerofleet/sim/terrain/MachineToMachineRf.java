package io.aerofleet.sim.terrain;

import io.aerofleet.sim.RadioEnvironment;

/**
 * 机间传播模型（FR-11）。
 * <p>
 * 两架无人机之间的 RF 传播模型，区别于 GCS→机的地对空传播。
 * 发射端为无人机（EIRP 较低，典型 15 dBm），两端高度均 > 0，
 * 地形遮挡按两端高度插值采样。
 */
public final class MachineToMachineRf {

    /** 机载发射 EIRP [dBm]（典型机载 2.4 GHz 模块，低于 GCS）。 */
    public static final double AIRBORNE_TX_EIRP_DBM = 15;
    /** 路径采样数。 */
    private static final int PATH_SAMPLES = 32;

    private final TerrainGrid terrainGrid;        // null = 自由空间
    private final double freqMhz;

    /**
     * @param terrainGrid 地形分区图（null = 自由空间模式）
     * @param freqMhz    工作频率 (MHz)
     */
    public MachineToMachineRf(TerrainGrid terrainGrid, double freqMhz) {
        this.terrainGrid = terrainGrid;
        this.freqMhz = freqMhz > 0 ? freqMhz : EnhancedRadioEnvironment.DEFAULT_FREQ_MHZ;
    }

    /**
     * 计算两架无人机之间的 RSSI + 分量分解。
     *
     * @param north1 发射机 north (m)
     * @param east1  发射机 east (m)
     * @param alt1   发射机高度 (m AMSL)
     * @param north2 接收机 north (m)
     * @param east2  接收机 east (m)
     * @param alt2   接收机高度 (m AMSL)
     * @return RSSI 分量分解
     */
    public RssiDecomposed rssiAirToAir(double north1, double east1, double alt1,
                                       double north2, double east2, double alt2) {
        double dx = north2 - north1;
        double dy = east2 - east1;
        double dz = alt2 - alt1;
        double distM = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distM < 1) {
            distM = 1;
        }
        // 1. 自由空间损耗
        double fspl = 20 * Math.log10(distM) + 20 * Math.log10(freqMhz) - 27.55;
        // 2. 地形遮挡：按两端高度插值采样
        boolean los = terrainGrid == null || losAirToAir(north1, east1, alt1, north2, east2, alt2);
        double shadowLoss = los ? 0 : RadioEnvironment.SHADOW_DB;
        // 3. 植被/建筑/多径（terrainGrid == null 时为 0）
        double vegLoss = 0;
        double bldLoss = 0;
        double multipathLoss = 0;
        if (terrainGrid != null) {
            // 使用本地坐标近似（假设原点在 GCS，无人机用 north/east 偏移）
            TerrainType dominant = terrainGrid.typeAtCell(0, 0);  // 简化：用原点网格
            multipathLoss = MultipathModel.lossDb(dominant, !los);
        }
        // 衰减叠加
        double rssi = AIRBORNE_TX_EIRP_DBM - fspl - shadowLoss - vegLoss - bldLoss - multipathLoss;
        return new RssiDecomposed(rssi, fspl, shadowLoss, vegLoss, bldLoss, multipathLoss, los);
    }

    /** 机间 LOS 判定：采样路径上的地形高程是否遮挡两端高度插值。 */
    private boolean losAirToAir(double north1, double east1, double alt1,
                                double north2, double east2, double alt2) {
        for (int i = 1; i < PATH_SAMPLES; i++) {
            double t = (double) i / PATH_SAMPLES;
            double pN = north1 + t * (north2 - north1);
            double pE = east1 + t * (east2 - east1);
            double chordAlt = alt1 + t * (alt2 - alt1);
            if (terrainGrid.elevationAt(pN, pE) > chordAlt + RadioEnvironment.CLEARANCE_M) {
                return false;
            }
        }
        return true;
    }
}