package io.aerofleet.sim.terrain;

import io.aerofleet.sim.TerrainModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerrainFlightConstraintChecker 单测（FR-06~FR-10）。
 * <p>
 * 覆盖山地峡谷风切变预警、沼泽禁飞区动态标注、老城密集区飞行约束三大功能。
 */
@DisplayName("TerrainFlightConstraintChecker 复杂地形飞行约束 (FR-06~10)")
class TerrainFlightConstraintCheckerTest {

    private static final double ZONE_LAT = 30.001;
    private static final double ZONE_LON = 120.001;

    // 简单方形多边形（围绕 ZONE_LAT/ZONE_LON）
    private List<double[]> squarePolygon() {
        return List.of(
                new double[]{ZONE_LAT - 0.001, ZONE_LON - 0.001},
                new double[]{ZONE_LAT - 0.001, ZONE_LON + 0.001},
                new double[]{ZONE_LAT + 0.001, ZONE_LON + 0.001},
                new double[]{ZONE_LAT + 0.001, ZONE_LON - 0.001});
    }

    // ===== FR-06 山地峡谷风切变预警 =====

    @Nested
    @DisplayName("FR-06 山地峡谷风切变预警")
    class WindShearCheck {

        @Test
        @DisplayName("山地峡谷 + 水平风速变化 >5m/s → WIND_SHEAR_WARN 告警")
        void mountainValleyHorizontalWindShearAlert() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.WindShearResult result =
                    checker.checkWindShear(TerrainZoneType.MOUNTAIN_VALLEY, 6.0, 0.0, 50.0);

            assertThat(result.alert()).isEqualTo(TerrainAlertType.WIND_SHEAR_WARN);
            assertThat(result.needAltAdjust()).isTrue();
            assertThat(result.suggestedAltM()).isEqualTo(50.0 + TerrainFlightConstraintChecker.WIND_SHEAR_SAFE_ALTITUDE_MARGIN);
            assertThat(result.suggestDetour()).isTrue();
        }

        @Test
        @DisplayName("山地峡谷 + 垂直风速变化 >3m/s → WIND_SHEAR_WARN 告警")
        void mountainValleyVerticalWindShearAlert() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.WindShearResult result =
                    checker.checkWindShear(TerrainZoneType.MOUNTAIN_VALLEY, 0.0, 4.0, 80.0);

            assertThat(result.alert()).isEqualTo(TerrainAlertType.WIND_SHEAR_WARN);
            assertThat(result.needAltAdjust()).isTrue();
            assertThat(result.suggestedAltM()).isEqualTo(80.0 + TerrainFlightConstraintChecker.WIND_SHEAR_SAFE_ALTITUDE_MARGIN);
        }

        @Test
        @DisplayName("山地峡谷 + 水平和垂直风切变同时超限 → WIND_SHEAR_WARN 告警")
        void mountainValleyBothWindShearAlert() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.WindShearResult result =
                    checker.checkWindShear(TerrainZoneType.MOUNTAIN_VALLEY, 7.0, 5.0, 60.0);

            assertThat(result.alert()).isEqualTo(TerrainAlertType.WIND_SHEAR_WARN);
            assertThat(result.detail()).contains("水平变化=7.0");
            assertThat(result.detail()).contains("垂直变化=5.0");
        }

        @Test
        @DisplayName("山地峡谷 + 风速变化未超阈值 → 无告警")
        void mountainValleyNoShearNoAlert() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.WindShearResult result =
                    checker.checkWindShear(TerrainZoneType.MOUNTAIN_VALLEY, 4.0, 2.0, 50.0);

            assertThat(result.alert()).isNull();
            assertThat(result.needAltAdjust()).isFalse();
        }

        @Test
        @DisplayName("非山地峡谷区域 + 风切变超限 → 无告警（区域不匹配）")
        void nonMountainValleyNoAlert() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.WindShearResult result =
                    checker.checkWindShear(TerrainZoneType.OPEN_FIELD, 10.0, 8.0, 50.0);

            assertThat(result.alert()).isNull();
        }

        @Test
        @DisplayName("山地峡谷 + 水平风速数据不可用(NaN) → 跳过水平检查，仅检查垂直")
        void mountainValleyHorizontalNaNSkip() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.WindShearResult result =
                    checker.checkWindShear(TerrainZoneType.MOUNTAIN_VALLEY, Double.NaN, 4.0, 50.0);

            assertThat(result.alert()).isEqualTo(TerrainAlertType.WIND_SHEAR_WARN);
            assertThat(result.detail()).contains("水平变化=-1.0");  // NaN 显示为 -1
        }

        @Test
        @DisplayName("山地峡谷 + 所有风速数据不可用(NaN) → 无告警")
        void mountainValleyAllNaMNoAlert() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.WindShearResult result =
                    checker.checkWindShear(TerrainZoneType.MOUNTAIN_VALLEY, Double.NaN, Double.NaN, 50.0);

            assertThat(result.alert()).isNull();
        }
    }

    // ===== FR-07 沼泽禁飞区动态标注 =====

    @Nested
    @DisplayName("FR-07 沼泽禁飞区动态标注")
    class SwampNoFlyCheck {

        @Test
        @DisplayName("当前位置在沼泽禁飞区内 → SWAMP_NO_FLY 告警 + 绕航建议")
        void insideSwampNoFlyZoneAlert() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.SwampNoFlyZone zone =
                    checker.markSwampNoFlyZone("泥石流沼泽区", squarePolygon());

            TerrainFlightConstraintChecker.SwampNoFlyResult result =
                    checker.checkSwampNoFly(ZONE_LAT, ZONE_LON, List.of(zone));

            assertThat(result.alert()).isEqualTo(TerrainAlertType.SWAMP_NO_FLY);
            assertThat(result.detail()).contains("泥石流沼泽区");
            assertThat(result.suggestDetour()).isTrue();
        }

        @Test
        @DisplayName("当前位置不在沼泽禁飞区内 → 无告警")
        void outsideSwampNoFlyZoneNoAlert() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.SwampNoFlyZone zone =
                    checker.markSwampNoFlyZone("泥石流沼泽区", squarePolygon());

            TerrainFlightConstraintChecker.SwampNoFlyResult result =
                    checker.checkSwampNoFly(31.0, 121.0, List.of(zone));

            assertThat(result.alert()).isNull();
            assertThat(result.suggestDetour()).isFalse();
        }

        @Test
        @DisplayName("沼泽禁飞区列表为空 → 无告警")
        void emptySwampNoFlyListNoAlert() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);

            TerrainFlightConstraintChecker.SwampNoFlyResult result =
                    checker.checkSwampNoFly(ZONE_LAT, ZONE_LON, List.of());

            assertThat(result.alert()).isNull();
        }

        @Test
        @DisplayName("沼泽禁飞区列表为 null → 无告警")
        void nullSwampNoFlyListNoAlert() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);

            TerrainFlightConstraintChecker.SwampNoFlyResult result =
                    checker.checkSwampNoFly(ZONE_LAT, ZONE_LON, null);

            assertThat(result.alert()).isNull();
        }

        @Test
        @DisplayName("动态标注沼泽禁飞区 → 返回不可变区域标注")
        void markSwampNoFlyZoneReturnsImmutable() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.SwampNoFlyZone zone =
                    checker.markSwampNoFlyZone("新沼泽", squarePolygon());

            assertThat(zone.name()).isEqualTo("新沼泽");
            assertThat(zone.polygon()).hasSize(4);
        }

        @Test
        @DisplayName("多个沼泽禁飞区 → 逐一检查，命中任一则告警")
        void multipleSwampZonesCheckAll() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.SwampNoFlyZone zone1 =
                    checker.markSwampNoFlyZone("沼泽A", List.of(
                            new double[]{28.0, 118.0},
                            new double[]{28.0, 118.001},
                            new double[]{28.001, 118.001},
                            new double[]{28.001, 118.0}));
            TerrainFlightConstraintChecker.SwampNoFlyZone zone2 =
                    checker.markSwampNoFlyZone("沼泽B", squarePolygon());

            TerrainFlightConstraintChecker.SwampNoFlyResult result =
                    checker.checkSwampNoFly(ZONE_LAT, ZONE_LON, List.of(zone1, zone2));

            assertThat(result.alert()).isEqualTo(TerrainAlertType.SWAMP_NO_FLY);
            assertThat(result.detail()).contains("沼泽B");
        }
    }

    // ===== FR-08 老城密集区飞行约束 =====

    @Nested
    @DisplayName("FR-08 老城密集区飞行约束")
    class OldCityConstraintCheck {

        @Test
        @DisplayName("老城密集区 + 速度超限(>5m/s) → OLD_CITY_CONSTRAINT 告警")
        void oldCitySpeedViolation() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.OldCityConstraintResult result =
                    checker.checkOldCityConstraint(TerrainZoneType.OLD_CITY_DENSE,
                            30.0, 120.0, 10.0, 30.0);

            assertThat(result.alert()).isEqualTo(TerrainAlertType.OLD_CITY_CONSTRAINT);
            assertThat(result.maxSpeedMps()).isEqualTo(TerrainFlightConstraintChecker.OLD_CITY_MAX_SPEED);
            assertThat(result.maxAltM()).isEqualTo(TerrainFlightConstraintChecker.OLD_CITY_MAX_ALTITUDE);
            assertThat(result.tofAvoidance()).isTrue();
            assertThat(result.compliant()).isFalse();
            assertThat(result.detail()).contains("速度超限");
        }

        @Test
        @DisplayName("老城密集区 + 高度超限(>50m) → OLD_CITY_CONSTRAINT 告警")
        void oldCityAltitudeViolation() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.OldCityConstraintResult result =
                    checker.checkOldCityConstraint(TerrainZoneType.OLD_CITY_DENSE,
                            30.0, 120.0, 4.0, 80.0);

            assertThat(result.alert()).isEqualTo(TerrainAlertType.OLD_CITY_CONSTRAINT);
            assertThat(result.compliant()).isFalse();
            assertThat(result.detail()).contains("高度超限");
        }

        @Test
        @DisplayName("老城密集区 + 速度和高度均超限 → 同时报告两项违规")
        void oldCityBothViolations() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.OldCityConstraintResult result =
                    checker.checkOldCityConstraint(TerrainZoneType.OLD_CITY_DENSE,
                            30.0, 120.0, 10.0, 80.0);

            assertThat(result.compliant()).isFalse();
            assertThat(result.detail()).contains("速度超限");
            assertThat(result.detail()).contains("高度超限");
        }

        @Test
        @DisplayName("老城密集区 + 速度和高度均满足约束 → 约束生效但无违规")
        void oldCityCompliant() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.OldCityConstraintResult result =
                    checker.checkOldCityConstraint(TerrainZoneType.OLD_CITY_DENSE,
                            30.0, 120.0, 4.0, 30.0);

            assertThat(result.alert()).isEqualTo(TerrainAlertType.OLD_CITY_CONSTRAINT);
            assertThat(result.maxSpeedMps()).isEqualTo(5.0);
            assertThat(result.maxAltM()).isEqualTo(50.0);
            assertThat(result.tofAvoidance()).isTrue();
            assertThat(result.compliant()).isTrue();
        }

        @Test
        @DisplayName("非老城密集区 → 无约束")
        void nonOldCityNoConstraint() {
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(null);
            TerrainFlightConstraintChecker.OldCityConstraintResult result =
                    checker.checkOldCityConstraint(TerrainZoneType.OPEN_FIELD,
                            30.0, 120.0, 20.0, 100.0);

            assertThat(result.alert()).isNull();
            assertThat(result.compliant()).isTrue();
        }

        @Test
        @DisplayName("terrainGrid 可用时 → 以 grid 查询结果为准判定区域类型")
        void oldCityWithTerrainGrid() {
            TerrainType[] cells = {TerrainType.OLD_CITY_DENSE};
            TerrainGrid grid = new TerrainGrid(cells, 1, 1, 10000, 30.0, 120.0, TerrainModel.flat());
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(grid);

            // 传入 OPEN_FIELD 但 grid 查到 OLD_CITY_DENSE → 应以 grid 为准
            TerrainFlightConstraintChecker.OldCityConstraintResult result =
                    checker.checkOldCityConstraint(TerrainZoneType.OPEN_FIELD,
                            30.0, 120.0, 10.0, 30.0);

            assertThat(result.alert()).isEqualTo(TerrainAlertType.OLD_CITY_CONSTRAINT);
            assertThat(result.compliant()).isFalse();
        }

        @Test
        @DisplayName("terrainGrid 可用 + 非老城区域 → 无约束")
        void nonOldCityWithTerrainGrid() {
            TerrainType[] cells = {TerrainType.FLAT};
            TerrainGrid grid = new TerrainGrid(cells, 1, 1, 10000, 30.0, 120.0, TerrainModel.flat());
            TerrainFlightConstraintChecker checker = new TerrainFlightConstraintChecker(grid);

            TerrainFlightConstraintChecker.OldCityConstraintResult result =
                    checker.checkOldCityConstraint(TerrainZoneType.OLD_CITY_DENSE,
                            30.0, 120.0, 10.0, 80.0);

            // grid 查到 FLAT → 不是老城密集区 → 无约束
            assertThat(result.alert()).isNull();
        }
    }
}