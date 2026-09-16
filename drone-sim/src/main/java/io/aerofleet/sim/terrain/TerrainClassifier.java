package io.aerofleet.sim.terrain;

import io.aerofleet.sim.SimLog;
import io.aerofleet.sim.TerrainModel;

/**
 * 地形分类器（FR-06, §6.1.3）。
 * <p>
 * 接受灾区高程数据（{@link TerrainModel}）与地物数据（{@link TerrainFeatureSource}），
 * 对每个网格分类一种 {@link TerrainType}，生成 {@link TerrainGrid}。
 * <p>
 * 分类规则（基于高程方差 + 地物数据）：
 * <ol>
 *   <li>地物 = 保护区 → NATURE_RESERVE</li>
 *   <li>地物 = 森林 → FOREST</li>
 *   <li>地物 = 沼泽 → SWAMP</li>
 *   <li>地物 = 老城密集 → OLD_CITY_DENSE</li>
 *   <li>建筑高度 &gt; 100m → SUPER_HIGH_RISE</li>
 *   <li>高程方差 &gt; 200m → MOUNTAIN</li>
 *   <li>高程方差 50-200m → HILL</li>
 *   <li>多种地物并存 → MIXED</li>
 *   <li>高程方差 &lt; 10m 且无地物 → FLAT</li>
 * </ol>
 * <p>
 * 异常处理（§6.1.3）：
 * <ul>
 *   <li>数据缺失 → FLAT + 告警日志</li>
 *   <li>网格数超限（&gt; 65535）→ 拒绝建图 + 提示</li>
 * </ul>
 */
public final class TerrainClassifier {

    /** 网格数上限（§7.3 gridCells 长度不超过 65535）。 */
    public static final int MAX_CELLS = 65535;
    /** 高程方差阈值：山地。 */
    private static final double MOUNTAIN_VARIANCE = 200.0;
    /** 高程方差阈值：丘陵。 */
    private static final double HILL_VARIANCE = 50.0;
    /** 超高层建筑高度阈值。 */
    private static final double SUPER_HIGH_RISE_HEIGHT = 100.0;

    private TerrainClassifier() {
    }

    /**
     * 对灾区网格分类建图。
     *
     * @param originLat       原点纬度
     * @param originLon       原点经度
     * @param widthM          灾区宽度 (m, east 方向)
     * @param heightM         灾区高度 (m, north 方向)
     * @param gridResolution  网格边长 (m)
     * @param terrainModel    高程模型
     * @param featureSource   地物数据源（可为 null）
     * @return 地形分区图
     * @throws IllegalArgumentException 网格数超限时
     */
    public static TerrainGrid classify(double originLat, double originLon,
                                       double widthM, double heightM,
                                       double gridResolution,
                                       TerrainModel terrainModel,
                                       TerrainFeatureSource featureSource) {
        int mapWidth = (int) Math.ceil(widthM / gridResolution);
        int mapHeight = (int) Math.ceil(heightM / gridResolution);
        int totalCells = mapWidth * mapHeight;
        if (totalCells > MAX_CELLS) {
            throw new IllegalArgumentException(
                    "grid count " + totalCells + " exceeds max " + MAX_CELLS
                            + "; please narrow the area or increase gridResolution");
        }
        TerrainType[] cells = new TerrainType[totalCells];
        for (int gy = 0; gy < mapHeight; gy++) {
            for (int gx = 0; gx < mapWidth; gx++) {
                double eastM = (gx + 0.5) * gridResolution;
                double northM = (gy + 0.5) * gridResolution;
                cells[gy * mapWidth + gx] = classifyCell(
                        northM, eastM, gridResolution, terrainModel, featureSource);
            }
        }
        return new TerrainGrid(cells, mapWidth, mapHeight, gridResolution,
                originLat, originLon, terrainModel);
    }

    /** 单网格分类。 */
    private static TerrainType classifyCell(double northM, double eastM,
                                            double gridResolution,
                                            TerrainModel terrainModel,
                                            TerrainFeatureSource featureSource) {
        // 1. 采样网格四角高程，计算方差
        double[] elevations = sampleElevations(northM, eastM, gridResolution, terrainModel);
        double variance = variance(elevations);

        // 2. 查询地物数据
        TerrainFeature feature = featureSource != null
                ? featureSource.featureAt(northM, eastM)
                : TerrainFeature.NONE;

        // 3. 分类规则（优先级从高到低）
        if (feature == TerrainFeature.NATURE_RESERVE) {
            return TerrainType.NATURE_RESERVE;
        }
        if (feature == TerrainFeature.FOREST) {
            return TerrainType.FOREST;
        }
        if (feature == TerrainFeature.SWAMP) {
            return TerrainType.SWAMP;
        }
        if (feature == TerrainFeature.OLD_CITY_DENSE) {
            return TerrainType.OLD_CITY_DENSE;
        }
        if (feature == TerrainFeature.SUPER_HIGH_RISE
                || maxBuildingHeight(featureSource, northM, eastM) > SUPER_HIGH_RISE_HEIGHT) {
            return TerrainType.SUPER_HIGH_RISE;
        }
        if (variance > MOUNTAIN_VARIANCE) {
            return TerrainType.MOUNTAIN;
        }
        if (variance > HILL_VARIANCE) {
            return TerrainType.HILL;
        }
        if (feature == TerrainFeature.MIXED) {
            return TerrainType.MIXED;
        }
        if (variance < 10 && feature == TerrainFeature.NONE) {
            return TerrainType.FLAT;
        }
        // 数据缺失兜底（§6.1.3）
        if (feature == TerrainFeature.NONE && variance < 1e-6) {
            SimLog.warn("terrain data missing at (" + northM + "," + eastM
                    + "), defaulting to FLAT");
            return TerrainType.FLAT;
        }
        return TerrainType.FLAT;
    }

    /** 采样网格四角 + 中心高程。 */
    private static double[] sampleElevations(double northM, double eastM,
                                             double gridResolution,
                                             TerrainModel terrainModel) {
        double half = gridResolution / 2;
        return new double[]{
                terrainModel.elevationAt(northM - half, eastM - half),
                terrainModel.elevationAt(northM - half, eastM + half),
                terrainModel.elevationAt(northM + half, eastM - half),
                terrainModel.elevationAt(northM + half, eastM + half),
                terrainModel.elevationAt(northM, eastM)
        };
    }

    /** 计算方差（简化：max - min）。 */
    private static double variance(double[] values) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return max - min;
    }

    /** 查询网格内最大建筑高度。 */
    private static double maxBuildingHeight(TerrainFeatureSource source,
                                            double northM, double eastM) {
        return source != null ? source.maxBuildingHeight(northM, eastM) : 0;
    }

    /** 地物类型枚举。 */
    public enum TerrainFeature {
        NONE, FOREST, SWAMP, OLD_CITY_DENSE, SUPER_HIGH_RISE, NATURE_RESERVE, MIXED
    }

    /** 地物数据源接口。 */
    public interface TerrainFeatureSource {
        /** 查询某位置的地物类型。 */
        TerrainFeature featureAt(double northM, double eastM);

        /** 查询某位置附近的最大建筑高度 (m)。 */
        double maxBuildingHeight(double northM, double eastM);
    }
}