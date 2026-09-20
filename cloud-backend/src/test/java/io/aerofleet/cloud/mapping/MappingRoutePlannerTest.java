package io.aerofleet.cloud.mapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MappingRoutePlanner} 单测。
 * <p>
 * 测试 3 种航线规划类型、重叠率计算、GSD 计算。
 */
@DisplayName("MappingRoutePlanner 航线规划 (P2-2)")
class MappingRoutePlannerTest {

    private MappingRoutePlanner planner;
    private MappingArea squareArea;
    private MappingArea circleArea;

    @BeforeEach
    void setUp() {
        planner = new MappingRoutePlanner();
        // 500m × 500m 方形区域
        double centerLat = 39.90;
        double centerLon = 116.40;
        double half = 250.0 / 111320.0;
        squareArea = MappingArea.polygon(List.of(
                new double[]{centerLat - half, centerLon - half},
                new double[]{centerLat - half, centerLon + half},
                new double[]{centerLat + half, centerLon + half},
                new double[]{centerLat + half, centerLon - half}));
        // 200m 半径圆形区域
        circleArea = MappingArea.circle(39.90, 116.40, 200.0);
    }

    // ========== 正射影像航线 ==========

    @Test
    @DisplayName("正射影像航线生成航点")
    void orthophotoRouteGeneratesWaypoints() {
        List<MappingWaypoint> wps = planner.planOrthophotoRoute(squareArea, 100, 80, 60);

        assertThat(wps).isNotEmpty();
        assertThat(wps.size()).isGreaterThanOrEqualTo(4);
        // 序号应从 0 递增
        for (int i = 0; i < wps.size(); i++) {
            assertThat(wps.get(i).seq).isEqualTo(i);
        }
        // 应包含 FLY 和 PHOTO 两种动作
        boolean hasFly = wps.stream().anyMatch(w -> w.action == MappingWaypoint.Action.FLY);
        boolean hasPhoto = wps.stream().anyMatch(w -> w.action == MappingWaypoint.Action.PHOTO);
        assertThat(hasFly).isTrue();
        assertThat(hasPhoto).isTrue();
    }

    @Test
    @DisplayName("正射影像重叠率越高航线间距越小（航点越多）")
    void orthophotoHigherOverlapMoreWaypoints() {
        List<MappingWaypoint> lowOverlap = planner.planOrthophotoRoute(squareArea, 100, 50, 40);
        List<MappingWaypoint> highOverlap = planner.planOrthophotoRoute(squareArea, 100, 90, 80);

        assertThat(highOverlap.size()).isGreaterThan(lowOverlap.size());
    }

    @Test
    @DisplayName("正射影像侧向重叠率越高航线数越多")
    void orthophotoHigherSidelapMoreLines() {
        List<MappingWaypoint> lowSidelap = planner.planOrthophotoRoute(squareArea, 100, 80, 30);
        List<MappingWaypoint> highSidelap = planner.planOrthophotoRoute(squareArea, 100, 80, 80);

        assertThat(highSidelap.size()).isGreaterThan(lowSidelap.size());
    }

    @Test
    @DisplayName("正射影像航线航点航向角在 0~360 范围内")
    void orthophotoHeadingInRange() {
        List<MappingWaypoint> wps = planner.planOrthophotoRoute(squareArea, 100, 80, 60);

        for (MappingWaypoint wp : wps) {
            assertThat(wp.headingDeg).isGreaterThanOrEqualTo(0);
            assertThat(wp.headingDeg).isLessThan(360);
        }
    }

    @Test
    @DisplayName("正射影像航线高度等于指定航高")
    void orthophotoAltitudeCorrect() {
        double alt = 120.0;
        List<MappingWaypoint> wps = planner.planOrthophotoRoute(squareArea, alt, 80, 60);

        for (MappingWaypoint wp : wps) {
            assertThat(wp.alt).isEqualTo(alt);
        }
    }

    // ========== DEM 航线 ==========

    @Test
    @DisplayName("DEM 交叉航线生成航点")
    void demRouteGeneratesWaypoints() {
        List<MappingWaypoint> wps = planner.planDemRoute(squareArea, 100);

        assertThat(wps).isNotEmpty();
        assertThat(wps.size()).isGreaterThanOrEqualTo(8);
        // 序号应从 0 递增
        for (int i = 0; i < wps.size(); i++) {
            assertThat(wps.get(i).seq).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("DEM 航线航点数多于正射影像航线（交叉覆盖）")
    void demMoreThanOrthophoto() {
        List<MappingWaypoint> orthoWps = planner.planOrthophotoRoute(squareArea, 100, 80, 60);
        List<MappingWaypoint> demWps = planner.planDemRoute(squareArea, 100);

        assertThat(demWps.size()).isGreaterThan(orthoWps.size());
    }

    @Test
    @DisplayName("DEM 航线包含 PHOTO 动作")
    void demHasPhotoAction() {
        List<MappingWaypoint> wps = planner.planDemRoute(squareArea, 100);

        boolean hasPhoto = wps.stream().anyMatch(w -> w.action == MappingWaypoint.Action.PHOTO);
        assertThat(hasPhoto).isTrue();
    }

    // ========== 三维建模航线 ==========

    @Test
    @DisplayName("三维建模航线生成航点")
    void model3DRouteGeneratesWaypoints() {
        List<MappingWaypoint> wps = planner.plan3DModelRoute(squareArea, 100);

        assertThat(wps).isNotEmpty();
        assertThat(wps.size()).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("三维建模航线包含垂直和倾斜拍照航点")
    void model3DHasTiltAngles() {
        List<MappingWaypoint> wps = planner.plan3DModelRoute(squareArea, 100);

        // 应包含 cameraAngleDeg=0（垂直）和 cameraAngleDeg=45（倾斜）的拍照航点
        boolean hasVertical = wps.stream()
                .anyMatch(w -> w.action == MappingWaypoint.Action.PHOTO && w.cameraAngleDeg == 0.0);
        boolean hasTilt = wps.stream()
                .anyMatch(w -> w.action == MappingWaypoint.Action.PHOTO && w.cameraAngleDeg == 45.0);
        assertThat(hasVertical).isTrue();
        assertThat(hasTilt).isTrue();
    }

    @Test
    @DisplayName("三维建模航线环绕圆形区域")
    void model3DWithCircleArea() {
        List<MappingWaypoint> wps = planner.plan3DModelRoute(circleArea, 80);

        assertThat(wps).isNotEmpty();
        // 验证航点大致分布在圆周上（距圆心约 200m）
        for (MappingWaypoint wp : wps) {
            if (wp.action == MappingWaypoint.Action.PHOTO) {
                double dist = haversine(39.90, 116.40, wp.lat, wp.lon);
                assertThat(dist).isBetween(180.0, 220.0);
            }
        }
    }

    @Test
    @DisplayName("三维建模航线航点航向角在 0~360 范围内")
    void model3DHeadingInRange() {
        List<MappingWaypoint> wps = planner.plan3DModelRoute(squareArea, 100);

        for (MappingWaypoint wp : wps) {
            assertThat(wp.headingDeg).isGreaterThanOrEqualTo(0);
            assertThat(wp.headingDeg).isLessThan(360);
        }
    }

    // ========== GSD 计算 ==========

    @Test
    @DisplayName("GSD 计算正确（altitude/100）")
    void gsdCalculation() {
        assertThat(planner.computeGsdCm(100)).isEqualTo(1.0);
        assertThat(planner.computeGsdCm(200)).isEqualTo(2.0);
        assertThat(planner.computeGsdCm(50)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("GSD 与航高成正比")
    void gsdProportionalToAltitude() {
        double gsd1 = planner.computeGsdCm(100);
        double gsd2 = planner.computeGsdCm(200);
        assertThat(gsd2).isGreaterThan(gsd1);
    }

    // ========== 区域测试 ==========

    @Test
    @DisplayName("圆形区域面积计算正确")
    void circleAreaKm2() {
        double area = circleArea.areaKm2();
        // π × 200² / 10⁶ ≈ 0.1257 km²
        assertThat(area).isCloseTo(Math.PI * 0.04, within(0.01));
    }

    @Test
    @DisplayName("多边形区域面积计算正确")
    void polygonAreaKm2() {
        double area = squareArea.areaKm2();
        // 500m(纬度方向) × ~383m(经度方向, 纬度39.9°处缩放) ≈ 0.19 km²
        assertThat(area).isCloseTo(0.19, within(0.05));
    }

    @Test
    @DisplayName("区域 contains 方法正确判断点是否在区域内")
    void areaContains() {
        // 中心点应在区域内
        assertThat(squareArea.contains(39.90, 116.40)).isTrue();
        // 远处点不应在区域内
        assertThat(squareArea.contains(40.00, 116.50)).isFalse();
        // 圆形区域中心点应在区域内
        assertThat(circleArea.contains(39.90, 116.40)).isTrue();
        // 圆形区域外远处点不应在区域内
        assertThat(circleArea.contains(39.92, 116.42)).isFalse();
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.api.Assertions.within(tolerance);
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371000.0 * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}