package io.aerofleet.cloud.route;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** 四类航线模板生成器（F3，spec §1/§2）：几何正确性 + 参数校验全表 + 守卫。 */
@DisplayName("RouteGenerator — 四类模板几何")
class RouteGeneratorTest {

    private static final double HOME_LAT = 22.59;
    private static final double HOME_LON = 113.93;

    @Nested
    @DisplayName("T1 电力杆塔")
    class TowerTest {
        @Test
        void 每塔生成抵达环绕回位拍照四段() {
            RouteGenerator.Result r = RouteGenerator.tower(
                    List.of(new RouteGenerator.Tower("T001", HOME_LAT, HOME_LON)),
                    60, 25, 4, 5);

            // 1 抵达 + 4 环绕 + 1 回位拍照 = 6 航点/塔，加尾部 RTL = 7
            assertThat(r.waypoints()).hasSize(7);
            assertThat(r.legs()).hasSize(1);
            assertThat(r.legs().get(0).towerNo()).isEqualTo("T001");
            assertThat(r.waypoints().get(6).cmd()).isEqualTo("rtl");
            // 环绕点半径 = orbitRadiusM
            RouteGenerator.Point orbit = r.waypoints().get(1);
            double dist = Geo.distM(HOME_LAT, HOME_LON, orbit.lat(), orbit.lon());
            assertThat(dist).isCloseTo(25.0, within(1.0));
            // 回位点驻留 = hoverSec（拍照语义）
            assertThat(r.waypoints().get(5).holdTime()).isEqualTo(5);
        }

        @Test
        void 多塔顺序与转场() {
            RouteGenerator.Result r = RouteGenerator.tower(
                    List.of(new RouteGenerator.Tower("A", HOME_LAT, HOME_LON),
                            new RouteGenerator.Tower("B", HOME_LAT + 0.001, HOME_LON)),
                    60, 20, 2, 0);
            assertThat(r.legs()).hasSize(2);
            assertThat(r.estKm()).isGreaterThan(0.1); // 塔间距 ~111m
        }

        @Test
        void 空塔列表拒绝() {
            assertThatThrownBy(() -> RouteGenerator.tower(List.of(), 60, 25, 4, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("towers");
        }
    }

    @Nested
    @DisplayName("T2 光伏弓字形")
    class SolarTest {
        @Test
        void 矩形区域生成隔行折返扫描() {
            // 100m×80m 矩形（北-东-南-西），扫描方向正北，间距 30m → 约 2 条扫描线
            double[][] poly = rect(HOME_LAT, HOME_LON, 80, 100);
            RouteGenerator.Result r = RouteGenerator.solar(poly, 60, 30, 0);

            assertThat(r.waypoints().size()).isGreaterThanOrEqualTo(4); // ≥2 条线 × 2 端点
            assertThat(r.waypoints().get(r.waypoints().size() - 1).cmd()).isEqualTo("rtl");
            // 弓字形：第 1 条线两点的经度差与第 2 条线相反（折返）
            List<RouteGenerator.Point> wp = r.waypoints();
            int firstLegEnd = 1;
            if (wp.size() >= 5) {
                double d1 = wp.get(firstLegEnd).lon() - wp.get(0).lon();
                double d2 = wp.get(3).lon() - wp.get(2).lon();
                assertThat(Math.signum(d1)).isNotEqualTo(Math.signum(d2));
            }
        }

        @Test
        void 多边形少于三点拒绝() {
            assertThatThrownBy(() -> RouteGenerator.solar(
                    new double[][]{{22.59, 113.93}, {22.60, 113.93}}, 60, 30, 0))
                    .hasMessageContaining("polygon");
        }
    }

    @Nested
    @DisplayName("T3 管线带状")
    class PipelineTest {
        @Test
        void 折线等距采样且拐点保留() {
            double[][] line = new double[][]{
                    {HOME_LAT, HOME_LON},
                    {HOME_LAT, HOME_LON + 0.002},   // ~200m 东
                    {HOME_LAT + 0.001, HOME_LON + 0.002}, // ~111m 北
            };
            RouteGenerator.Result r = RouteGenerator.pipeline(line, 60, 50);

            // 总长 ~311m，50m 步长 → ≥6 个采样点 + RTL
            assertThat(r.waypoints().size()).isGreaterThanOrEqualTo(7);
            // 首尾保持原折线端点
            assertThat(r.waypoints().get(0).lat()).isCloseTo(HOME_LAT, within(1e-6));
            assertThat(r.waypoints().get(0).lon()).isCloseTo(HOME_LON, within(1e-6));
            // 拐点保留：北向段中的 (HOME_LAT+0.001, HOME_LON+0.002) 必在航点中
            boolean hasCorner = r.waypoints().stream().anyMatch(p ->
                    Math.abs(p.lat() - (HOME_LAT + 0.001)) < 1e-6
                            && Math.abs(p.lon() - (HOME_LON + 0.002)) < 1e-6);
            assertThat(hasCorner).isTrue();
        }

        @Test
        void 步长非法拒绝() {
            assertThatThrownBy(() -> RouteGenerator.pipeline(
                    new double[][]{{22.59, 113.93}, {22.60, 113.93}}, 60, 0))
                    .hasMessageContaining("stepM");
        }
    }

    @Nested
    @DisplayName("T4 河湖岸线")
    class ShorelineTest {
        @Test
        void 闭合环采样且首尾闭合() {
            double[][] poly = rect(HOME_LAT, HOME_LON, 60, 80);
            RouteGenerator.Result r = RouteGenerator.shoreline(poly, 60, 40, 0);

            // 环：最后点应回到首点附近（闭合），且总数 ≥ 顶点数
            List<RouteGenerator.Point> wp = r.waypoints();
            int lastWp = wp.size() - 2; // -1 是 RTL
            double gap = Geo.distM(wp.get(0).lat(), wp.get(0).lon(),
                    wp.get(lastWp).lat(), wp.get(lastWp).lon());
            assertThat(gap).isLessThan(1.0);
            assertThat(wp.size()).isGreaterThanOrEqualTo(5);
        }

        @Test
        void 外偏移让环变大() {
            double[][] poly = rect(HOME_LAT, HOME_LON, 60, 80);
            RouteGenerator.Result inner = RouteGenerator.shoreline(poly, 60, 1000, 0);
            RouteGenerator.Result outer = RouteGenerator.shoreline(poly, 60, 1000, 10);
            // 偏移后航点离矩形中心更远
            double cLat = HOME_LAT + 0.00027; // 中心近似
            double cLon = HOME_LON + 0.0004;
            double innerMax = inner.waypoints().stream()
                    .mapToDouble(p -> Geo.distM(cLat, cLon, p.lat(), p.lon())).max().orElse(0);
            double outerMax = outer.waypoints().stream()
                    .mapToDouble(p -> Geo.distM(cLat, cLon, p.lat(), p.lon())).max().orElse(0);
            assertThat(outerMax).isGreaterThan(innerMax);
        }
    }

    @Nested
    @DisplayName("守卫与通用")
    class GuardTest {
        @Test
        void 航点上限守卫() {
            // 生成 200 塔 × 6 点 = 1200 > 990 → 拒绝
            java.util.List<RouteGenerator.Tower> many = new java.util.ArrayList<>();
            for (int i = 0; i < 200; i++) {
                many.add(new RouteGenerator.Tower("T" + i, HOME_LAT + i * 1e-4, HOME_LON));
            }
            assertThatThrownBy(() -> RouteGenerator.tower(many, 60, 25, 4, 5))
                    .hasMessageContaining("990");
        }

        @Test
        void 相邻点无重复() {
            double[][] poly = rect(HOME_LAT, HOME_LON, 60, 80);
            for (RouteGenerator.Result r : List.of(
                    RouteGenerator.solar(poly, 60, 30, 0),
                    RouteGenerator.pipeline(poly, 60, 50),
                    RouteGenerator.shoreline(poly, 60, 50, 0))) {
                for (int i = 1; i < r.waypoints().size(); i++) {
                    var a = r.waypoints().get(i - 1);
                    var b = r.waypoints().get(i);
                    if ("waypoint".equals(b.cmd())) {
                        assertThat(Geo.distM(a.lat(), a.lon(), b.lat(), b.lon()))
                                .as("相邻航点距 >0（%s 第 %d 点）", r.type(), i)
                                .isGreaterThan(0.0);
                    }
                }
            }
        }

        @Test
        void 高度非法拒绝() {
            assertThatThrownBy(() -> RouteGenerator.tower(
                    List.of(new RouteGenerator.Tower("T", 22.59, 113.93)), 0, 25, 4, 5))
                    .hasMessageContaining("altM");
        }
    }

    /** 生成以 (lat,lon) 为西南角的矩形（米制）：西→北→东→南顶点序。 */
    private static double[][] rect(double lat, double lon, double widthM, double heightM) {
        double dLat = heightM / Geo.M_PER_DEG_LAT;
        double dLon = widthM / Geo.mPerDegLon(lat);
        return new double[][]{
                {lat, lon},
                {lat + dLat, lon},
                {lat + dLat, lon + dLon},
                {lat, lon + dLon},
        };
    }
}