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
 * AdaptivePathStrategy 自适应航线策略单测（M11）。
 * <p>
 * 纯 JUnit 5 + AssertJ，覆盖：
 * <ul>
 *   <li>旧接口 {@code evaluate} 向后兼容（7 个原测试）</li>
 *   <li>风补偿 {@code computeWindCorrectedHeading}（无风/逆风/侧风/风速>空速/空速=0）</li>
 *   <li>能耗优化 {@code computeEnergyOptimalSpeed}（无风/逆风/顺风/低电量/低电量优先）</li>
 *   <li>Dubins 平滑 {@code smoothPathWithDubins}（直线不变/尖角平滑/长度增加/边界）</li>
 *   <li>综合适应 {@code adaptPath}（多航段修正/非法输入）</li>
 * </ul>
 */
@DisplayName("AdaptivePathStrategy 自适应航线策略 (M11)")
class AdaptivePathStrategyTest {

    private AdaptivePathStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new AdaptivePathStrategy();
    }

    // ==================================================================
    // 旧接口 evaluate 向后兼容（保留原 7 个测试）
    // ==================================================================

    @Nested
    @DisplayName("evaluate 旧接口（向后兼容）")
    class EvaluateLegacyTest {

        @Test
        @DisplayName("风速 > 8 m/s 触发 ADAPT_PATH，reason=strong wind，置信度 0.6")
        void strongWindTriggersAdaptPath() {
            DecisionResult result = strategy.evaluate(12.0, 80.0);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("ADAPT_PATH");
            assertThat(result.reason).isEqualTo("strong wind");
            assertThat(result.triggerValue).isEqualTo(12.0);
            assertThat(result.confidence).isEqualTo(0.6);
        }

        @Test
        @DisplayName("风速恰好 8 m/s 不触发强风决策（边界 wind > 8）")
        void boundaryWind8DoesNotTriggerStrongWind() {
            DecisionResult result = strategy.evaluate(8.0, 80.0);
            // 8.0 > 8.0 = false，不触发强风；电量 80 >= 40，也不触发
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("电量 < 40 触发 ADAPT_PATH，reason=battery optimization，置信度 0.5")
        void lowBatteryTriggersAdaptPath() {
            DecisionResult result = strategy.evaluate(5.0, 30.0);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("ADAPT_PATH");
            assertThat(result.reason).isEqualTo("battery optimization");
            assertThat(result.triggerValue).isEqualTo(30.0);
            assertThat(result.confidence).isEqualTo(0.5);
        }

        @Test
        @DisplayName("电量恰好 40 不触发低电量决策（边界 battery < 40）")
        void boundaryBattery40DoesNotTrigger() {
            DecisionResult result = strategy.evaluate(5.0, 40.0);
            // 40.0 < 40.0 = false
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("风速和电量都正常时返回 null（无决策）")
        void normalConditionsReturnNull() {
            DecisionResult result = strategy.evaluate(5.0, 80.0);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("强风优先于低电量（先检查风速）")
        void strongWindTakesPrecedenceOverLowBattery() {
            DecisionResult result = strategy.evaluate(15.0, 20.0);

            assertThat(result).isNotNull();
            assertThat(result.reason).isEqualTo("strong wind");
        }

        @Test
        @DisplayName("低风速 + 低电量触发 battery optimization")
        void lowWindLowBatteryTriggersBatteryOptimization() {
            DecisionResult result = strategy.evaluate(3.0, 20.0);

            assertThat(result).isNotNull();
            assertThat(result.reason).isEqualTo("battery optimization");
            assertThat(result.triggerValue).isEqualTo(20.0);
        }
    }

    // ==================================================================
    // 风补偿 computeWindCorrectedHeading
    // ==================================================================

    @Nested
    @DisplayName("computeWindCorrectedHeading 风补偿航向")
    class WindCorrectedHeadingTest {

        @Test
        @DisplayName("无风时航向不变")
        void noWindHeadingUnchanged() {
            double heading = strategy.computeWindCorrectedHeading(45.0, 0.0, 90.0, 10.0);
            assertThat(heading).isEqualTo(45.0, within(1e-9));
        }

        @Test
        @DisplayName("空速为 0 时航向不变（安全处理）")
        void zeroAirspeedHeadingUnchanged() {
            double heading = strategy.computeWindCorrectedHeading(45.0, 5.0, 90.0, 0.0);
            assertThat(heading).isEqualTo(45.0, within(1e-9));
        }

        @Test
        @DisplayName("逆风（风向=航向）时航向不变：WCA=arcsin(0)=0")
        void headwindHeadingUnchanged() {
            // 航向正北，北风（从北吹来），逆风
            double heading = strategy.computeWindCorrectedHeading(0.0, 5.0, 0.0, 10.0);
            assertThat(heading).isEqualTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("顺风（风向=航向+180）时航向不变：sin(180°)=0")
        void tailwindHeadingUnchanged() {
            // 航向正北，南风（从南吹来），顺风
            double heading = strategy.computeWindCorrectedHeading(0.0, 5.0, 180.0, 10.0);
            assertThat(heading).isEqualTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("右侧风（风向=航向+90）时航向东修正：WCA=arcsin(5/10)=30°")
        void rightCrosswindCorrectsEast() {
            // 航向正北，东风（从东吹来），右侧风
            // WCA = arcsin(5 * sin(90°) / 10) = arcsin(0.5) = 30°
            double heading = strategy.computeWindCorrectedHeading(0.0, 5.0, 90.0, 10.0);
            assertThat(heading).isEqualTo(30.0, within(1e-6));
        }

        @Test
        @DisplayName("左侧风（风向=航向-90）时航向西修正：WCA=arcsin(-0.5)=-30°")
        void leftCrosswindCorrectsWest() {
            // 航向正北，西风（从西吹来），左侧风
            // WCA = arcsin(5 * sin(-90°) / 10) = arcsin(-0.5) = -30°
            double heading = strategy.computeWindCorrectedHeading(0.0, 5.0, 270.0, 10.0);
            // 归一化后 -30° → 330°
            assertThat(heading).isEqualTo(330.0, within(1e-6));
        }

        @Test
        @DisplayName("风速 > 空速时不产生 NaN，返回有限值")
        void windExceedsAirspeedNoNaN() {
            // 航向正北，东风，风速 15 > 空速 10
            // ratio = 15 * sin(90°) / 10 = 1.5 > 1 → WCA 限制到 90°
            double heading = strategy.computeWindCorrectedHeading(0.0, 15.0, 90.0, 10.0);
            assertThat(heading).isNotNaN();
            assertThat(heading).isFinite();
            // WCA = +90°，修正后航向 = 0 + 90 = 90°
            assertThat(heading).isEqualTo(90.0, within(1e-6));
        }

        @Test
        @DisplayName("风速 > 空速的左侧风：WCA 限制到 -90°")
        void windExceedsAirspeedLeftCrosswind() {
            // 航向正北，西风，风速 20 > 空速 10
            // ratio = 20 * sin(-90°) / 10 = -2 < -1 → WCA 限制到 -90°
            double heading = strategy.computeWindCorrectedHeading(0.0, 20.0, 270.0, 10.0);
            assertThat(heading).isNotNaN();
            assertThat(heading).isFinite();
            // WCA = -90°，归一化后 0 - 90 = -90 → 270°
            assertThat(heading).isEqualTo(270.0, within(1e-6));
        }

        @Test
        @DisplayName("航向归一化到 [0, 360)")
        void headingNormalized() {
            // 航向 350°，右侧风 WCA=30° → 380° → 归一化 20°
            double heading = strategy.computeWindCorrectedHeading(350.0, 5.0, 80.0, 10.0);
            assertThat(heading).isGreaterThanOrEqualTo(0.0);
            assertThat(heading).isLessThan(360.0);
        }
    }

    // ==================================================================
    // 能耗优化 computeEnergyOptimalSpeed
    // ==================================================================

    @Nested
    @DisplayName("computeEnergyOptimalSpeed 能耗最优速度")
    class EnergyOptimalSpeedTest {

        @Test
        @DisplayName("无风时保持基础速度")
        void noWindKeepsBaseSpeed() {
            double speed = strategy.computeEnergyOptimalSpeed(1000.0, 80.0, 0.0, 10.0);
            assertThat(speed).isEqualTo(10.0, within(1e-9));
        }

        @Test
        @DisplayName("逆风时降速：factor=1-0.3*5/10=0.85，speed=8.5")
        void headwindReducesSpeed() {
            double speed = strategy.computeEnergyOptimalSpeed(1000.0, 80.0, 5.0, 10.0);
            assertThat(speed).isEqualTo(8.5, within(1e-9));
            assertThat(speed).isLessThan(10.0);
        }

        @Test
        @DisplayName("强逆风时降速到下限：factor=1-0.3*20/10=0.4 → 限制到 0.6，speed=6.0")
        void strongHeadwindClampedToMin() {
            double speed = strategy.computeEnergyOptimalSpeed(1000.0, 80.0, 20.0, 10.0);
            assertThat(speed).isEqualTo(6.0, within(1e-9));
        }

        @Test
        @DisplayName("顺风时提速：factor=1+0.2*5/10=1.1，speed=11.0")
        void tailwindIncreasesSpeed() {
            double speed = strategy.computeEnergyOptimalSpeed(1000.0, 80.0, -5.0, 10.0);
            assertThat(speed).isEqualTo(11.0, within(1e-9));
            assertThat(speed).isGreaterThan(10.0);
        }

        @Test
        @DisplayName("强顺风时提速到上限：factor=1+0.2*20/10=1.4 → 限制到 1.2，speed=12.0")
        void strongTailwindClampedToMax() {
            double speed = strategy.computeEnergyOptimalSpeed(1000.0, 80.0, -20.0, 10.0);
            assertThat(speed).isEqualTo(12.0, within(1e-9));
        }

        @Test
        @DisplayName("低电量时降速到 0.7 倍：speed=7.0")
        void lowBatteryReducesSpeed() {
            double speed = strategy.computeEnergyOptimalSpeed(1000.0, 30.0, 0.0, 10.0);
            assertThat(speed).isEqualTo(7.0, within(1e-9));
        }

        @Test
        @DisplayName("低电量优先于逆风：电量 30 + 逆风 5 → speed=7.0（非 8.5）")
        void lowBatteryTakesPrecedenceOverHeadwind() {
            double speed = strategy.computeEnergyOptimalSpeed(1000.0, 30.0, 5.0, 10.0);
            assertThat(speed).isEqualTo(7.0, within(1e-9));
        }

        @Test
        @DisplayName("低电量优先于顺风：电量 30 + 顺风 5 → speed=7.0（非 11.0）")
        void lowBatteryTakesPrecedenceOverTailwind() {
            double speed = strategy.computeEnergyOptimalSpeed(1000.0, 30.0, -5.0, 10.0);
            assertThat(speed).isEqualTo(7.0, within(1e-9));
        }

        @Test
        @DisplayName("电量边界 40 不触发低电量：speed=10.0（无风）")
        void batteryBoundary40NoLowBattery() {
            double speed = strategy.computeEnergyOptimalSpeed(1000.0, 40.0, 0.0, 10.0);
            assertThat(speed).isEqualTo(10.0, within(1e-9));
        }

        @Test
        @DisplayName("baseSpeed=0 时返回 0")
        void zeroBaseSpeedReturnsZero() {
            double speed = strategy.computeEnergyOptimalSpeed(1000.0, 80.0, 5.0, 0.0);
            assertThat(speed).isEqualTo(0.0, within(1e-9));
        }
    }

    // ==================================================================
    // Dubins 平滑 smoothPathWithDubins
    // ==================================================================

    @Nested
    @DisplayName("smoothPathWithDubins Dubins 曲线平滑")
    class SmoothPathWithDubinsTest {

        @Test
        @DisplayName("直线路径不变：所有点共线，转角 ≤ 5°")
        void straightPathUnchanged() {
            // 三点共线（正北方向）
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0},
                    new double[]{0.002, 0.0, 10.0}
            );

            List<double[]> smoothed = strategy.smoothPathWithDubins(path, 10.0);

            // 直线路径点数不变
            assertThat(smoothed).hasSize(3);
            // 起终点保持
            assertThat(smoothed.get(0)[0]).isEqualTo(0.0, within(1e-9));
            assertThat(smoothed.get(2)[0]).isEqualTo(0.002, within(1e-9));
        }

        @Test
        @DisplayName("尖角路径被平滑：L 形 90° 转角，点数增加")
        void sharpCornerSmoothed() {
            // L 形路径：北 → 东，90° 右转
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0},
                    new double[]{0.001, 0.001, 10.0}
            );

            List<double[]> smoothed = strategy.smoothPathWithDubins(path, 10.0);

            // 尖角被圆弧替换，点数 > 原路径
            assertThat(smoothed.size()).isGreaterThan(3);
            // 圆弧采样 9 个点（ARC_SAMPLES+1），替换中间 1 点 → 2 + 9 = 11
            assertThat(smoothed).hasSize(11);
            // 起终点保持
            assertThat(smoothed.get(0)[0]).isEqualTo(0.0, within(1e-9));
            assertThat(smoothed.get(smoothed.size() - 1)[1]).isEqualTo(0.001, within(1e-9));
        }

        @Test
        @DisplayName("平滑后圆弧段长度大于切点直连距离（圆弧 > 弦，Dubins 特性）")
        void smoothedArcLongerThanChord() {
            // L 形路径：北 → 东，90° 右转
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0},
                    new double[]{0.001, 0.001, 10.0}
            );

            List<double[]> smoothed = strategy.smoothPathWithDubins(path, 10.0);
            double smoothedLen = pathLength(smoothed);

            // 起点到终点的直线距离（斜边）
            double[] start = path.get(0);
            double[] end = path.get(2);
            double chordLen = Math.hypot(
                    GeoUtil.north(start[0], start[1], end[0], end[1]),
                    GeoUtil.east(start[0], start[1], end[0], end[1]));

            // Dubins 圆弧切角，平滑后路径长度介于"切点直连"与"原始尖角路径"之间：
            // chordLen < smoothedLen < origLen（圆弧绕过转角内侧，比斜边长但比尖角短）
            assertThat(smoothedLen).isGreaterThan(chordLen);

            // 同时验证：平滑后路径长度有限且为正
            assertThat(smoothedLen).isFinite();
            assertThat(smoothedLen).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("平滑后转角更平滑：中间转角显著减小")
        void smoothedTurnAngleReduced() {
            // L 形路径：原 90° 转角
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0},
                    new double[]{0.001, 0.001, 10.0}
            );

            List<double[]> smoothed = strategy.smoothPathWithDubins(path, 10.0);

            // 平滑后所有内部转角应 < 原始 90°
            double maxTurn = 0.0;
            for (int i = 1; i < smoothed.size() - 1; i++) {
                double turn = turnAngle(smoothed.get(i - 1), smoothed.get(i), smoothed.get(i + 1));
                maxTurn = Math.max(maxTurn, Math.abs(turn));
            }
            assertThat(maxTurn).isLessThan(90.0);
        }

        @Test
        @DisplayName("空路径返回空列表")
        void emptyPathReturnsEmpty() {
            List<double[]> smoothed = strategy.smoothPathWithDubins(Collections.emptyList(), 10.0);
            assertThat(smoothed).isEmpty();
        }

        @Test
        @DisplayName("null 路径返回空列表")
        void nullPathReturnsEmpty() {
            List<double[]> smoothed = strategy.smoothPathWithDubins(null, 10.0);
            assertThat(smoothed).isEmpty();
        }

        @Test
        @DisplayName("单点路径返回副本")
        void singlePointReturnsCopy() {
            List<double[]> path = Collections.singletonList(new double[]{1.0, 2.0, 3.0});
            List<double[]> smoothed = strategy.smoothPathWithDubins(path, 10.0);
            assertThat(smoothed).hasSize(1);
            assertThat(smoothed.get(0)[0]).isEqualTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("两点路径返回副本（无转角可平滑）")
        void twoPointsReturnCopy() {
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0}
            );
            List<double[]> smoothed = strategy.smoothPathWithDubins(path, 10.0);
            assertThat(smoothed).hasSize(2);
        }

        @Test
        @DisplayName("turnRadius ≤ 0 返回原路径副本")
        void zeroTurnRadiusReturnsCopy() {
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0},
                    new double[]{0.001, 0.001, 10.0}
            );
            List<double[]> smoothed = strategy.smoothPathWithDubins(path, 0.0);
            assertThat(smoothed).hasSize(3);
        }

        @Test
        @DisplayName("小转角（≤5°）不被平滑")
        void smallTurnNotSmoothed() {
            // 构造一个转角约 2° 的路径（每段偏 1°，总转角 2° < 5° 阈值）
            // 正北方向，中间点偏东一点点（产生小转角）
            double offset = 0.001 * Math.tan(Math.toRadians(1.0)); // 偏移产生约 1° 偏转
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, offset, 10.0},
                    new double[]{0.002, 0.0, 10.0}
            );

            List<double[]> smoothed = strategy.smoothPathWithDubins(path, 10.0);
            // 小转角不平滑，点数不变
            assertThat(smoothed).hasSize(3);
        }

        @Test
        @DisplayName("多个尖角的路径全部被平滑")
        void multipleSharpCornersAllSmoothed() {
            // Z 形路径：北 → 东 → 北，两个 90° 转角
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0},
                    new double[]{0.001, 0.001, 10.0},
                    new double[]{0.002, 0.001, 10.0}
            );

            List<double[]> smoothed = strategy.smoothPathWithDubins(path, 10.0);

            // 两个尖角各被替换为 9 个圆弧点 → 2 + 9 + 9 = 20
            assertThat(smoothed.size()).isGreaterThan(4);
            assertThat(smoothed).hasSize(20);
        }
    }

    // ==================================================================
    // 综合自适应 adaptPath
    // ==================================================================

    @Nested
    @DisplayName("adaptPath 综合自适应航线")
    class AdaptPathTest {

        @Test
        @DisplayName("多航段路径正确修正：返回修正路径 + 速度 + 航向 + 能耗")
        void multiSegmentPathCorrected() {
            // L 形路径：北 → 东
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0},
                    new double[]{0.001, 0.001, 10.0}
            );

            AdaptivePathResult result = strategy.adaptPath(path, 5.0, 90.0, 10.0, 80.0, 10.0);

            // 修正路径非空，且经过 Dubins 平滑（点数 > 原始）
            assertThat(result.correctedPath).isNotEmpty();
            assertThat(result.correctedPath.size()).isGreaterThan(3);

            // 各段速度和航向列表长度 = 路径段数
            int segCount = result.correctedPath.size() - 1;
            assertThat(result.segmentSpeeds).hasSize(segCount);
            assertThat(result.correctedHeadings).hasSize(segCount);

            // 所有速度为正且有限
            for (double speed : result.segmentSpeeds) {
                assertThat(speed).isNotNaN();
                assertThat(speed).isFinite();
                assertThat(speed).isGreaterThanOrEqualTo(0.0);
            }

            // 所有航向在 [0, 360) 范围
            for (double heading : result.correctedHeadings) {
                assertThat(heading).isGreaterThanOrEqualTo(0.0);
                assertThat(heading).isLessThan(360.0);
            }

            // 总能耗为正（有距离 + 有速度）
            assertThat(result.totalEnergyEstimate).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("无风时航向等于期望航向，速度等于空速")
        void noWindHeadingAndSpeedUnchanged() {
            // 直线路径（正北方向）
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0}
            );

            AdaptivePathResult result = strategy.adaptPath(path, 0.0, 0.0, 10.0, 80.0, 10.0);

            assertThat(result.correctedPath).hasSize(2);
            assertThat(result.segmentSpeeds).hasSize(1);
            assertThat(result.correctedHeadings).hasSize(1);

            // 无风 → 速度 = 空速
            assertThat(result.segmentSpeeds.get(0)).isEqualTo(10.0, within(1e-9));
            // 正北方向 → 航向 0°
            assertThat(result.correctedHeadings.get(0)).isEqualTo(0.0, within(1e-6));
        }

        @Test
        @DisplayName("低电量时所有段降速到 0.7 倍")
        void lowBatteryAllSegmentsReduced() {
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0},
                    new double[]{0.001, 0.001, 10.0}
            );

            AdaptivePathResult result = strategy.adaptPath(path, 0.0, 0.0, 10.0, 30.0, 10.0);

            // 低电量 → 所有段速度 = 10 * 0.7 = 7.0
            for (double speed : result.segmentSpeeds) {
                if (speed > 0.0) {
                    assertThat(speed).isEqualTo(7.0, within(1e-9));
                }
            }
        }

        @Test
        @DisplayName("空路径返回空结果")
        void emptyPathReturnsEmptyResult() {
            AdaptivePathResult result = strategy.adaptPath(Collections.emptyList(), 5.0, 90.0, 10.0, 80.0, 10.0);

            assertThat(result.correctedPath).isEmpty();
            assertThat(result.segmentSpeeds).isEmpty();
            assertThat(result.correctedHeadings).isEmpty();
            assertThat(result.totalEnergyEstimate).isEqualTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("null 路径返回空结果")
        void nullPathReturnsEmptyResult() {
            AdaptivePathResult result = strategy.adaptPath(null, 5.0, 90.0, 10.0, 80.0, 10.0);

            assertThat(result.correctedPath).isEmpty();
            assertThat(result.segmentSpeeds).isEmpty();
            assertThat(result.correctedHeadings).isEmpty();
        }

        @Test
        @DisplayName("单点路径返回空结果（<2 点）")
        void singlePointReturnsEmptyResult() {
            List<double[]> path = Collections.singletonList(new double[]{0.0, 0.0, 10.0});
            AdaptivePathResult result = strategy.adaptPath(path, 5.0, 90.0, 10.0, 80.0, 10.0);

            assertThat(result.correctedPath).isEmpty();
            assertThat(result.segmentSpeeds).isEmpty();
        }

        @Test
        @DisplayName("修正路径起点和终点保持与原始一致")
        void endpointsPreserved() {
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0},
                    new double[]{0.001, 0.001, 10.0}
            );

            AdaptivePathResult result = strategy.adaptPath(path, 5.0, 90.0, 10.0, 80.0, 10.0);

            List<double[]> corrected = result.correctedPath;
            // 起点
            assertThat(corrected.get(0)[0]).isEqualTo(0.0, within(1e-9));
            assertThat(corrected.get(0)[1]).isEqualTo(0.0, within(1e-9));
            // 终点
            assertThat(corrected.get(corrected.size() - 1)[0]).isEqualTo(0.001, within(1e-9));
            assertThat(corrected.get(corrected.size() - 1)[1]).isEqualTo(0.001, within(1e-9));
        }

        @Test
        @DisplayName("能耗估算非负")
        void energyEstimateNonNegative() {
            List<double[]> path = Arrays.asList(
                    new double[]{0.0, 0.0, 10.0},
                    new double[]{0.001, 0.0, 10.0}
            );

            AdaptivePathResult result = strategy.adaptPath(path, 5.0, 90.0, 10.0, 80.0, 10.0);
            assertThat(result.totalEnergyEstimate).isGreaterThanOrEqualTo(0.0);
            assertThat(result.totalEnergyEstimate).isNotNaN();
        }
    }

    // ==================================================================
    // 测试辅助方法
    // ==================================================================

    /** 计算路径总长度（米，3D） */
    private static double pathLength(List<double[]> path) {
        if (path == null || path.size() < 2) {
            return 0.0;
        }
        double len = 0.0;
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            double dn = GeoUtil.north(a[0], a[1], b[0], b[1]);
            double de = GeoUtil.east(a[0], a[1], b[0], b[1]);
            double da = b[2] - a[2];
            len += Math.sqrt(dn * dn + de * de + da * da);
        }
        return len;
    }

    /** 计算转向角（度，正值右转，负值左转） */
    private static double turnAngle(double[] prev, double[] curr, double[] next) {
        double v1n = GeoUtil.north(curr[0], curr[1], prev[0], prev[1]);
        double v1e = GeoUtil.east(curr[0], curr[1], prev[0], prev[1]);
        double v2n = GeoUtil.north(curr[0], curr[1], next[0], next[1]);
        double v2e = GeoUtil.east(curr[0], curr[1], next[0], next[1]);

        double len1 = Math.hypot(v1n, v1e);
        double len2 = Math.hypot(v2n, v2e);
        if (len1 < 1e-9 || len2 < 1e-9) {
            return 0.0;
        }

        double inN = -v1n / len1, inE = -v1e / len1;
        double outN = v2n / len2, outE = v2e / len2;

        double cross = inN * outE - inE * outN;
        double dot = inN * outN + inE * outE;
        return Math.toDegrees(Math.atan2(cross, dot));
    }
}
