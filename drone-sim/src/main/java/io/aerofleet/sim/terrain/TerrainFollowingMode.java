package io.aerofleet.sim.terrain;

import io.aerofleet.sim.GeoUtil;
import io.aerofleet.sim.TerrainModel;

/**
 * 地形跟随飞行模式（FR-09, §6.5）。
 * <p>
 * 当灾区山地地形起伏较大时，无人机保持相对地面恒定离地高度飞行。
 * 默认离地高度 30m，与绝对海拔高度模式可切换。
 * <p>
 * 工作原理：
 * <ol>
 *   <li>输入当前经纬度，通过 {@link TerrainModel} 查询地面高程</li>
 *   <li>目标海拔高度 = 地面高程 + 设定离地高度</li>
 *   <li>飞控系统根据目标海拔高度调整无人机高度</li>
 * </ol>
 * <p>
 * 模式切换：
 * <ul>
 *   <li>{@link Mode#TERRAIN_FOLLOWING}：地形跟随模式（恒定离地高度）</li>
 *   <li>{@link Mode#ABSOLUTE_ALTITUDE}：绝对海拔高度模式（恒定海拔高度）</li>
 * </ul>
 * <p>
 * 异常处理（§6.5.2）：
 * <ul>
 *   <li>地形高程数据不可用时，退化为绝对海拔高度模式</li>
 *   <li>离地高度参数非法（&lt;0）时，使用默认值 30m</li>
 * </ul>
 */
public final class TerrainFollowingMode {

    /** 默认离地高度 (m, FR-09)。 */
    public static final double DEFAULT_AGL_HEIGHT = 30.0;
    /** 最小离地高度 (m, 安全下限)。 */
    public static final double MIN_AGL_HEIGHT = 5.0;
    /** 最大离地高度 (m, 安全上限)。 */
    public static final double MAX_AGL_HEIGHT = 200.0;

    /** 飞行高度模式。 */
    public enum Mode {
        /** 地形跟随模式：恒定离地高度。 */
        TERRAIN_FOLLOWING(0),
        /** 绝对海拔高度模式：恒定海拔高度。 */
        ABSOLUTE_ALTITUDE(1);

        public final int code;

        Mode(int code) {
            this.code = code;
        }

        public static Mode fromCode(int code) {
            for (Mode m : values()) {
                if (m.code == code) {
                    return m;
                }
            }
            return ABSOLUTE_ALTITUDE;  // 默认绝对海拔模式
        }
    }

    private final TerrainModel terrainModel;
    private final double originLat;
    private final double originLon;
    private final double aglHeightM;
    private final Mode mode;

    /**
     * 创建地形跟随飞行模式实例（默认离地高度 30m，地形跟随模式）。
     *
     * @param terrainModel 地形高程模型（可为 null，退化时使用绝对海拔模式）
     * @param originLat    原点纬度（用于经纬度 → north/east 转换）
     * @param originLon    原点经度
     */
    public TerrainFollowingMode(TerrainModel terrainModel, double originLat, double originLon) {
        this(terrainModel, originLat, originLon, DEFAULT_AGL_HEIGHT, Mode.TERRAIN_FOLLOWING);
    }

    /**
     * 创建地形跟随飞行模式实例（自定义参数）。
     *
     * @param terrainModel 地形高程模型（可为 null，退化时使用绝对海拔模式）
     * @param originLat    原点纬度
     * @param originLon    原点经度
     * @param aglHeightM   离地高度 (m)，非法值自动修正为默认值
     * @param mode         飞行高度模式
     */
    public TerrainFollowingMode(TerrainModel terrainModel, double originLat, double originLon,
                                double aglHeightM, Mode mode) {
        this.terrainModel = terrainModel;
        this.originLat = originLat;
        this.originLon = originLon;
        this.aglHeightM = clampAglHeight(aglHeightM);
        this.mode = mode;
    }

    /**
     * 计算目标海拔高度（FR-09）。
     * <p>
     * 地形跟随模式：目标海拔 = 地面高程 + 离地高度。
     * 绝对海拔模式：目标海拔 = 离地高度（视为绝对海拔设定值）。
     * <p>
     * 异常处理：terrainModel 为 null 时退化为绝对海拔模式。
     *
     * @param lat 当前纬度
     * @param lon 当前经度
     * @return 目标海拔高度 (m AMSL)
     */
    public double targetAltitude(double lat, double lon) {
        if (mode == Mode.ABSOLUTE_ALTITUDE || terrainModel == null) {
            // 绝对海拔模式或退化模式：返回设定高度作为海拔高度
            return aglHeightM;
        }

        // 地形跟随模式：地面高程 + 离地高度
        double northM = GeoUtil.north(originLat, originLon, lat, lon);
        double eastM = GeoUtil.east(originLat, originLon, lat, lon);
        double groundElevation = terrainModel.elevationAt(northM, eastM);
        return groundElevation + aglHeightM;
    }

    /**
     * 计算当前离地高度。
     *
     * @param lat     当前纬度
     * @param lon     当前经度
     * @param altMSL  当前海拔高度 (m AMSL)
     * @return 当前离地高度 (m AGL)
     */
    public double currentAgl(double lat, double lon, double altMSL) {
        if (terrainModel == null) {
            return altMSL;  // 退化模式：无法计算离地高度，返回海拔高度
        }
        double northM = GeoUtil.north(originLat, originLon, lat, lon);
        double eastM = GeoUtil.east(originLat, originLon, lat, lon);
        double groundElevation = terrainModel.elevationAt(northM, eastM);
        return altMSL - groundElevation;
    }

    /**
     * 切换到地形跟随模式。
     *
     * @return 新的模式实例（不可变，返回新对象）
     */
    public TerrainFollowingMode switchToTerrainFollowing() {
        return new TerrainFollowingMode(terrainModel, originLat, originLon, aglHeightM, Mode.TERRAIN_FOLLOWING);
    }

    /**
     * 切换到绝对海拔高度模式。
     *
     * @param absoluteAltM 绝对海拔高度设定值 (m AMSL)
     * @return 新的模式实例（不可变，返回新对象）
     */
    public TerrainFollowingMode switchToAbsoluteAltitude(double absoluteAltM) {
        return new TerrainFollowingMode(terrainModel, originLat, originLon,
                clampAglHeight(absoluteAltM), Mode.ABSOLUTE_ALTITUDE);
    }

    /**
     * 调整离地高度设定值。
     *
     * @param newAglHeightM 新的离地高度 (m)，非法值自动修正
     * @return 新的模式实例（不可变，返回新对象）
     */
    public TerrainFollowingMode withAglHeight(double newAglHeightM) {
        return new TerrainFollowingMode(terrainModel, originLat, originLon,
                clampAglHeight(newAglHeightM), mode);
    }

    /** 当前飞行高度模式。 */
    public Mode mode() {
        return mode;
    }

    /** 当前离地高度设定值 (m)。 */
    public double aglHeightM() {
        return aglHeightM;
    }

    /** 地形高程模型是否可用。 */
    public boolean hasTerrainData() {
        return terrainModel != null;
    }

    /** 离地高度合法性约束。 */
    private static double clampAglHeight(double agl) {
        if (agl < MIN_AGL_HEIGHT || agl > MAX_AGL_HEIGHT || Double.isNaN(agl)) {
            return DEFAULT_AGL_HEIGHT;
        }
        return agl;
    }
}