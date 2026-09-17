package io.aerofleet.sim.ai;

import io.aerofleet.sim.GeoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ReturnToHomeStrategy 应急返航策略单测（M11）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>旧接口 {@link ReturnToHomeStrategy#evaluate} 向后兼容（7 个原测试）</li>
 *   <li>风向利用 {@link ReturnToHomeStrategy#computeWindAssistedHeading}：顺风加速 / 逆风减速 / 侧风修正 / 风速超空速安全处理</li>
 *   <li>能耗最优路径 {@link ReturnToHomeStrategy#computeOptimalReturnPath}：直线返航 / Z 字形 / 可达性</li>
 *   <li>地形规避 {@link ReturnToHomeStrategy#avoidTerrain}：无障碍不变 / 有障碍抬升</li>
 *   <li>滑翔路径 {@link ReturnToHomeStrategy#computeGlidePath}：高度足够纯滑翔 / 高度不足需动力</li>
 *   <li>综合返航 {@link ReturnToHomeStrategy#planReturnHome}：多因素综合决策</li>
 * </ul>
 */
@DisplayName("ReturnToHomeStrategy 应急返航策略 (M11)")
class ReturnToHomeStrategyTest {

    private ReturnToHomeStrategy strategy;

    // 测试基准点（杭州附近）
    private static final double BASE_LAT = 30.0;
    private static final double BASE_LON = 120.0;
    private static final double EPS = 1e-6;

    @BeforeEach
    void setUp() {
        strategy = new ReturnToHomeStrategy();
    }

    // ======================================================================
    // 旧接口向后兼容测试（保留原 7 个测试）
    // ======================================================================

    @Nested
    @DisplayName("evaluate 旧接口（向后兼容）")
    class LegacyEvaluateTest {

        @Test
        @DisplayName("电量低于 25% 触发 RTL，置信度 0.9")
        void lowBatteryTriggersRtl() {
            DecisionResult result = strategy.evaluate(15.0, true, true, 500.0);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("RTL");
            assertThat(result.reason).isEqualTo("low battery");
            assertThat(result.triggerValue).isEqualTo(15.0);
            assertThat(result.confidence).isEqualTo(0.9);
        }

        @Test
        @DisplayName("电量恰好 25% 不触发低电量 RTL（边界 < 25）")
        void batteryAtThresholdDoesNotTriggerRtl() {
            DecisionResult result = strategy.evaluate(25.0, true, true, 500.0);
            // 25.0 < 25.0 = false，不触发
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("链路丢失触发 RTL，置信度 0.85")
        void linkLostTriggersRtl() {
            DecisionResult result = strategy.evaluate(80.0, false, true, 500.0);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("RTL");
            assertThat(result.reason).isEqualTo("link lost");
            assertThat(result.confidence).isEqualTo(0.85);
        }

        @Test
        @DisplayName("GPS 退化触发 EMERGENCY_LAND，置信度 0.8")
        void gpsDegradedTriggersEmergencyLand() {
            DecisionResult result = strategy.evaluate(80.0, true, false, 500.0);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("EMERGENCY_LAND");
            assertThat(result.reason).isEqualTo("GPS degraded");
            assertThat(result.confidence).isEqualTo(0.8);
        }

        @Test
        @DisplayName("全部健康时返回 null（无决策）")
        void allHealthyReturnsNull() {
            DecisionResult result = strategy.evaluate(80.0, true, true, 500.0);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("低电量优先于链路丢失（先检查电量）")
        void lowBatteryTakesPrecedenceOverLinkLost() {
            DecisionResult result = strategy.evaluate(10.0, false, true, 500.0);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("RTL");
            assertThat(result.reason).isEqualTo("low battery");
        }

        @Test
        @DisplayName("链路丢失优先于 GPS 退化（先检查链路）")
        void linkLostTakesPrecedenceOverGpsDegraded() {
            DecisionResult result = strategy.evaluate(80.0, false, false, 500.0);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("RTL");
            assertThat(result.reason).isEqualTo("link lost");
        }
    }

    // ======================================================================
    // 风向利用测试
    // ======================================================================

    @Nested
    @DisplayName("computeWindAssistedHeading 风向利用")
    class WindAssistedHeadingTest {

        @Test
        @DisplayName("顺风：地速 = 空速 + 风速，航向不变")
        void tailwindAccelerates() {
            // 期望航向 0°（正北），风向 0°（风吹向北），顺风
            ReturnToHomeStrategy.WindAssistedHeading result =
                    strategy.computeWindAssistedHeading(0.0, 5.0, 0.0, 10.0);

            assertThat(result.heading).isCloseTo(0.0, within(EPS));
            // 地速 = 10 + 5*cos(0) = 15
            assertThat(result.groundSpeed).isCloseTo(15.0, within(1e-4));
            assertThat(result.groundSpeed).isGreaterThan(10.0); // 加速
        }

        @Test
        @DisplayName("逆风：tacking 偏航 45°，地速 < 空速（减速）")
        void headwindTackingDecelerates() {
            // 期望航向 0°，风向 180°（风吹向南），逆风
            ReturnToHomeStrategy.WindAssistedHeading result =
                    strategy.computeWindAssistedHeading(0.0, 5.0, 180.0, 10.0);

            // tacking 偏航 +45°
            assertThat(result.heading).isCloseTo(45.0, within(1e-4));
            // 地速 = 10 + 5*cos(135°) = 10 - 3.54 = 6.46
            assertThat(result.groundSpeed).isCloseTo(6.4645, within(1e-3));
            assertThat(result.groundSpeed).isLessThan(10.0); // 减速
        }

        @Test
        @DisplayName("侧风：crab angle 修正航向，地速略降")
        void crosswindCrabCorrection() {
            // 期望航向 0°，风向 90°（风吹向东），侧风
            ReturnToHomeStrategy.WindAssistedHeading result =
                    strategy.computeWindAssistedHeading(0.0, 5.0, 90.0, 10.0);

            // crab angle = asin(5/10) = 30°，航向 = 0 - 30 = -30 → 330
            assertThat(result.heading).isCloseTo(330.0, within(1e-4));
            // 地速 = 10*cos(30°) + 5*cos(90°) = 8.66 + 0 = 8.66
            assertThat(result.groundSpeed).isCloseTo(8.6603, within(1e-3));
            assertThat(result.heading).isNotCloseTo(0.0, within(1.0)); // 航向有修正
        }

        @Test
        @DisplayName("风速 > 空速 逆风：安全处理，沿风向飞，地速 = 风速 - 空速")
        void windExceedsAirspeedHeadwindSafe() {
            // 期望航向 0°，风向 180°，风速 15 > 空速 10，逆风
            ReturnToHomeStrategy.WindAssistedHeading result =
                    strategy.computeWindAssistedHeading(0.0, 15.0, 180.0, 10.0);

            // 安全处理：沿风向 180° 飞，地速 = 15 - 10 = 5
            assertThat(result.heading).isCloseTo(180.0, within(1e-4));
            assertThat(result.groundSpeed).isCloseTo(5.0, within(1e-4));
            assertThat(result.groundSpeed).isGreaterThanOrEqualTo(0.0);
        }

        @Test
        @DisplayName("风速 > 空速 顺风：地速 = 空速 + 风速，正常加速")
        void windExceedsAirspeedTailwind() {
            ReturnToHomeStrategy.WindAssistedHeading result =
                    strategy.computeWindAssistedHeading(0.0, 15.0, 0.0, 10.0);

            assertThat(result.heading).isCloseTo(0.0, within(EPS));
            assertThat(result.groundSpeed).isCloseTo(25.0, within(1e-4));
        }

        @Test
        @DisplayName("风速 > 空速 侧风：安全处理，不抛异常，地速 >= 0")
        void windExceedsAirspeedCrosswindSafe() {
            ReturnToHomeStrategy.WindAssistedHeading result =
                    strategy.computeWindAssistedHeading(0.0, 15.0, 90.0, 10.0);

            // 侧风超过空速，尽力偏航 45°
            assertThat(result.groundSpeed).isGreaterThanOrEqualTo(0.0);
            assertThat(result.heading).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
        }

        @Test
        @DisplayName("无风时：航向不变，地速 = 空速")
        void noWindPreservesHeading() {
            ReturnToHomeStrategy.WindAssistedHeading result =
                    strategy.computeWindAssistedHeading(45.0, 0.0, 0.0, 12.0);

            assertThat(result.heading).isCloseTo(45.0, within(EPS));
            assertThat(result.groundSpeed).isCloseTo(12.0, within(1e-4));
        }
    }

    // ======================================================================
    // 能耗最优返航路径测试
    // ======================================================================

    @Nested
    @DisplayName("computeOptimalReturnPath 能耗最优返航路径")
    class OptimalReturnPathTest {

        @Test
        @DisplayName("无风直线返航：路径 2 点，起点终点坐标正确，距离正确")
        void directReturnNoWind() {
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 100.0;
            double homeLat = BASE_LAT + 0.001, homeLon = BASE_LON + 0.001, homeAlt = 50.0;

            ReturnToHomeStrategy.RtlPathResult result =
                    strategy.computeOptimalReturnPath(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 80.0, 0.0, 0.0, 10.0);

            assertThat(result.path).hasSize(2);
            // 起点终点坐标
            assertThat(result.path.get(0)[0]).isCloseTo(curLat, within(EPS));
            assertThat(result.path.get(0)[1]).isCloseTo(curLon, within(EPS));
            assertThat(result.path.get(0)[2]).isCloseTo(curAlt, within(EPS));
            assertThat(result.path.get(1)[0]).isCloseTo(homeLat, within(EPS));
            assertThat(result.path.get(1)[1]).isCloseTo(homeLon, within(EPS));
            assertThat(result.path.get(1)[2]).isCloseTo(homeAlt, within(EPS));
            // 预估时间和能耗为正
            assertThat(result.estimatedTimeSec).isGreaterThan(0.0);
            assertThat(result.estimatedEnergy).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("低电量直线返航：选择最短路径（2 点）")
        void lowBatteryDirectShortest() {
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 100.0;
            double homeLat = BASE_LAT + 0.002, homeLon = BASE_LON, homeAlt = 50.0;

            // 低电量 15%，即使逆风也走直线（最短路径）
            double directHeading = Math.toDegrees(Math.atan2(
                    GeoUtil.east(curLat, curLon, homeLat, homeLon),
                    GeoUtil.north(curLat, curLon, homeLat, homeLon)));
            double headwindDir = (directHeading + 180.0) % 360.0; // 逆风方向

            ReturnToHomeStrategy.RtlPathResult result =
                    strategy.computeOptimalReturnPath(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 15.0, 5.0, headwindDir, 10.0);

            // 低电量 → 直线（2 点），不走 Z 字形
            assertThat(result.path).hasSize(2);
        }

        @Test
        @DisplayName("逆风 + 电量充足：Z 字形路径（3 点）")
        void headwindSufficientBatteryZigzag() {
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 100.0;
            double homeLat = BASE_LAT + 0.002, homeLon = BASE_LON + 0.002, homeAlt = 80.0;

            // 计算直线航向，设风向为逆风
            double north = GeoUtil.north(curLat, curLon, homeLat, homeLon);
            double east = GeoUtil.east(curLat, curLon, homeLat, homeLon);
            double directHeading = Math.toDegrees(Math.atan2(east, north));
            double headwindDir = (directHeading + 180.0) % 360.0;

            ReturnToHomeStrategy.RtlPathResult result =
                    strategy.computeOptimalReturnPath(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 80.0, 5.0, headwindDir, 10.0);

            // 逆风 + 电量 80% >= 40% → Z 字形（3 点）
            assertThat(result.path).hasSize(3);
            // 起点终点正确
            assertThat(result.path.get(0)[0]).isCloseTo(curLat, within(EPS));
            assertThat(result.path.get(2)[0]).isCloseTo(homeLat, within(EPS));
            // 中点偏离直线（Z 字形偏移）
            double midNorth = GeoUtil.north(curLat, curLon, result.path.get(1)[0], result.path.get(1)[1]);
            double midEast = GeoUtil.east(curLat, curLon, result.path.get(1)[0], result.path.get(1)[1]);
            double straightMidNorth = north / 2.0;
            double straightMidEast = east / 2.0;
            double offsetDist = Math.hypot(midNorth - straightMidNorth, midEast - straightMidEast);
            assertThat(offsetDist).isGreaterThan(1.0); // 中点有偏移
        }

        @Test
        @DisplayName("电量充足近距离：可达")
        void sufficientBatteryReachable() {
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 100.0;
            double homeLat = BASE_LAT + 0.0005, homeLon = BASE_LON, homeAlt = 80.0;

            ReturnToHomeStrategy.RtlPathResult result =
                    strategy.computeOptimalReturnPath(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 90.0, 0.0, 0.0, 10.0);

            assertThat(result.reachable).isTrue();
        }

        @Test
        @DisplayName("电量极低远距离：不可达")
        void veryLowBatteryLongDistanceUnreachable() {
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 100.0;
            // 远距离：0.1 度纬度 ≈ 11km
            double homeLat = BASE_LAT + 0.1, homeLon = BASE_LON, homeAlt = 80.0;

            ReturnToHomeStrategy.RtlPathResult result =
                    strategy.computeOptimalReturnPath(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 5.0, 0.0, 0.0, 10.0);

            assertThat(result.reachable).isFalse();
        }

        @Test
        @DisplayName("起点等于终点：0 时间 0 能耗，可达")
        void samePositionZeroTime() {
            ReturnToHomeStrategy.RtlPathResult result =
                    strategy.computeOptimalReturnPath(BASE_LAT, BASE_LON, 100.0,
                            BASE_LAT, BASE_LON, 100.0, 80.0, 0.0, 0.0, 10.0);

            assertThat(result.path).hasSize(1);
            assertThat(result.estimatedTimeSec).isCloseTo(0.0, within(EPS));
            assertThat(result.estimatedEnergy).isCloseTo(0.0, within(EPS));
            assertThat(result.reachable).isTrue();
        }
    }

    // ======================================================================
    // 地形规避测试
    // ======================================================================

    @Nested
    @DisplayName("avoidTerrain 地形规避")
    class AvoidTerrainTest {

        @Test
        @DisplayName("无障碍：路径不变（点数和坐标相同）")
        void noObstaclePathUnchanged() {
            List<double[]> path = Arrays.asList(
                    new double[]{BASE_LAT, BASE_LON, 50.0},
                    new double[]{BASE_LAT + 0.001, BASE_LON + 0.001, 50.0}
            );

            List<double[]> result = strategy.avoidTerrain(path, Collections.emptyList(), 100.0);

            assertThat(result).hasSize(path.size());
            for (int i = 0; i < path.size(); i++) {
                assertThat(result.get(i)[0]).isCloseTo(path.get(i)[0], within(EPS));
                assertThat(result.get(i)[1]).isCloseTo(path.get(i)[1], within(EPS));
                assertThat(result.get(i)[2]).isCloseTo(path.get(i)[2], within(EPS));
            }
        }

        @Test
        @DisplayName("null 障碍列表：路径不变")
        void nullObstaclePathUnchanged() {
            List<double[]> path = Arrays.asList(
                    new double[]{BASE_LAT, BASE_LON, 50.0},
                    new double[]{BASE_LAT + 0.001, BASE_LON, 50.0}
            );

            List<double[]> result = strategy.avoidTerrain(path, null, 100.0);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)[2]).isCloseTo(50.0, within(EPS));
        }

        @Test
        @DisplayName("有障碍相交：路径高度抬升到安全高度")
        void obstacleIntersectionRaisesAltitude() {
            // 低空路径穿过障碍物
            List<double[]> path = Arrays.asList(
                    new double[]{BASE_LAT, BASE_LON, 50.0},
                    new double[]{BASE_LAT + 0.001, BASE_LON + 0.001, 50.0}
            );
            // 障碍物在路径中点，高 100m，半径 100m
            List<double[]> obstacles = Collections.singletonList(
                    new double[]{BASE_LAT + 0.0005, BASE_LON + 0.0005, 100.0, 100.0}
            );

            List<double[]> result = strategy.avoidTerrain(path, obstacles, 120.0);

            assertThat(result).hasSize(2);
            // 高度应被抬升到 max(120, 100+20) = 120
            assertThat(result.get(0)[2]).isCloseTo(120.0, within(EPS));
            assertThat(result.get(1)[2]).isCloseTo(120.0, within(EPS));
        }

        @Test
        @DisplayName("障碍不相交（路径高于障碍）：路径不变")
        void obstacleNoIntersectionPathUnchanged() {
            // 高空路径，高于障碍物
            List<double[]> path = Arrays.asList(
                    new double[]{BASE_LAT, BASE_LON, 200.0},
                    new double[]{BASE_LAT + 0.001, BASE_LON + 0.001, 200.0}
            );
            List<double[]> obstacles = Collections.singletonList(
                    new double[]{BASE_LAT + 0.0005, BASE_LON + 0.0005, 100.0, 100.0}
            );

            List<double[]> result = strategy.avoidTerrain(path, obstacles, 50.0);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)[2]).isCloseTo(200.0, within(EPS));
            assertThat(result.get(1)[2]).isCloseTo(200.0, within(EPS));
        }
    }

    // ======================================================================
    // 滑翔路径测试
    // ======================================================================

    @Nested
    @DisplayName("computeGlidePath 滑翔路径")
    class GlidePathTest {

        @Test
        @DisplayName("高度足够：纯滑翔可达，无需额外动力")
        void sufficientAltitudePureGlide() {
            // 水平距离约 963m（0.01 度经度 @ lat=30），滑翔比 10:1 需下降 96.3m
            // currentAlt=200, arrivalAlt=200-96.3=103.7 >= homeAlt=50 → 纯滑翔
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 200.0;
            double homeLat = BASE_LAT, homeLon = BASE_LON + 0.01, homeAlt = 50.0;

            ReturnToHomeStrategy.GlideResult result =
                    strategy.computeGlidePath(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 10.0);

            assertThat(result.pureGlideReachable).isTrue();
            assertThat(result.requiredAdditionalPower).isCloseTo(0.0, within(EPS));
            assertThat(result.path).hasSize(2);
            // 终点高度 = arrivalAlt >= homeAlt
            assertThat(result.path.get(1)[2]).isGreaterThanOrEqualTo(homeAlt);
        }

        @Test
        @DisplayName("高度不足：需动力辅助，requiredAdditionalPower > 0")
        void insufficientAltitudeNeedsPower() {
            // 水平距离约 963m，滑翔比 10:1 需下降 96.3m
            // currentAlt=100, arrivalAlt=100-96.3=3.7 < homeAlt=50 → 高度不足
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 100.0;
            double homeLat = BASE_LAT, homeLon = BASE_LON + 0.01, homeAlt = 50.0;

            ReturnToHomeStrategy.GlideResult result =
                    strategy.computeGlidePath(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 10.0);

            assertThat(result.pureGlideReachable).isFalse();
            assertThat(result.requiredAdditionalPower).isGreaterThan(0.0);
            assertThat(result.path).hasSize(2);
            // 终点高度 = homeAlt（动力辅助维持）
            assertThat(result.path.get(1)[2]).isCloseTo(homeAlt, within(EPS));
        }

        @Test
        @DisplayName("起点等于终点：纯滑翔可达，0 动力")
        void samePositionPureGlide() {
            ReturnToHomeStrategy.GlideResult result =
                    strategy.computeGlidePath(BASE_LAT, BASE_LON, 100.0,
                            BASE_LAT, BASE_LON, 50.0, 10.0);

            assertThat(result.pureGlideReachable).isTrue();
            assertThat(result.requiredAdditionalPower).isCloseTo(0.0, within(EPS));
        }

        @Test
        @DisplayName("高滑翔比：更容易纯滑翔可达")
        void highGlideRatioEasierReach() {
            // 滑翔比 30:1，963m 只需下降 32.1m
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 100.0;
            double homeLat = BASE_LAT, homeLon = BASE_LON + 0.01, homeAlt = 50.0;

            ReturnToHomeStrategy.GlideResult result =
                    strategy.computeGlidePath(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 30.0);

            // 100 - 963/30 = 100 - 32.1 = 67.9 >= 50 → 纯滑翔
            assertThat(result.pureGlideReachable).isTrue();
        }
    }

    // ======================================================================
    // 综合返航决策测试
    // ======================================================================

    @Nested
    @DisplayName("planReturnHome 综合返航决策")
    class PlanReturnHomeTest {

        @Test
        @DisplayName("正常情况：电量充足无风无障碍，动力路径可达")
        void normalConditionsPowerPath() {
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 100.0;
            double homeLat = BASE_LAT + 0.001, homeLon = BASE_LON + 0.001, homeAlt = 80.0;

            ReturnToHomeStrategy.ReturnHomePlan plan =
                    strategy.planReturnHome(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 80.0, 0.0, 0.0, 10.0, null);

            assertThat(plan.path).isNotEmpty();
            assertThat(plan.path.get(0)[0]).isCloseTo(curLat, within(EPS));
            assertThat(plan.path.get(plan.path.size() - 1)[0]).isCloseTo(homeLat, within(EPS));
            assertThat(plan.reachable).isTrue();
            assertThat(plan.pureGlide).isFalse(); // 电量 80% 不走滑翔
        }

        @Test
        @DisplayName("极低电量 + 高度足够滑翔：优先纯滑翔")
        void veryLowBatteryGlidePreferred() {
            // 电量 10% < 15%，高度足够滑翔到家
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 300.0;
            double homeLat = BASE_LAT + 0.001, homeLon = BASE_LON, homeAlt = 50.0;

            ReturnToHomeStrategy.ReturnHomePlan plan =
                    strategy.planReturnHome(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 10.0, 0.0, 0.0, 10.0, null);

            // 距离约 111m，滑翔比 10:1 需下降 11.1m，300-11.1=288.9 >= 50 → 可纯滑翔
            // 电量 10% < 15% 且可滑翔 → pureGlide=true
            assertThat(plan.pureGlide).isTrue();
            assertThat(plan.reachable).isTrue();
        }

        @Test
        @DisplayName("有障碍：路径高度被抬升越过障碍")
        void withObstaclesPathRaised() {
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 50.0;
            double homeLat = BASE_LAT + 0.001, homeLon = BASE_LON + 0.001, homeAlt = 50.0;
            // 障碍物在路径中间，高 100m
            List<double[]> obstacles = Collections.singletonList(
                    new double[]{BASE_LAT + 0.0005, BASE_LON + 0.0005, 100.0, 100.0}
            );

            ReturnToHomeStrategy.ReturnHomePlan plan =
                    strategy.planReturnHome(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 80.0, 0.0, 0.0, 10.0, obstacles);

            // 路径高度应被抬升（不再是 50m）
            double maxAlt = 0.0;
            for (double[] p : plan.path) {
                maxAlt = Math.max(maxAlt, p[2]);
            }
            assertThat(maxAlt).isGreaterThan(50.0);
            assertThat(plan.pureGlide).isFalse(); // 电量 80% 不走滑翔
        }

        @Test
        @DisplayName("极低电量 + 高度不足滑翔：走动力路径")
        void veryLowBatteryNoGlidePowerPath() {
            // 电量 10%，但高度不足滑翔
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 60.0;
            // 远距离 0.01 度 ≈ 963m，滑翔比 10:1 需下降 96.3m，60-96.3 < 0 < homeAlt
            double homeLat = BASE_LAT, homeLon = BASE_LON + 0.01, homeAlt = 50.0;

            ReturnToHomeStrategy.ReturnHomePlan plan =
                    strategy.planReturnHome(curLat, curLon, curAlt,
                            homeLat, homeLon, homeAlt, 10.0, 0.0, 0.0, 10.0, null);

            // 高度不足滑翔 → 不走纯滑翔
            assertThat(plan.pureGlide).isFalse();
        }
    }
}
