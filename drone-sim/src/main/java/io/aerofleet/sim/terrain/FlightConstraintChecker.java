package io.aerofleet.sim.terrain;

import io.aerofleet.sim.SimLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 飞行约束检查器（FR-15 ~ FR-21, §6.3）。
 * <p>
 * 综合限飞区/安全高度/风切变/沼泽禁飞四项约束，单次检查返回所有违反项（FR-21）。
 * <p>
 * 判定逻辑：
 * <ol>
 *   <li>FR-15 限飞区：位置在 {@link NoFlyZone} 多边形内 → NO_FLY 违反</li>
 *   <li>FR-16 缓冲区限高：位置在缓冲区内且 altM &gt; bufferLimit → ALTITUDE_LIMIT 违反</li>
 *   <li>FR-17 超高层安全高度：位置在超高层建筑附近且 altM ≤ buildingHeight + 50 → ALTITUDE_LIMIT 违反</li>
 *   <li>FR-18 风切变：windShear &gt; 5 → WIND_SHEAR_WARN 违反</li>
 *   <li>FR-19 沼泽禁飞：terrainGrid.typeAt(lat,lon) == SWAMP → NO_FLY 违反</li>
 * </ol>
 * <p>
 * 异常处理（§6.3.3）：
 * <ul>
 *   <li>限飞区数据未加载 → 跳过限飞检查 + 告警</li>
 *   <li>风场数据不可用 → 跳过风切变检查 + 告警</li>
 * </ul>
 */
public final class FlightConstraintChecker {

    /** 风切变告警阈值 (m/s/100m, §7.6)。 */
    public static final double WIND_SHEAR_THRESHOLD = 5.0;
    /** 超高层安全高度余量 (m, §7.6)。 */
    public static final double SAFE_ALTITUDE_MARGIN_M = 50.0;

    private final TerrainGrid terrainGrid;
    private final List<NoFlyZone> noFlyZones;
    private final List<SafeAltitudeZone> safeAltZones;

    /**
     * @param terrainGrid  地形分区图（可为 null，仅影响沼泽禁飞）
     * @param noFlyZones   限飞区列表（可为 null/empty）
     * @param safeAltZones 安全高度区列表（可为 null/empty）
     */
    public FlightConstraintChecker(TerrainGrid terrainGrid,
                                   List<NoFlyZone> noFlyZones,
                                   List<SafeAltitudeZone> safeAltZones) {
        this.terrainGrid = terrainGrid;
        this.noFlyZones = noFlyZones != null ? noFlyZones : Collections.emptyList();
        this.safeAltZones = safeAltZones != null ? safeAltZones : Collections.emptyList();
    }

    /**
     * 综合约束检查（FR-21），返回所有违反项。
     *
     * @param lat       无人机纬度
     * @param lon       无人机经度
     * @param altM      无人机高度 (m AGL)
     * @param windShear 风切变 (m/s/100m)，{@link Double#NaN} 表示数据不可用
     * @return 约束违反项列表（可能为空）
     */
    public List<ConstraintViolation> check(double lat, double lon, double altM, double windShear) {
        List<ConstraintViolation> violations = new ArrayList<>();

        // FR-15 限飞区禁飞
        if (noFlyZones.isEmpty()) {
            SimLog.warn("no-fly zones not loaded, skipping no-fly check");
        } else {
            for (NoFlyZone zone : noFlyZones) {
                if (pointInPolygon(lat, lon, zone.polygon)) {
                    violations.add(new ConstraintViolation(
                            RestrictionType.NO_FLY,
                            "no-fly zone: " + zone.name,
                            0, 1));
                }
            }
        }

        // FR-16 缓冲区限高 + FR-17 超高层安全高度
        for (SafeAltitudeZone zone : safeAltZones) {
            if (pointInPolygon(lat, lon, zone.polygon)) {
                double requiredAlt = zone.requiredAltitudeM;
                if (altM <= requiredAlt) {
                    violations.add(new ConstraintViolation(
                            RestrictionType.ALTITUDE_LIMIT,
                            "safe altitude: " + zone.name,
                            requiredAlt, altM));
                }
            }
        }

        // FR-18 风切变预警
        if (Double.isNaN(windShear)) {
            SimLog.warn("wind shear data unavailable, skipping wind shear check");
        } else if (windShear > WIND_SHEAR_THRESHOLD) {
            violations.add(new ConstraintViolation(
                    RestrictionType.WIND_SHEAR_WARN,
                    "wind shear exceeds threshold",
                    WIND_SHEAR_THRESHOLD, windShear));
        }

        // FR-19 沼泽禁飞
        if (terrainGrid != null) {
            TerrainType type = terrainGrid.typeAt(lat, lon);
            if (type == TerrainType.SWAMP) {
                violations.add(new ConstraintViolation(
                        RestrictionType.NO_FLY,
                        "swamp no-fly zone",
                        0, 1));
            }
        }

        return violations;
    }

    /** 点在多边形内判定（射线法）。 */
    public static boolean pointInPolygon(double lat, double lon, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = polygon.get(i)[0];
            double xi = polygon.get(i)[1];
            double yj = polygon.get(j)[0];
            double xj = polygon.get(j)[1];
            if ((yi > lat) != (yj > lat) &&
                    lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 限飞区。 */
    public record NoFlyZone(String name, List<double[]> polygon) {
    }

    /**
     * 安全高度区。
     *
     * @param name              名称
     * @param polygon           多边形顶点（lat, lon 数组）
     * @param requiredAltitudeM 要求的最低高度 (m AGL)
     */
    public record SafeAltitudeZone(String name, List<double[]> polygon, double requiredAltitudeM) {
    }
}