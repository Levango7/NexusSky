package io.aerofleet.cloud.inspection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoutePlannerService} 单测。
 * <p>
 * 测试 4 种航线规划类型、航点生成数量与间距、重叠率计算。
 */
@DisplayName("RoutePlannerService 航线规划 (P1-1)")
class RoutePlannerServiceTest {

    private RoutePlannerService planner;
    private InspectionArea squareArea;

    @BeforeEach
    void setUp() {
        planner = new RoutePlannerService();
        // 500m × 500m 方形区域
        double centerLat = 39.90;
        double centerLon = 116.40;
        double half = 250.0 / 111320.0;
        squareArea = InspectionArea.polygon(List.of(
                new double[]{centerLat - half, centerLon - half},
                new double[]{centerLat - half, centerLon + half},
                new double[]{centerLat + half, centerLon + half},
                new double[]{centerLat + half, centerLon - half}));
    }

    private InspectionTemplate template(RouteType routeType, double alt, double overlap) {
        return new InspectionTemplate(
                "test-tpl", "test", IndustryType.POWER_LINE, "desc",
                routeType, alt, 8.0, overlap, 90.0,
                List.of(), 1.0, 5.0, java.time.Instant.now());
    }

    @Test
    @DisplayName("LINEAR_GRID 沿线网格扫描生成航点")
    void linearGridGeneratesWaypoints() {
        InspectionTemplate tpl = template(RouteType.LINEAR_GRID, 80, 80);
        List<Waypoint> wps = planner.planRoute(tpl, 39.90, 116.40, squareArea);

        assertThat(wps).isNotEmpty();
        assertThat(wps.size()).isGreaterThanOrEqualTo(4);
        // 序号应从 0 递增
        for (int i = 0; i < wps.size(); i++) {
            assertThat(wps.get(i).seq).isEqualTo(i);
        }
        // 应包含 FLY 和 PHOTO 两种动作
        boolean hasFly = wps.stream().anyMatch(w -> w.action == Waypoint.Action.FLY);
        boolean hasPhoto = wps.stream().anyMatch(w -> w.action == Waypoint.Action.PHOTO);
        assertThat(hasFly).isTrue();
        assertThat(hasPhoto).isTrue();
    }

    @Test
    @DisplayName("LINEAR_GRID 重叠率越高航线间距越小（航点越多）")
    void linearGridHigherOverlapMoreWaypoints() {
        InspectionTemplate lowOverlap = template(RouteType.LINEAR_GRID, 80, 50);
        InspectionTemplate highOverlap = template(RouteType.LINEAR_GRID, 80, 90);

        List<Waypoint> lowWps = planner.planRoute(lowOverlap, 39.90, 116.40, squareArea);
        List<Waypoint> highWps = planner.planRoute(highOverlap, 39.90, 116.40, squareArea);

        assertThat(highWps.size()).isGreaterThan(lowWps.size());
    }

    @Test
    @DisplayName("CROSS_GRID 交叉网格生成航点")
    void crossGridGeneratesWaypoints() {
        InspectionTemplate tpl = template(RouteType.CROSS_GRID, 100, 60);
        List<Waypoint> wps = planner.planRoute(tpl, 39.90, 116.40, squareArea);

        assertThat(wps).isNotEmpty();
        assertThat(wps.size()).isGreaterThanOrEqualTo(8);
        // 序号应从 0 递增
        for (int i = 0; i < wps.size(); i++) {
            assertThat(wps.get(i).seq).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("CROSS_GRID 航点数多于 LINEAR_GRID（交叉覆盖）")
    void crossGridMoreThanLinearGrid() {
        InspectionTemplate linear = template(RouteType.LINEAR_GRID, 100, 60);
        InspectionTemplate cross = template(RouteType.CROSS_GRID, 100, 60);

        List<Waypoint> linearWps = planner.planRoute(linear, 39.90, 116.40, squareArea);
        List<Waypoint> crossWps = planner.planRoute(cross, 39.90, 116.40, squareArea);

        assertThat(crossWps.size()).isGreaterThan(linearWps.size());
    }

    @Test
    @DisplayName("ORBIT 环绕飞行生成圆形航点")
    void orbitGeneratesCircularWaypoints() {
        InspectionTemplate tpl = template(RouteType.ORBIT, 60, 65);
        List<Waypoint> wps = planner.planRoute(tpl, 39.90, 116.40, squareArea);

        assertThat(wps).hasSize(16);
        // 所有航点高度应等于模板航高
        for (Waypoint wp : wps) {
            assertThat(wp.alt).isEqualTo(60.0);
            assertThat(wp.speedMps).isEqualTo(8.0);
        }
        // 应包含 PHOTO 动作
        boolean hasPhoto = wps.stream().anyMatch(w -> w.action == Waypoint.Action.PHOTO);
        assertThat(hasPhoto).isTrue();
    }

    @Test
    @DisplayName("ORBIT 圆形区域环绕飞行")
    void orbitWithCircleArea() {
        InspectionArea circle = InspectionArea.circle(39.90, 116.40, 200);
        InspectionTemplate tpl = template(RouteType.ORBIT, 60, 65);
        List<Waypoint> wps = planner.planRoute(tpl, 39.90, 116.40, circle);

        assertThat(wps).hasSize(16);
        // 验证航点大致分布在圆周上（距圆心约 200m）
        for (Waypoint wp : wps) {
            double dist = haversine(39.90, 116.40, wp.lat, wp.lon);
            assertThat(dist).isBetween(180.0, 220.0);
        }
    }

    @Test
    @DisplayName("PERIMETER 周界巡逻沿边界生成航点")
    void perimeterGeneratesBoundaryWaypoints() {
        InspectionTemplate tpl = template(RouteType.PERIMETER, 80, 70);
        List<Waypoint> wps = planner.planRoute(tpl, 39.90, 116.40, squareArea);

        // 方形区域 4 个顶点 → 4 个航点
        assertThat(wps).hasSize(4);
        for (Waypoint wp : wps) {
            assertThat(wp.action).isEqualTo(Waypoint.Action.SCAN);
            assertThat(wp.holdTimeSec).isEqualTo(3.0);
        }
    }

    @Test
    @DisplayName("PERIMETER 圆形区域周界生成航点")
    void perimeterWithCircleArea() {
        InspectionArea circle = InspectionArea.circle(39.90, 116.40, 200);
        InspectionTemplate tpl = template(RouteType.PERIMETER, 80, 70);
        List<Waypoint> wps = planner.planRoute(tpl, 39.90, 116.40, circle);

        assertThat(wps).hasSize(16);
        for (Waypoint wp : wps) {
            assertThat(wp.action).isEqualTo(Waypoint.Action.SCAN);
        }
    }

    @Test
    @DisplayName("area=null 时使用默认区域生成航点")
    void nullAreaUsesDefault() {
        InspectionTemplate tpl = template(RouteType.LINEAR_GRID, 80, 80);
        List<Waypoint> wps = planner.planRoute(tpl, 39.90, 116.40, null);

        assertThat(wps).isNotEmpty();
        assertThat(wps.size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("航点航向角在 0~360 范围内")
    void headingInRange() {
        InspectionTemplate tpl = template(RouteType.ORBIT, 60, 65);
        List<Waypoint> wps = planner.planRoute(tpl, 39.90, 116.40, squareArea);

        for (Waypoint wp : wps) {
            assertThat(wp.headingDeg).isGreaterThanOrEqualTo(0);
            assertThat(wp.headingDeg).isLessThan(360);
        }
    }

    @Test
    @DisplayName("LINEAR_GRID 相邻航线航点间距合理")
    void linearGridSpacingReasonable() {
        InspectionTemplate tpl = template(RouteType.LINEAR_GRID, 80, 80);
        List<Waypoint> wps = planner.planRoute(tpl, 39.90, 116.40, squareArea);

        // 至少有 2 个航点
        assertThat(wps.size()).isGreaterThanOrEqualTo(2);
        // 第一和第二个航点应在同一纬度（同一航线）
        Waypoint first = wps.get(0);
        Waypoint second = wps.get(1);
        assertThat(Math.abs(first.lat - second.lat)).isLessThan(1e-9);
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