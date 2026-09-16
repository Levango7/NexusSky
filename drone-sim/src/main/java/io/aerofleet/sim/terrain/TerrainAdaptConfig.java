package io.aerofleet.sim.terrain;

/**
 * M8 地形适配配置（FR-01，DFX 4.4 配置可追溯）。
 * <p>
 * 由 SimConfig 解析后传入 VirtualDrone 构造器。
 */
public final class TerrainAdaptConfig {

    /** 地形适配启用开关。 */
    public final boolean enabled;
    /** 网格分辨率 (m)。 */
    public final double gridResolution;
    /** 灾区宽度 (m)。 */
    public final double areaWidthM;
    /** 灾区高度 (m)。 */
    public final double areaHeightM;
    /** 风切变阈值 (m/s/100m)。 */
    public final double windShearThreshold;

    public TerrainAdaptConfig(boolean enabled, double gridResolution,
                              double areaWidthM, double areaHeightM,
                              double windShearThreshold) {
        this.enabled = enabled;
        this.gridResolution = gridResolution;
        this.areaWidthM = areaWidthM;
        this.areaHeightM = areaHeightM;
        this.windShearThreshold = windShearThreshold;
    }

    /** 默认配置（禁用）。 */
    public static TerrainAdaptConfig disabled() {
        return new TerrainAdaptConfig(false, 100, 10000, 10000,
                FlightConstraintChecker.WIND_SHEAR_THRESHOLD);
    }

    /** 启用配置。 */
    public static TerrainAdaptConfig enabled(double gridResolution,
                                             double areaWidthM, double areaHeightM) {
        return new TerrainAdaptConfig(true, gridResolution, areaWidthM, areaHeightM,
                FlightConstraintChecker.WIND_SHEAR_THRESHOLD);
    }
}