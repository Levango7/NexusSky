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

/**
 * ObstacleAvoidanceStrategy + PathPlanner 单测（M11）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>旧接口 {@link ObstacleAvoidanceStrategy#evaluate} 向后兼容</li>
 *   <li>A* 网格搜索：无障碍直线 / 有障碍绕行 / 无可行路径返回空</li>
 *   <li>RRT 快速扩展随机树：基本规划成功 / 路径平滑后更短</li>
 *   <li>{@link ObstacleAvoidanceStrategy#avoidWithPath} 返回有效路径</li>
 *   <li>碰撞检测：路径不与障碍物相交</li>
 * </ul>
 */
@DisplayName("ObstacleAvoidanceStrategy 自动避障策略 + PathPlanner 路径规划 (M11)")
class ObstacleAvoidanceStrategyTest {

    private ObstacleAvoidanceStrategy strategy;
    private PathPlanner planner;

    // 测试基准点（杭州附近）
    private static final double BASE_LAT = 30.0;
    private static final double BASE_LON = 120.0;

    @BeforeEach
    void setUp() {
        planner = new PathPlanner();
        strategy = new ObstacleAvoidanceStrategy(planner);
    }

    // ======================================================================
    // 旧接口向后兼容测试
    // ======================================================================

    @Nested
    @DisplayName("evaluate 旧接口（向后兼容）")
    class LegacyEvaluateTest {

        @Test
        @DisplayName("无障碍物时返回 null（无决策）")
        void noObstacleReturnsNull() {
            DecisionResult result = strategy.evaluate(false, 100.0);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("有障碍物且高度 < 50 时触发爬升 AVOID，置信度 0.75")
        void obstacleAtLowAltitudeTriggersClimb() {
            DecisionResult result = strategy.evaluate(true, 30.0);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("AVOID");
            assertThat(result.reason).isEqualTo("obstacle ahead, climb");
            assertThat(result.triggerValue).isEqualTo(30.0);
            assertThat(result.confidence).isEqualTo(0.75);
        }

        @Test
        @DisplayName("有障碍物且高度 >= 50 时触发重规划 AVOID，置信度 0.7")
        void obstacleAtHighAltitudeTriggersReroute() {
            DecisionResult result = strategy.evaluate(true, 80.0);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("AVOID");
            assertThat(result.reason).isEqualTo("obstacle ahead, reroute");
            assertThat(result.confidence).isEqualTo(0.7);
        }

        @Test
        @DisplayName("高度恰好 50 时走 reroute 分支（边界 alt < 50）")
        void boundaryAlt50TriggersReroute() {
            DecisionResult result = strategy.evaluate(true, 50.0);

            assertThat(result).isNotNull();
            assertThat(result.reason).isEqualTo("obstacle ahead, reroute");
            assertThat(result.confidence).isEqualTo(0.7);
        }

        @Test
        @DisplayName("高度恰好 49 时走 climb 分支")
        void boundaryAlt49TriggersClimb() {
            DecisionResult result = strategy.evaluate(true, 49.0);

            assertThat(result).isNotNull();
            assertThat(result.reason).isEqualTo("obstacle ahead, climb");
            assertThat(result.confidence).isEqualTo(0.75);
        }

        @Test
        @DisplayName("爬升决策 triggerValue 为当前高度")
        void climbDecisionCarriesAltitude() {
            DecisionResult result = strategy.evaluate(true, 25.0);

            assertThat(result).isNotNull();
            assertThat(result.triggerValue).isEqualTo(25.0);
        }

        @Test
        @DisplayName("重规划决策 triggerValue 为 0")
        void rerouteDecisionZeroTriggerValue() {
            DecisionResult result = strategy.evaluate(true, 100.0);

            assertThat(result).isNotNull();
            assertThat(result.triggerValue).isEqualTo(0.0);
        }
    }

    // ======================================================================
    // A* 网格搜索测试
    // ======================================================================

    @Nested
    @DisplayName("A* 网格搜索 planAStar")
    class AStarTest {

        @Test
        @DisplayName("无障碍物时返回近似直线路径")
        void noObstacleReturnsStraightPath() {
            // 起点到终点约 130m
            double startLat = BASE_LAT, startLon = BASE_LON;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 100.0, 100.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 100.0, 100.0);

            List<double[]> path = planner.planAStar(startLat, startLon, goalLat, goalLon,
                    Collections.emptyList(), 5.0);

            assertThat(path).isNotEmpty();
            // 起点精确
            assertThat(path.get(0)[0]).isEqualTo(startLat);
            assertThat(path.get(0)[1]).isEqualTo(startLon);
            // 终点精确
            assertThat(path.get(path.size() - 1)[0]).isEqualTo(goalLat);
            assertThat(path.get(path.size() - 1)[1]).isEqualTo(goalLon);
            // 路径长度接近直线距离（允许网格量化误差 10%）
            double straightDist = Math.hypot(100.0, 100.0);
            double pathLen = PathPlanner.pathLength(path);
            assertThat(pathLen).isBetween(straightDist, straightDist * 1.1);
        }

        @Test
        @DisplayName("有障碍物时绕行：路径不穿过障碍物且长度大于直线距离")
        void obstacleCausesDetour() {
            double startLat = BASE_LAT, startLon = BASE_LON;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            // 障碍物在正中间，半径 15m（堵住直线通道）
            PathPlanner.Obstacle obs = new PathPlanner.Obstacle(
                    GeoUtil.latOf(BASE_LAT, BASE_LON, 50.0, 0.0),
                    GeoUtil.lonOf(BASE_LAT, BASE_LON, 50.0, 0.0),
                    200.0, 15.0);

            List<double[]> path = planner.planAStar(startLat, startLon, goalLat, goalLon,
                    Collections.singletonList(obs), 5.0);

            assertThat(path).isNotEmpty();
            // 路径不与障碍物相交
            assertThat(planner.pathCollides(path, Collections.singletonList(obs)))
                    .as("A* 路径不应穿过障碍物").isFalse();
            // 路径长度大于直线距离（绕行了）
            double pathLen = PathPlanner.pathLength(path);
            assertThat(pathLen).isGreaterThan(100.0);
        }

        @Test
        @DisplayName("终点在障碍物内时返回空（无可行路径）")
        void goalInsideObstacleReturnsEmpty() {
            double startLat = BASE_LAT, startLon = BASE_LON;
            // 终点设在障碍物中心
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 80.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 80.0, 0.0);
            PathPlanner.Obstacle obs = new PathPlanner.Obstacle(goalLat, goalLon, 200.0, 20.0);

            List<double[]> path = planner.planAStar(startLat, startLon, goalLat, goalLon,
                    Collections.singletonList(obs), 5.0);

            assertThat(path).isEmpty();
        }

        @Test
        @DisplayName("起点等于终点时返回单点路径")
        void startEqualsGoalReturnsSinglePoint() {
            List<double[]> path = planner.planAStar(BASE_LAT, BASE_LON, BASE_LAT, BASE_LON,
                    Collections.emptyList(), 5.0);
            assertThat(path).hasSize(1);
        }

        @Test
        @DisplayName("多个障碍物时仍能规划出无碰撞路径")
        void multipleObstaclesPathAvoidsAll() {
            double startLat = BASE_LAT, startLon = BASE_LON;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 120.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 120.0, 0.0);
            // 两个障碍物错开摆放，形成 S 形通道
            PathPlanner.Obstacle o1 = new PathPlanner.Obstacle(
                    GeoUtil.latOf(BASE_LAT, BASE_LON, 40.0, 0.0),
                    GeoUtil.lonOf(BASE_LAT, BASE_LON, 40.0, 0.0),
                    200.0, 12.0);
            PathPlanner.Obstacle o2 = new PathPlanner.Obstacle(
                    GeoUtil.latOf(BASE_LAT, BASE_LON, 80.0, 20.0),
                    GeoUtil.lonOf(BASE_LAT, BASE_LON, 80.0, 20.0),
                    200.0, 12.0);
            List<PathPlanner.Obstacle> obstacles = Arrays.asList(o1, o2);

            List<double[]> path = planner.planAStar(startLat, startLon, goalLat, goalLon,
                    obstacles, 5.0);

            assertThat(path).isNotEmpty();
            assertThat(planner.pathCollides(path, obstacles))
                    .as("A* 路径应绕开所有障碍物").isFalse();
        }
    }

    // ======================================================================
    // RRT 快速扩展随机树测试
    // ======================================================================

    @Nested
    @DisplayName("RRT 快速扩展随机树 planRRT")
    class RrtTest {

        @Test
        @DisplayName("无障碍物时基本规划成功")
        void noObstaclePlansSuccessfully() {
            double startLat = BASE_LAT, startLon = BASE_LON, startAlt = 50.0;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 80.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 80.0, 0.0);
            double goalAlt = 60.0;

            List<double[]> path = planner.planRRT(startLat, startLon, startAlt,
                    goalLat, goalLon, goalAlt,
                    Collections.emptyList(), BASE_LAT, BASE_LON, 200.0);

            assertThat(path).isNotEmpty();
            // 起点精确
            assertThat(path.get(0)[0]).isEqualTo(startLat);
            assertThat(path.get(0)[1]).isEqualTo(startLon);
            assertThat(path.get(0)[2]).isEqualTo(startAlt);
            // 终点精确
            assertThat(path.get(path.size() - 1)[0]).isEqualTo(goalLat);
            assertThat(path.get(path.size() - 1)[1]).isEqualTo(goalLon);
            assertThat(path.get(path.size() - 1)[2]).isEqualTo(goalAlt);
        }

        @Test
        @DisplayName("有障碍物时规划成功且路径不与障碍物相交")
        void obstacleAvoidingPath() {
            double startLat = BASE_LAT, startLon = BASE_LON, startAlt = 50.0;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            double goalAlt = 50.0;
            // 障碍物在中间，但 RRT 可从上方（alt > 200）绕过或侧向绕过
            PathPlanner.Obstacle obs = new PathPlanner.Obstacle(
                    GeoUtil.latOf(BASE_LAT, BASE_LON, 50.0, 0.0),
                    GeoUtil.lonOf(BASE_LAT, BASE_LON, 50.0, 0.0),
                    200.0, 15.0);
            List<PathPlanner.Obstacle> obstacles = Collections.singletonList(obs);

            List<double[]> path = planner.planRRT(startLat, startLon, startAlt,
                    goalLat, goalLon, goalAlt,
                    obstacles, BASE_LAT, BASE_LON, 250.0);

            assertThat(path).isNotEmpty();
            assertThat(planner.pathCollides(path, obstacles))
                    .as("RRT 路径不应穿过障碍物").isFalse();
        }

        @Test
        @DisplayName("路径平滑后更短或相等")
        void smoothedPathShorterOrEqual() {
            double startLat = BASE_LAT, startLon = BASE_LON, startAlt = 50.0;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 80.0, 60.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 80.0, 60.0);
            double goalAlt = 60.0;

            List<double[]> rawPath = planner.planRRT(startLat, startLon, startAlt,
                    goalLat, goalLon, goalAlt,
                    Collections.emptyList(), BASE_LAT, BASE_LON, 200.0);
            assertThat(rawPath).isNotEmpty();

            double rawLen = PathPlanner.pathLength(rawPath);
            List<double[]> smoothed = planner.smoothPath(rawPath);
            double smoothedLen = PathPlanner.pathLength(smoothed);

            assertThat(smoothed).isNotEmpty();
            // 平滑后路径应更短或相等（shortcut smoothing 移除冗余中间点）
            assertThat(smoothedLen).isLessThanOrEqualTo(rawLen + 1e-6);
        }

        @Test
        @DisplayName("平滑后路径起终点保持不变")
        void smoothedPathKeepsEndpoints() {
            double startLat = BASE_LAT, startLon = BASE_LON, startAlt = 50.0;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 80.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 80.0, 0.0);
            double goalAlt = 60.0;

            List<double[]> rawPath = planner.planRRT(startLat, startLon, startAlt,
                    goalLat, goalLon, goalAlt,
                    Collections.emptyList(), BASE_LAT, BASE_LON, 200.0);
            assertThat(rawPath).isNotEmpty();

            List<double[]> smoothed = planner.smoothPath(rawPath);
            // 平滑后起点接近原起点
            assertThat(smoothed.get(0)[0]).isCloseTo(startLat, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(smoothed.get(0)[1]).isCloseTo(startLon, org.assertj.core.data.Offset.offset(1e-6));
            // 平滑后终点接近原终点
            assertThat(smoothed.get(smoothed.size() - 1)[0]).isCloseTo(goalLat, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(smoothed.get(smoothed.size() - 1)[1]).isCloseTo(goalLon, org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    // ======================================================================
    // ObstacleAvoidanceStrategy.avoidWithPath 测试
    // ======================================================================

    @Nested
    @DisplayName("avoidWithPath 基于路径规划的避障")
    class AvoidWithPathTest {

        @Test
        @DisplayName("无障碍物时返回有效直线路径")
        void noObstacleReturnsValidPath() {
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 50.0;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            double goalAlt = 60.0;

            DecisionResult result = strategy.avoidWithPath(curLat, curLon, curAlt, 0.0,
                    Collections.emptyList(), goalLat, goalLon, goalAlt);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("AVOID");
            assertThat(result.path).isNotNull().isNotEmpty();
            // 起点接近当前位置
            assertThat(result.path.get(0)[0]).isCloseTo(curLat, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(result.path.get(0)[1]).isCloseTo(curLon, org.assertj.core.data.Offset.offset(1e-9));
            // 终点接近目标
            int last = result.path.size() - 1;
            assertThat(result.path.get(last)[0]).isCloseTo(goalLat, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(result.path.get(last)[1]).isCloseTo(goalLon, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("有障碍物时返回绕行路径且不与障碍物相交")
        void withObstacleReturnsAvoidingPath() {
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 50.0;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            double goalAlt = 50.0;
            PathPlanner.Obstacle obs = new PathPlanner.Obstacle(
                    GeoUtil.latOf(BASE_LAT, BASE_LON, 50.0, 0.0),
                    GeoUtil.lonOf(BASE_LAT, BASE_LON, 50.0, 0.0),
                    200.0, 15.0);
            List<PathPlanner.Obstacle> obstacles = Collections.singletonList(obs);

            DecisionResult result = strategy.avoidWithPath(curLat, curLon, curAlt, 0.0,
                    obstacles, goalLat, goalLon, goalAlt);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("AVOID");
            assertThat(result.path).isNotNull().isNotEmpty();
            // 路径不与障碍物相交
            assertThat(planner.pathCollides(result.path, obstacles))
                    .as("避障路径不应穿过障碍物").isFalse();
        }

        @Test
        @DisplayName("终点在障碍物内时仍返回决策（path 可能为空）")
        void goalInsideObstacleReturnsDecision() {
            double curLat = BASE_LAT, curLon = BASE_LON, curAlt = 50.0;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 80.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 80.0, 0.0);
            double goalAlt = 50.0;
            // 终点被障碍物包围
            PathPlanner.Obstacle obs = new PathPlanner.Obstacle(goalLat, goalLon, 200.0, 25.0);

            DecisionResult result = strategy.avoidWithPath(curLat, curLon, curAlt, 0.0,
                    Collections.singletonList(obs), goalLat, goalLon, goalAlt);

            assertThat(result).isNotNull();
            assertThat(result.decisionType).isEqualTo("AVOID");
            // A* 会失败（终点被堵），RRT 也可能失败 → path 为空，置信度降低
            assertThat(result.path).isNotNull();
            assertThat(result.confidence).isLessThanOrEqualTo(0.8);
        }
    }

    // ======================================================================
    // 碰撞检测工具方法测试
    // ======================================================================

    @Nested
    @DisplayName("pathCollides 碰撞检测")
    class PathCollidesTest {

        @Test
        @DisplayName("穿过障碍物的路径被检测为碰撞")
        void collidingPathDetected() {
            double startLat = BASE_LAT, startLon = BASE_LON;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            // 直线路径穿过中间的障碍物
            List<double[]> path = Arrays.asList(
                    new double[]{startLat, startLon, 50.0},
                    new double[]{goalLat, goalLon, 50.0}
            );
            PathPlanner.Obstacle obs = new PathPlanner.Obstacle(
                    GeoUtil.latOf(BASE_LAT, BASE_LON, 50.0, 0.0),
                    GeoUtil.lonOf(BASE_LAT, BASE_LON, 50.0, 0.0),
                    200.0, 15.0);

            assertThat(planner.pathCollides(path, Collections.singletonList(obs)))
                    .as("直线路径穿过障碍物应被检测为碰撞").isTrue();
        }

        @Test
        @DisplayName("不穿过障碍物的路径被检测为无碰撞")
        void nonCollidingPathDetected() {
            double startLat = BASE_LAT, startLon = BASE_LON;
            double goalLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            double goalLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 100.0, 0.0);
            // 路径绕到东侧 40m 处，不穿过中间障碍物
            double midLat = GeoUtil.latOf(BASE_LAT, BASE_LON, 50.0, 40.0);
            double midLon = GeoUtil.lonOf(BASE_LAT, BASE_LON, 50.0, 40.0);
            List<double[]> path = Arrays.asList(
                    new double[]{startLat, startLon, 50.0},
                    new double[]{midLat, midLon, 50.0},
                    new double[]{goalLat, goalLon, 50.0}
            );
            PathPlanner.Obstacle obs = new PathPlanner.Obstacle(
                    GeoUtil.latOf(BASE_LAT, BASE_LON, 50.0, 0.0),
                    GeoUtil.lonOf(BASE_LAT, BASE_LON, 50.0, 0.0),
                    200.0, 15.0);

            assertThat(planner.pathCollides(path, Collections.singletonList(obs)))
                    .as("绕行路径不应被检测为碰撞").isFalse();
        }

        @Test
        @DisplayName("空路径或无障碍物时返回 false")
        void emptyPathOrNoObstacleReturnsFalse() {
            assertThat(planner.pathCollides(Collections.emptyList(),
                    Collections.singletonList(new PathPlanner.Obstacle(BASE_LAT, BASE_LON, 100, 10))))
                    .isFalse();
            assertThat(planner.pathCollides(
                    Arrays.asList(new double[]{BASE_LAT, BASE_LON, 0}, new double[]{BASE_LAT, BASE_LON, 0}),
                    Collections.emptyList()))
                    .isFalse();
        }
    }
}
