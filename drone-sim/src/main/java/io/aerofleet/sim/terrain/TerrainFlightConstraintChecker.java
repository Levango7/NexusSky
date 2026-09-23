package io.aerofleet.sim.terrain;

import io.aerofleet.sim.SimLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 复杂地形飞行约束检查器（FR-06~FR-10, §6.3 扩展）。
 * <p>
 * 专注于复杂地形场景下的飞行约束检查，与既有 {@link FlightConstraintChecker} 互补：
 * <ul>
 *   <li>FR-06 山地峡谷风切变预警：检测水平风速变化 &gt;5m/s 或垂直风速变化 &gt;3m/s，
 *       发出 WIND_SHEAR_WARN 告警 + 高度调整建议</li>
 *   <li>FR-07 沼泽禁飞区动态标注：泥石流灾害导致新沼泽区域形成时，
 *       动态标注为禁飞区并输出绕航建议</li>
 *   <li>FR-08 老城密集区飞行约束：自动降低最大速度至 5m/s、最大高度至 50m、
 *       启用 ToF 近距避障</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>不可变值对象：所有内部状态在构造时确定，检查结果通过不可变 record 返回</li>
 *   <li>异常容错：数据不可用时跳过对应检查并告警，不中断整体检查流程</li>
 * </ul>
 */
public final class TerrainFlightConstraintChecker {

    // ===== FR-06 风切变阈值 =====
    /** 水平风速变化阈值 (m/s, FR-06)。 */
    public static final double HORIZONTAL_WIND_SHEAR_THRESHOLD = 5.0;
    /** 垂直风速变化阈值 (m/s, FR-06)。 */
    public static final double VERTICAL_WIND_SHEAR_THRESHOLD = 3.0;
    /** 风切变安全高度余量 (m, FR-06)。 */
    public static final double WIND_SHEAR_SAFE_ALTITUDE_MARGIN = 30.0;

    // ===== FR-08 老城密集区约束 =====
    /** 老城密集区最大飞行速度 (m/s, FR-08)。 */
    public static final double OLD_CITY_MAX_SPEED = 5.0;
    /** 老城密集区最大飞行高度 (m AGL, FR-08)。 */
    public static final double OLD_CITY_MAX_ALTITUDE = 50.0;
    /** 老城密集区启用 ToF 近距避障 (FR-08)。 */
    public static final boolean OLD_CITY_TOF_AVOIDANCE = true;

    private final TerrainGrid terrainGrid;

    /**
     * @param terrainGrid 地形分区图（可为 null，仅影响区域类型判定）
     */
    public TerrainFlightConstraintChecker(TerrainGrid terrainGrid) {
        this.terrainGrid = terrainGrid;
    }

    // ===== FR-06 山地峡谷风切变预警 =====

    /**
     * 检查风切变约束（FR-06）。
     * <p>
     * 当无人机处于山地峡谷区域且检测到风切变（水平风速变化 &gt;5m/s 或垂直风速变化 &gt;3m/s）时，
     * 发出 WIND_SHEAR_WARN 告警，并给出高度调整建议（当前高度 + 安全余量）。
     * <p>
     * 异常处理：风速数据为 {@link Double#NaN} 时跳过检查并告警。
     *
     * @param zoneType          当前区域类型
     * @param horizontalWindDelta 水平风速变化 (m/s)，{@link Double#NaN} 表示数据不可用
     * @param verticalWindDelta   垂直风速变化 (m/s)，{@link Double#NaN} 表示数据不可用
     * @param currentAltM       当前飞行高度 (m AGL)
     * @return 风切变检查结果（{@link WindShearResult#alert()} 为 null 表示无告警）
     */
    public WindShearResult checkWindShear(TerrainZoneType zoneType,
                                          double horizontalWindDelta,
                                          double verticalWindDelta,
                                          double currentAltM) {
        if (zoneType != TerrainZoneType.MOUNTAIN_VALLEY) {
            return WindShearResult.noAlert();
        }

        boolean hShear = false;
        boolean vShear = false;

        if (Double.isNaN(horizontalWindDelta)) {
            SimLog.warn("水平风速数据不可用，跳过水平风切变检查");
        } else if (horizontalWindDelta > HORIZONTAL_WIND_SHEAR_THRESHOLD) {
            hShear = true;
        }

        if (Double.isNaN(verticalWindDelta)) {
            SimLog.warn("垂直风速数据不可用，跳过垂直风切变检查");
        } else if (verticalWindDelta > VERTICAL_WIND_SHEAR_THRESHOLD) {
            vShear = true;
        }

        if (!hShear && !vShear) {
            return WindShearResult.noAlert();
        }

        // 生成告警 + 高度调整建议
        String detail = String.format(
                "风切变检测: 水平变化=%.1fm/s (阈值%.0f), 垂直变化=%.1fm/s (阈值%.0f)",
                Double.isNaN(horizontalWindDelta) ? -1 : horizontalWindDelta,
                HORIZONTAL_WIND_SHEAR_THRESHOLD,
                Double.isNaN(verticalWindDelta) ? -1 : verticalWindDelta,
                VERTICAL_WIND_SHEAR_THRESHOLD);

        double suggestedAltM = currentAltM + WIND_SHEAR_SAFE_ALTITUDE_MARGIN;

        return new WindShearResult(
                TerrainAlertType.WIND_SHEAR_WARN,
                detail,
                true,           // 需要高度调整
                suggestedAltM,  // 建议安全高度
                true            // 建议绕航
        );
    }

    // ===== FR-07 沼泽禁飞区动态标注 =====

    /**
     * 检查沼泽禁飞区约束（FR-07）。
     * <p>
     * 输入动态标注的沼泽禁飞区列表，判定当前位置是否在禁飞区内，
     * 若在禁飞区内则输出 SWAMP_NO_FLY 告警 + 绕航建议。
     *
     * @param lat             无人机纬度
     * @param lon             无人机经度
     * @param swampNoFlyZones 沼泽禁飞区列表（动态标注，可为 null/empty）
     * @return 沼泽禁飞检查结果（{@link SwampNoFlyResult#alert()} 为 null 表示无告警）
     */
    public SwampNoFlyResult checkSwampNoFly(double lat, double lon,
                                            List<SwampNoFlyZone> swampNoFlyZones) {
        if (swampNoFlyZones == null || swampNoFlyZones.isEmpty()) {
            return SwampNoFlyResult.noAlert();
        }

        for (SwampNoFlyZone zone : swampNoFlyZones) {
            if (FlightConstraintChecker.pointInPolygon(lat, lon, zone.polygon())) {
                return new SwampNoFlyResult(
                        TerrainAlertType.SWAMP_NO_FLY,
                        "沼泽禁飞区: " + zone.name(),
                        true  // 需要绕航
                );
            }
        }

        return SwampNoFlyResult.noAlert();
    }

    /**
     * 动态标注新沼泽禁飞区（FR-07）。
     * <p>
     * 泥石流灾害导致新沼泽区域形成时，构造新的沼泽禁飞区标注，
     * 供后续检查和通知所有无人机绕航使用。
     *
     * @param name    沼泽区域名称
     * @param polygon 沼泽区域多边形顶点（lat, lon 数组）
     * @return 新标注的沼泽禁飞区
     */
    public SwampNoFlyZone markSwampNoFlyZone(String name, List<double[]> polygon) {
        return new SwampNoFlyZone(name, polygon);
    }

    // ===== FR-08 老城密集区飞行约束 =====

    /**
     * 检查老城密集区飞行约束（FR-08）。
     * <p>
     * 当无人机进入老式街道密集区（OLD_CITY_DENSE）时，自动应用以下约束：
     * <ul>
     *   <li>最大飞行速度降至 5m/s</li>
     *   <li>最大飞行高度降至 50m</li>
     *   <li>启用 ToF 近距避障</li>
     * </ul>
     * <p>
     * 异常处理：terrainGrid 为 null 时，通过 zoneType 参数直接判定。
     *
     * @param zoneType 当前区域类型（若 terrainGrid 可用，应与其一致）
     * @param lat      无人机纬度（terrainGrid 可用时用于查询区域类型）
     * @param lon      无人机经度
     * @param currentSpeed 当前飞行速度 (m/s)
     * @param currentAltM  当前飞行高度 (m AGL)
     * @return 老城密集区约束检查结果
     */
    public OldCityConstraintResult checkOldCityConstraint(TerrainZoneType zoneType,
                                                          double lat, double lon,
                                                          double currentSpeed,
                                                          double currentAltM) {
        TerrainZoneType effectiveZone = zoneType;

        // terrainGrid 可用时，以 grid 查询结果为准
        if (terrainGrid != null) {
            TerrainType gridType = terrainGrid.typeAt(lat, lon);
            effectiveZone = TerrainZoneType.fromTerrainType(gridType);
        }

        if (effectiveZone != TerrainZoneType.OLD_CITY_DENSE) {
            return OldCityConstraintResult.noConstraint();
        }

        List<String> violations = new ArrayList<>();

        if (currentSpeed > OLD_CITY_MAX_SPEED) {
            violations.add(String.format(
                    "速度超限: 当前=%.1fm/s, 限制=%.1fm/s",
                    currentSpeed, OLD_CITY_MAX_SPEED));
        }

        if (currentAltM > OLD_CITY_MAX_ALTITUDE) {
            violations.add(String.format(
                    "高度超限: 当前=%.1fm, 限制=%.1fm",
                    currentAltM, OLD_CITY_MAX_ALTITUDE));
        }

        return new OldCityConstraintResult(
                TerrainAlertType.OLD_CITY_CONSTRAINT,
                violations.isEmpty() ? "老城密集区约束生效" : String.join("; ", violations),
                OLD_CITY_MAX_SPEED,
                OLD_CITY_MAX_ALTITUDE,
                OLD_CITY_TOF_AVOIDANCE,
                violations.isEmpty()
        );
    }

    // ===== 不可变结果记录 =====

    /**
     * 风切变检查结果（FR-06）。
     *
     * @param alert            告警类型（null = 无告警）
     * @param detail           告警详情
     * @param needAltAdjust    是否需要高度调整
     * @param suggestedAltM    建议安全高度 (m AGL)
     * @param suggestDetour    是否建议绕航
     */
    public record WindShearResult(
            TerrainAlertType alert,
            String detail,
            boolean needAltAdjust,
            double suggestedAltM,
            boolean suggestDetour
    ) {
        /** 无告警的空结果。 */
        public static WindShearResult noAlert() {
            return new WindShearResult(null, "", false, 0, false);
        }
    }

    /**
     * 沼泽禁飞检查结果（FR-07）。
     *
     * @param alert         告警类型（null = 无告警）
     * @param detail        告警详情
     * @param suggestDetour 是否建议绕航
     */
    public record SwampNoFlyResult(
            TerrainAlertType alert,
            String detail,
            boolean suggestDetour
    ) {
        /** 无告警的空结果。 */
        public static SwampNoFlyResult noAlert() {
            return new SwampNoFlyResult(null, "", false);
        }
    }

    /**
     * 老城密集区约束检查结果（FR-08）。
     *
     * @param alert           告警类型（null = 无约束）
     * @param detail          约束详情
     * @param maxSpeedMps     约束最大速度 (m/s)
     * @param maxAltM         约束最大高度 (m AGL)
     * @param tofAvoidance    是否启用 ToF 近距避障
     * @param compliant       当前是否满足约束
     */
    public record OldCityConstraintResult(
            TerrainAlertType alert,
            String detail,
            double maxSpeedMps,
            double maxAltM,
            boolean tofAvoidance,
            boolean compliant
    ) {
        /** 无约束的空结果。 */
        public static OldCityConstraintResult noConstraint() {
            return new OldCityConstraintResult(null, "", 0, 0, false, true);
        }
    }

    /**
     * 沼泽禁飞区标注（FR-07）。
     *
     * @param name    区域名称
     * @param polygon 多边形顶点（lat, lon 数组）
     */
    public record SwampNoFlyZone(String name, List<double[]> polygon) {
        public SwampNoFlyZone {
            polygon = polygon != null ? List.copyOf(polygon) : Collections.emptyList();
        }
    }
}