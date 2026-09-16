package io.aerofleet.sim.terrain;

import io.aerofleet.sim.TerrainModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FlightConstraintChecker 单测（FR-15~FR-21）。
 */
@DisplayName("FlightConstraintChecker 飞行约束检查 (FR-15~21)")
class FlightConstraintCheckerTest {

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

    @Test
    @DisplayName("FR-15 限飞区内位置 → NO_FLY 违反")
    void noFlyZoneViolation() {
        FlightConstraintChecker checker = new FlightConstraintChecker(
                null,
                List.of(new FlightConstraintChecker.NoFlyZone("airport", squarePolygon())),
                List.of());
        List<ConstraintViolation> violations = checker.check(ZONE_LAT, ZONE_LON, 50, Double.NaN);
        assertThat(violations).anyMatch(v -> v.type() == RestrictionType.NO_FLY);
    }

    @Test
    @DisplayName("FR-15 限飞区外位置 → 无 NO_FLY 违反")
    void noFlyZoneOutsideNoViolation() {
        FlightConstraintChecker checker = new FlightConstraintChecker(
                null,
                List.of(new FlightConstraintChecker.NoFlyZone("airport", squarePolygon())),
                List.of());
        List<ConstraintViolation> violations = checker.check(31.0, 121.0, 50, Double.NaN);
        assertThat(violations).noneMatch(v -> v.type() == RestrictionType.NO_FLY && v.description().contains("airport"));
    }

    @Test
    @DisplayName("FR-16 安全高度区：高度不足 → ALTITUDE_LIMIT 违反")
    void safeAltitudeViolation() {
        FlightConstraintChecker checker = new FlightConstraintChecker(
                null, List.of(),
                List.of(new FlightConstraintChecker.SafeAltitudeZone("downtown", squarePolygon(), 120.0)));
        List<ConstraintViolation> violations = checker.check(ZONE_LAT, ZONE_LON, 80, Double.NaN);
        assertThat(violations).anyMatch(v -> v.type() == RestrictionType.ALTITUDE_LIMIT);
    }

    @Test
    @DisplayName("FR-16 安全高度区：高度足够 → 无违反")
    void safeAltitudeSatisfied() {
        FlightConstraintChecker checker = new FlightConstraintChecker(
                null, List.of(),
                List.of(new FlightConstraintChecker.SafeAltitudeZone("downtown", squarePolygon(), 120.0)));
        List<ConstraintViolation> violations = checker.check(ZONE_LAT, ZONE_LON, 150, Double.NaN);
        assertThat(violations).noneMatch(v -> v.type() == RestrictionType.ALTITUDE_LIMIT);
    }

    @Test
    @DisplayName("FR-18 风切变超阈值 → WIND_SHEAR_WARN 违反")
    void windShearViolation() {
        FlightConstraintChecker checker = new FlightConstraintChecker(null, List.of(), List.of());
        List<ConstraintViolation> violations = checker.check(30.0, 120.0, 50, 8.0);
        assertThat(violations).anyMatch(v -> v.type() == RestrictionType.WIND_SHEAR_WARN);
    }

    @Test
    @DisplayName("FR-19 沼泽禁飞：terrainGrid SWAMP → NO_FLY 违反")
    void swampNoFlyViolation() {
        TerrainType[] cells = {TerrainType.SWAMP};
        TerrainGrid grid = new TerrainGrid(cells, 1, 1, 10000, 30.0, 120.0, TerrainModel.flat());
        FlightConstraintChecker checker = new FlightConstraintChecker(grid, List.of(), List.of());
        List<ConstraintViolation> violations = checker.check(30.0, 120.0, 50, Double.NaN);
        assertThat(violations).anyMatch(v -> v.type() == RestrictionType.NO_FLY && v.description().contains("swamp"));
    }
}