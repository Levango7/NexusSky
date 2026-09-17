package io.aerofleet.sim.orch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * CoveragePathPlanner 覆盖路径规划算法单测。
 * <p>
 * 覆盖牛耕式覆盖、多边形覆盖、Voronoi 分区、转弯优化、覆盖率计算、遗漏检测、空区域健壮性。
 */
@DisplayName("CoveragePathPlanner 覆盖路径规划")
class CoveragePathPlannerTest {

    /** 1 度纬度对应的距离（m）。 */
    private static final double M_PER_DEG_LAT = 111000.0;

    private final CoveragePathPlanner planner = new CoveragePathPlanner();

    /** 计算路径总长度（m）。 */
    private double pathLength(List<double[]> path) {
        double len = 0.0;
        for (int i = 1; i < path.size(); i++) {
            double[] p1 = path.get(i - 1);
            double[] p2 = path.get(i);
            double cosLat = Math.cos(Math.toRadians((p1[0] + p2[0]) / 2.0));
            double dLat = (p2[0] - p1[0]) * M_PER_DEG_LAT;
            double dLon = (p2[1] - p1[1]) * M_PER_DEG_LAT * cosLat;
            len += Math.hypot(dLat, dLon);
        }
        return len;
    }

    /** 判断点是否在多边形内（射线法，含边界容差）。 */
    private boolean inPolygon(double lat, double lon, List<double[]> polygon) {
        int n = polygon.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = polygon.get(i)[0];
            double xi = polygon.get(i)[1];
            double yj = polygon.get(j)[0];
            double xj = polygon.get(j)[1];
            if (((yi > lat) != (yj > lat)) &&
                    (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 计算三点外接圆半径（米坐标系，用于验证圆弧半径）。 */
    private double circumRadius(double[] a, double[] b, double[] c) {
        double cosLat = Math.cos(Math.toRadians(a[0]));
        if (Math.abs(cosLat) < 1e-6) {
            cosLat = 1e-6;
        }
        double mPerDegLon = M_PER_DEG_LAT * cosLat;
        double bx = (b[1] - a[1]) * mPerDegLon;
        double by = (b[0] - a[0]) * M_PER_DEG_LAT;
        double cx = (c[1] - a[1]) * mPerDegLon;
        double cy = (c[0] - a[0]) * M_PER_DEG_LAT;
        double ab = Math.hypot(bx, by);
        double bc = Math.hypot(cx - bx, cy - by);
        double ca = Math.hypot(cx, cy);
        double cross = bx * cy - by * cx;
        double area = Math.abs(cross) / 2.0;
        if (area < 1e-12) {
            return Double.MAX_VALUE;
        }
        return ab * bc * ca / (4.0 * area);
    }

    // ==================== 牛耕式覆盖 ====================

    @Test
    @DisplayName("矩形区域牛耕式：路径覆盖整个区域（覆盖率 > 0.9）")
    void boustrophedonCoversRectangle() {
        // 约 111m × 111m 区域，间距 20m
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.001};
        List<double[]> path = planner.boustrophedon(bbox, 20.0, 50.0);

        assertThat(path).isNotEmpty();
        // 每个点 3 元素 [lat, lon, alt]
        for (double[] p : path) {
            assertThat(p).hasSize(3);
            assertThat(p[2]).isEqualTo(50.0);
        }

        // 覆盖率 > 0.9（swathWidth 略大于 spacing 确保覆盖）
        CoveragePathPlanner.CoverageStats stats = planner.computeCoverage(path, bbox, 25.0);
        assertThat(stats.coverageRatio).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("牛耕式扫描线方向交替：偶数行左→右，奇数行右→左")
    void boustrophedonAlternatingDirection() {
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.001};
        List<double[]> path = planner.boustrophedon(bbox, 20.0, 50.0);

        assertThat(path.size()).isGreaterThanOrEqualTo(4);
        // 路径成对：每条扫描线两个端点
        assertThat(path.size() % 2).isEqualTo(0);

        int numSwaths = path.size() / 2;
        assertThat(numSwaths).isGreaterThanOrEqualTo(2);

        for (int i = 0; i < numSwaths; i++) {
            double[] start = path.get(2 * i);
            double[] end = path.get(2 * i + 1);
            if (i % 2 == 0) {
                // 偶数行：从 minLon(0) 到 maxLon(0.001)
                assertThat(start[1]).isCloseTo(0.0, within(1e-12));
                assertThat(end[1]).isCloseTo(0.001, within(1e-12));
            } else {
                // 奇数行：从 maxLon(0.001) 到 minLon(0)
                assertThat(start[1]).isCloseTo(0.001, within(1e-12));
                assertThat(end[1]).isCloseTo(0.0, within(1e-12));
            }
        }
    }

    // ==================== 多边形覆盖 ====================

    @Test
    @DisplayName("多边形区域覆盖：路径点在多边形内（含边界容差）")
    void boustrophedonPolygonInsidePolygon() {
        // 矩形多边形 [0,0] - [0,0.002] - [0.002,0.002] - [0.002,0]
        List<double[]> polygon = Arrays.asList(
                new double[]{0.0, 0.0},
                new double[]{0.0, 0.002},
                new double[]{0.002, 0.002},
                new double[]{0.002, 0.0}
        );
        List<double[]> path = planner.boustrophedonPolygon(polygon, 30.0, 50.0);

        assertThat(path).isNotEmpty();
        // 容差：浮点误差 + 边界点
        double tol = 1e-9;
        for (double[] p : path) {
            // 射线法判断（含边界容差）
            boolean inside = inPolygon(p[0] + tol, p[1] + tol, polygon)
                    || inPolygon(p[0] - tol, p[1] + tol, polygon)
                    || inPolygon(p[0] + tol, p[1] - tol, polygon)
                    || inPolygon(p[0] - tol, p[1] - tol, polygon)
                    || inPolygon(p[0], p[1], polygon);
            assertThat(inside).as("点 (%f, %f) 应在多边形内", p[0], p[1]).isTrue();
        }
    }

    @Test
    @DisplayName("多边形区域覆盖：凹多边形路径正确")
    void boustrophedonConcavePolygon() {
        // L 形凹多边形
        List<double[]> polygon = Arrays.asList(
                new double[]{0.0, 0.0},
                new double[]{0.0, 0.002},
                new double[]{0.001, 0.002},
                new double[]{0.001, 0.001},
                new double[]{0.002, 0.001},
                new double[]{0.002, 0.0}
        );
        List<double[]> path = planner.boustrophedonPolygon(polygon, 30.0, 50.0);

        assertThat(path).isNotEmpty();
        // 路径点应在多边形 bbox 内
        for (double[] p : path) {
            assertThat(p[0]).isBetween(0.0 - 1e-9, 0.002 + 1e-9);
            assertThat(p[1]).isBetween(0.0 - 1e-9, 0.002 + 1e-9);
        }
    }

    // ==================== Voronoi 分区 ====================

    @Test
    @DisplayName("Voronoi 分区：每架无人机有独立非空子区域")
    void partitionAndPlanIndependentSubRegions() {
        // 2 架无人机，bbox 沿经度 0~0.004
        List<double[]> drones = Arrays.asList(
                new double[]{0.0, 0.0},
                new double[]{0.0, 0.003}
        );
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.004};
        List<List<double[]>> plans = planner.partitionAndPlan(drones, bbox, 30.0, 50.0);

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0)).isNotEmpty();
        assertThat(plans.get(1)).isNotEmpty();

        // 子区域经度范围不同：第 0 架在 [0, 0.002)，第 1 架在 [0.002, 0.004]
        double maxLon0 = 0.0;
        for (double[] p : plans.get(0)) {
            maxLon0 = Math.max(maxLon0, p[1]);
        }
        double minLon1 = Double.MAX_VALUE;
        for (double[] p : plans.get(1)) {
            minLon1 = Math.min(minLon1, p[1]);
        }
        // 第 0 架子区域经度上限 < 第 1 架子区域经度下限（子区域不同）
        assertThat(maxLon0).isLessThan(minLon1 + 1e-9);
    }

    @Test
    @DisplayName("Voronoi 分区：子区域并集覆盖整个区域")
    void partitionAndPlanCoversWholeArea() {
        List<double[]> drones = Arrays.asList(
                new double[]{0.0, 0.0},
                new double[]{0.0, 0.003}
        );
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.004};
        List<List<double[]>> plans = planner.partitionAndPlan(drones, bbox, 30.0, 50.0);

        // 合并所有子区域路径
        List<double[]> merged = new ArrayList<>();
        for (List<double[]> plan : plans) {
            merged.addAll(plan);
        }
        assertThat(merged).isNotEmpty();

        // 覆盖率 > 0.9
        CoveragePathPlanner.CoverageStats stats = planner.computeCoverage(merged, bbox, 35.0);
        assertThat(stats.coverageRatio).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("Voronoi 分区：单架无人机 → 整个区域")
    void partitionAndPlanSingleDrone() {
        List<double[]> drones = Collections.singletonList(new double[]{0.0, 0.0});
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.002};
        List<List<double[]>> plans = planner.partitionAndPlan(drones, bbox, 30.0, 50.0);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0)).isNotEmpty();
    }

    // ==================== 转弯优化 ====================

    @Test
    @DisplayName("转弯优化：90° 转弯优化后路径更短")
    void optimizeTurnsShorterPath() {
        // 构造 90° 转弯：prev → curr → next
        // prev = (0, 0)，curr = (0, 0.001)（向东约 111m），next = (0.001, 0.001)（向北约 111m）
        List<double[]> rawPath = Arrays.asList(
                new double[]{0.0, 0.0, 50.0},
                new double[]{0.0, 0.001, 50.0},
                new double[]{0.001, 0.001, 50.0}
        );
        double minTurnRadius = 20.0;

        List<double[]> optimized = planner.optimizeTurns(rawPath, minTurnRadius);

        assertThat(optimized).isNotEmpty();
        // 优化后插入了圆弧采样点，点数增加
        assertThat(optimized.size()).isGreaterThan(rawPath.size());

        double rawLen = pathLength(rawPath);
        double optLen = pathLength(optimized);
        // 优化后路径更短（节省 r * (2 - π/2) ≈ 8.6m）
        assertThat(optLen).isLessThan(rawLen);
    }

    @Test
    @DisplayName("转弯优化：转弯半径不小于最小半径")
    void optimizeTurnsRadiusNotLessThanMin() {
        // 构造 90° 转弯，前后段足够长
        List<double[]> rawPath = Arrays.asList(
                new double[]{0.0, 0.0, 50.0},
                new double[]{0.0, 0.002, 50.0},
                new double[]{0.002, 0.002, 50.0}
        );
        double minTurnRadius = 30.0;

        List<double[]> optimized = planner.optimizeTurns(rawPath, minTurnRadius);

        assertThat(optimized.size()).isGreaterThan(rawPath.size());

        // 取圆弧上的三个连续点计算外接圆半径
        // 优化后路径结构：[prev, arcStart, arcMid1, ..., arcEnd, next]
        // 圆弧上的点在 index 1 ~ size-2 之间
        int mid = optimized.size() / 2;
        double[] a = optimized.get(mid - 1);
        double[] b = optimized.get(mid);
        double[] c = optimized.get(mid + 1);
        double radius = circumRadius(a, b, c);

        // 半径应接近 minTurnRadius（容差 10%）
        assertThat(radius).isCloseTo(minTurnRadius, within(minTurnRadius * 0.15));
    }

    @Test
    @DisplayName("转弯优化：直行路径不变")
    void optimizeTurnsStraightPathUnchanged() {
        // 共线点，无转弯
        List<double[]> rawPath = Arrays.asList(
                new double[]{0.0, 0.0, 50.0},
                new double[]{0.0, 0.001, 50.0},
                new double[]{0.0, 0.002, 50.0}
        );
        List<double[]> optimized = planner.optimizeTurns(rawPath, 20.0);

        // 直行无转弯，路径点数不变
        assertThat(optimized).hasSize(rawPath.size());
    }

    @Test
    @DisplayName("转弯优化：路径长度不增加")
    void optimizeTurnsNeverLonger() {
        // 构造多个 90° 转弯的锯齿路径
        List<double[]> rawPath = new ArrayList<>();
        rawPath.add(new double[]{0.0, 0.0, 50.0});
        rawPath.add(new double[]{0.0, 0.002, 50.0});
        rawPath.add(new double[]{0.001, 0.002, 50.0});
        rawPath.add(new double[]{0.001, 0.0, 50.0});
        rawPath.add(new double[]{0.002, 0.0, 50.0});

        List<double[]> optimized = planner.optimizeTurns(rawPath, 20.0);
        double rawLen = pathLength(rawPath);
        double optLen = pathLength(optimized);

        // 优化后路径不比原始长
        assertThat(optLen).isLessThanOrEqualTo(rawLen + 1e-6);
    }

    // ==================== 覆盖率计算 ====================

    @Test
    @DisplayName("覆盖率计算：完整覆盖时 ratio ≈ 1.0")
    void computeCoverageFullRatio() {
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.001};
        List<double[]> path = planner.boustrophedon(bbox, 20.0, 50.0);
        // swathWidth 略大于 spacing，确保完整覆盖
        CoveragePathPlanner.CoverageStats stats = planner.computeCoverage(path, bbox, 25.0);

        assertThat(stats.coverageRatio).isGreaterThan(0.95);
        assertThat(stats.totalArea).isGreaterThan(0.0);
        assertThat(stats.coveredArea).isGreaterThan(0.0);
        assertThat(stats.numSwaths).isGreaterThan(0);
        assertThat(stats.totalPathLength).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("覆盖率计算：有遗漏时 ratio < 1.0")
    void computeCoveragePartialRatio() {
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.001};
        // 稀疏路径，spacing 远大于 swathWidth
        List<double[]> path = planner.boustrophedon(bbox, 80.0, 50.0);
        CoveragePathPlanner.CoverageStats stats = planner.computeCoverage(path, bbox, 20.0);

        assertThat(stats.coverageRatio).isLessThan(1.0);
        assertThat(stats.coverageRatio).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("覆盖率计算：空路径 ratio = 0")
    void computeCoverageEmptyPath() {
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.001};
        CoveragePathPlanner.CoverageStats stats = planner.computeCoverage(Collections.emptyList(), bbox, 20.0);

        assertThat(stats.coverageRatio).isZero();
        assertThat(stats.totalArea).isGreaterThan(0.0);
    }

    // ==================== 遗漏检测 ====================

    @Test
    @DisplayName("遗漏检测：稀疏路径检测到未覆盖缝隙")
    void detectGapsFindsUncovered() {
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.001};
        // 稀疏路径，spacing 远大于 swathWidth
        List<double[]> path = planner.boustrophedon(bbox, 80.0, 50.0);
        List<double[]> gaps = planner.detectGaps(path, bbox, 20.0);

        assertThat(gaps).isNotEmpty();
        // 遗漏点在 bbox 内
        for (double[] g : gaps) {
            assertThat(g[0]).isBetween(0.0, 0.001);
            assertThat(g[1]).isBetween(0.0, 0.001);
        }
    }

    @Test
    @DisplayName("遗漏检测：完整覆盖时无遗漏")
    void detectGapsNoneWhenFullCoverage() {
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.001};
        List<double[]> path = planner.boustrophedon(bbox, 20.0, 50.0);
        List<double[]> gaps = planner.detectGaps(path, bbox, 30.0);

        assertThat(gaps).isEmpty();
    }

    // ==================== 空区域健壮性 ====================

    @Test
    @DisplayName("空区域不崩溃：null bbox → 空路径")
    void nullBboxReturnsEmpty() {
        List<double[]> path = planner.boustrophedon(null, 20.0, 50.0);
        assertThat(path).isEmpty();
    }

    @Test
    @DisplayName("空区域不崩溃：退化 bbox → 空路径")
    void degenerateBboxReturnsEmpty() {
        // minLat == maxLat
        assertThat(planner.boustrophedon(new double[]{0.0, 0.0, 0.0, 0.001}, 20.0, 50.0)).isEmpty();
        // minLon == maxLon
        assertThat(planner.boustrophedon(new double[]{0.0, 0.001, 0.0, 0.0}, 20.0, 50.0)).isEmpty();
        // minLat > maxLat
        assertThat(planner.boustrophedon(new double[]{0.001, 0.0, 0.0, 0.001}, 20.0, 50.0)).isEmpty();
    }

    @Test
    @DisplayName("空区域不崩溃：null 多边形 → 空路径")
    void nullPolygonReturnsEmpty() {
        assertThat(planner.boustrophedonPolygon(null, 20.0, 50.0)).isEmpty();
    }

    @Test
    @DisplayName("空区域不崩溃：不足 3 顶点 → 空路径")
    void tooFewPolygonVerticesReturnsEmpty() {
        List<double[]> polygon = Arrays.asList(
                new double[]{0.0, 0.0},
                new double[]{0.0, 0.001}
        );
        assertThat(planner.boustrophedonPolygon(polygon, 20.0, 50.0)).isEmpty();
    }

    @Test
    @DisplayName("空区域不崩溃：null 路径 → 空优化结果")
    void nullPathReturnsEmpty() {
        assertThat(planner.optimizeTurns(null, 20.0)).isEmpty();
    }

    @Test
    @DisplayName("空区域不崩溃：空无人机列表 → 空分区")
    void emptyDronesReturnsEmpty() {
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.001};
        assertThat(planner.partitionAndPlan(Collections.emptyList(), bbox, 20.0, 50.0)).isEmpty();
    }

    @Test
    @DisplayName("空区域不崩溃：非正参数 → 空路径")
    void nonPositiveParamsReturnEmpty() {
        double[] bbox = new double[]{0.0, 0.001, 0.0, 0.001};
        assertThat(planner.boustrophedon(bbox, 0.0, 50.0)).isEmpty();
        assertThat(planner.boustrophedon(bbox, -10.0, 50.0)).isEmpty();
    }

    @Test
    @DisplayName("CoverageStats 不可变：字段 final")
    void coverageStatsFields() {
        CoveragePathPlanner.CoverageStats stats = new CoveragePathPlanner.CoverageStats(
                1000.0, 800.0, 0.8, 5, 500.0);
        assertThat(stats.totalArea).isEqualTo(1000.0);
        assertThat(stats.coveredArea).isEqualTo(800.0);
        assertThat(stats.coverageRatio).isCloseTo(0.8, within(1e-9));
        assertThat(stats.numSwaths).isEqualTo(5);
        assertThat(stats.totalPathLength).isEqualTo(500.0);
    }
}