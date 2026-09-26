package io.aerofleet.sim.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("AI 策略实现测试")
class StrategyTest {

    private static final double BASE_LAT = 30.0;
    private static final double BASE_LON = 120.0;
    private static final double EPS = 1e-6;

    @Nested
    @DisplayName("EmergencyReturnStrategy 应急返航策略")
    class EmergencyReturnTest {

        private EmergencyReturnStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new EmergencyReturnStrategy();
            strategy.currentLat = BASE_LAT;
            strategy.currentLon = BASE_LON;
            strategy.currentAlt = 80.0;
            strategy.currentHeading = 0.0;
            strategy.homeLat = BASE_LAT + 0.01;
            strategy.homeLon = BASE_LON + 0.01;
            strategy.homeAlt = 10.0;
            strategy.batteryPct = 80.0;
            strategy.linkLost = false;
            strategy.gpsLost = false;
            strategy.weatherCode = 0;
            strategy.windSpeed = 3.0;
            strategy.safeLandingSites = Collections.emptyList();
            strategy.terrainObstacles = Collections.emptyList();
        }

        @Test
        @DisplayName("电量低于25%触发应急返航")
        void lowBatteryTriggersReturn() {
            strategy.batteryPct = 20.0;
            assertThat(strategy.shouldTrigger()).isTrue();

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.triggerReason).isEqualTo("low battery");
            assertThat(result.confidence).isGreaterThanOrEqualTo(0.9);
            assertThat(result.waypoints).isNotEmpty();
            assertThat(result.waypoints.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("电量低于10%置信度更高")
        void criticalBatteryHigherConfidence() {
            strategy.batteryPct = 8.0;
            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.confidence).isGreaterThanOrEqualTo(0.95);
        }

        @Test
        @DisplayName("链路丢失超过30秒触发应急返航")
        void linkLossTriggersReturn() {
            strategy.batteryPct = 80.0;
            strategy.linkLost = true;
            strategy.linkLostSinceMs = System.currentTimeMillis() - 35_000L;
            assertThat(strategy.shouldTrigger()).isTrue();

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.triggerReason).isEqualTo("link lost");
        }

        @Test
        @DisplayName("链路丢失未超30秒不触发")
        void linkLossUnderThresholdNoTrigger() {
            strategy.batteryPct = 80.0;
            strategy.linkLost = true;
            strategy.linkLostSinceMs = System.currentTimeMillis() - 10_000L;
            assertThat(strategy.shouldTrigger()).isFalse();
        }

        @Test
        @DisplayName("GPS丢失触发紧急降落")
        void gpsLossTriggersEmergencyLand() {
            strategy.batteryPct = 80.0;
            strategy.gpsLost = true;
            assertThat(strategy.shouldTrigger()).isTrue();

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.triggerReason).isEqualTo("GPS lost");

            DecisionResult dr = strategy.evaluateAsDecisionResult();
            assertThat(dr).isNotNull();
            assertThat(dr.decisionType).isEqualTo("EMERGENCY_LAND");
        }

        @Test
        @DisplayName("极端天气触发应急返航")
        void extremeWeatherTriggersReturn() {
            strategy.batteryPct = 80.0;
            strategy.weatherCode = 3;
            assertThat(strategy.shouldTrigger()).isTrue();

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.triggerReason).isEqualTo("extreme weather");
        }

        @Test
        @DisplayName("极端风速触发应急返航")
        void extremeWindTriggersReturn() {
            strategy.batteryPct = 80.0;
            strategy.windSpeed = 18.0;
            assertThat(strategy.shouldTrigger()).isTrue();

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.triggerReason).isEqualTo("extreme wind");
        }

        @Test
        @DisplayName("健康状态不触发")
        void healthyNoTrigger() {
            strategy.batteryPct = 80.0;
            assertThat(strategy.shouldTrigger()).isFalse();
            assertThat(strategy.evaluate()).isNull();
        }

        @Test
        @DisplayName("选择最近安全着陆点")
        void selectsNearestLandingSite() {
            strategy.batteryPct = 20.0;
            strategy.homeLat = BASE_LAT + 0.1;
            strategy.homeLon = BASE_LON + 0.1;

            double nearLat = BASE_LAT + 0.005;
            double nearLon = BASE_LON + 0.005;
            strategy.safeLandingSites = new ArrayList<>();
            strategy.safeLandingSites.add(new double[]{BASE_LAT + 0.05, BASE_LON + 0.05, 5.0});
            strategy.safeLandingSites.add(new double[]{nearLat, nearLon, 5.0});

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.targetLat).isCloseTo(nearLat, within(1e-9));
            assertThat(result.targetLon).isCloseTo(nearLon, within(1e-9));
        }

        @Test
        @DisplayName("无安全着陆点时返回Home")
        void noLandingSitesReturnsHome() {
            strategy.batteryPct = 20.0;
            strategy.safeLandingSites = Collections.emptyList();

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.targetLat).isCloseTo(strategy.homeLat, within(1e-9));
            assertThat(result.targetLon).isCloseTo(strategy.homeLon, within(1e-9));
        }

        @Test
        @DisplayName("GPS丢失时在当前位置紧急降落")
        void gpsLossLandsAtCurrentPosition() {
            strategy.batteryPct = 80.0;
            strategy.gpsLost = true;

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.targetLat).isCloseTo(strategy.currentLat, within(1e-9));
            assertThat(result.targetLon).isCloseTo(strategy.currentLon, within(1e-9));
        }

        @Test
        @DisplayName("路径包含起点和终点")
        void pathContainsStartAndEnd() {
            strategy.batteryPct = 20.0;

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            List<double[]> path = result.waypoints;
            assertThat(path.size()).isGreaterThanOrEqualTo(2);

            double[] start = path.get(0);
            assertThat(start[0]).isCloseTo(strategy.currentLat, within(1e-9));
            assertThat(start[1]).isCloseTo(strategy.currentLon, within(1e-9));

            double[] end = path.get(path.size() - 1);
            assertThat(end[0]).isCloseTo(result.targetLat, within(1e-9));
            assertThat(end[1]).isCloseTo(result.targetLon, within(1e-9));
        }

        @Test
        @DisplayName("低高度时路径包含爬升段")
        void lowAltitudeIncludesClimbSegment() {
            strategy.batteryPct = 20.0;
            strategy.currentAlt = 10.0;

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.waypoints.size()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("evaluateAsDecisionResult返回RTL类型")
        void evaluateAsDecisionResultReturnsRTL() {
            strategy.batteryPct = 20.0;
            DecisionResult dr = strategy.evaluateAsDecisionResult();
            assertThat(dr).isNotNull();
            assertThat(dr.decisionType).isEqualTo("RTL");
            assertThat(dr.reason).isEqualTo("low battery");
        }

        @Test
        @DisplayName("ETA为正值")
        void etaIsPositive() {
            strategy.batteryPct = 20.0;
            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.estimatedTimeSec).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("可达性判断基于电量")
        void reachabilityBasedOnBattery() {
            strategy.batteryPct = 1.0;
            strategy.homeLat = BASE_LAT + 1.0;
            strategy.homeLon = BASE_LON + 1.0;

            EmergencyReturnStrategy.EmergencyReturnResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.reachable).isFalse();
        }
    }

    @Nested
    @DisplayName("AutoAvoidanceStrategy 自动避障策略")
    class AutoAvoidanceTest {

        private AutoAvoidanceStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new AutoAvoidanceStrategy();
            strategy.currentLat = BASE_LAT;
            strategy.currentLon = BASE_LON;
            strategy.currentAlt = 50.0;
            strategy.currentHeading = 0.0;
            strategy.currentSpeed = 15.0;
            strategy.currentVerticalSpeed = 0.0;
            strategy.obstacleDetected = false;
            strategy.obstacleDistance = Double.MAX_VALUE;
            strategy.obstacleBearing = 0.0;
            strategy.obstacleSpeed = 0.0;
            strategy.nearbyDrones = Collections.emptyList();
        }

        @Test
        @DisplayName("检测到近距离障碍物触发避障")
        void obstacleTriggersAvoidance() {
            strategy.obstacleDetected = true;
            strategy.obstacleDistance = 30.0;
            strategy.obstacleBearing = 0.0;
            assertThat(strategy.shouldTrigger()).isTrue();

            AutoAvoidanceStrategy.AvoidanceResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.avoidanceType).isNotNull();
        }

        @Test
        @DisplayName("远距离障碍物不触发避障")
        void distantObstacleNoTrigger() {
            strategy.obstacleDetected = true;
            strategy.obstacleDistance = 100.0;
            assertThat(strategy.shouldTrigger()).isFalse();
        }

        @Test
        @DisplayName("安全距离内有其他无人机触发避障")
        void nearbyDroneTriggersAvoidance() {
            strategy.nearbyDrones = new ArrayList<>();
            strategy.nearbyDrones.add(new AutoAvoidanceStrategy.OtherDrone(
                    2, BASE_LAT + 0.0001, BASE_LON, 50.0, 90.0, 10.0));
            assertThat(strategy.shouldTrigger()).isTrue();

            AutoAvoidanceStrategy.AvoidanceResult result = strategy.evaluate();
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("远距离无人机不触发避障")
        void distantDroneNoTrigger() {
            strategy.nearbyDrones = new ArrayList<>();
            strategy.nearbyDrones.add(new AutoAvoidanceStrategy.OtherDrone(
                    2, BASE_LAT + 0.01, BASE_LON, 50.0, 90.0, 10.0));
            assertThat(strategy.shouldTrigger()).isFalse();
        }

        @Test
        @DisplayName("无障碍物不触发")
        void noObstacleNoTrigger() {
            assertThat(strategy.shouldTrigger()).isFalse();
            assertThat(strategy.evaluate()).isNull();
        }

        @Test
        @DisplayName("避障结果包含修正航向")
        void avoidanceResultHasCorrectedHeading() {
            strategy.obstacleDetected = true;
            strategy.obstacleDistance = 20.0;
            strategy.obstacleBearing = 0.0;

            AutoAvoidanceStrategy.AvoidanceResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.correctedHeading).isGreaterThanOrEqualTo(0.0);
            assertThat(result.correctedHeading).isLessThan(360.0);
        }

        @Test
        @DisplayName("避障结果包含修正速度")
        void avoidanceResultHasCorrectedSpeed() {
            strategy.obstacleDetected = true;
            strategy.obstacleDistance = 20.0;
            strategy.obstacleBearing = 0.0;

            AutoAvoidanceStrategy.AvoidanceResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.correctedSpeed).isGreaterThan(0.0);
            assertThat(result.correctedSpeed).isLessThanOrEqualTo(20.0);
        }

        @Test
        @DisplayName("避障结果包含修正高度")
        void avoidanceResultHasCorrectedAlt() {
            strategy.obstacleDetected = true;
            strategy.obstacleDistance = 20.0;
            strategy.obstacleBearing = 0.0;

            AutoAvoidanceStrategy.AvoidanceResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.correctedAlt).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("避障路径包含起点")
        void avoidancePathContainsStart() {
            strategy.obstacleDetected = true;
            strategy.obstacleDistance = 20.0;
            strategy.obstacleBearing = 0.0;

            AutoAvoidanceStrategy.AvoidanceResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.avoidancePath).isNotEmpty();
            double[] start = result.avoidancePath.get(0);
            assertThat(start[0]).isCloseTo(strategy.currentLat, within(1e-9));
            assertThat(start[1]).isCloseTo(strategy.currentLon, within(1e-9));
        }

        @Test
        @DisplayName("正前方障碍物应转向")
        void frontObstacleCausesTurn() {
            strategy.obstacleDetected = true;
            strategy.obstacleDistance = 20.0;
            strategy.obstacleBearing = 0.0;
            strategy.currentHeading = 0.0;

            AutoAvoidanceStrategy.AvoidanceResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            double headingDiff = Math.abs(result.correctedHeading - strategy.currentHeading);
            if (headingDiff > 180) headingDiff = 360 - headingDiff;
            assertThat(headingDiff).isGreaterThan(5.0);
        }

        @Test
        @DisplayName("evaluateAsDecisionResult返回AVOID类型")
        void evaluateAsDecisionResultReturnsAVOID() {
            strategy.obstacleDetected = true;
            strategy.obstacleDistance = 20.0;
            strategy.obstacleBearing = 0.0;

            DecisionResult dr = strategy.evaluateAsDecisionResult();
            assertThat(dr).isNotNull();
            assertThat(dr.decisionType).isEqualTo("AVOID");
        }

        @Test
        @DisplayName("高度冲突时调整高度")
        void altitudeConflictAdjustsAltitude() {
            strategy.nearbyDrones = new ArrayList<>();
            strategy.nearbyDrones.add(new AutoAvoidanceStrategy.OtherDrone(
                    2, BASE_LAT + 0.0001, BASE_LON, 52.0, 0.0, 15.0));

            AutoAvoidanceStrategy.AvoidanceResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(Math.abs(result.correctedAlt - strategy.currentAlt)).isGreaterThan(1.0);
        }
    }

    @Nested
    @DisplayName("SwarmCoordinationStrategy 多机协同策略")
    class SwarmCoordinationTest {

        private SwarmCoordinationStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new SwarmCoordinationStrategy();
            strategy.selfSysid = 1;
            strategy.selfLat = BASE_LAT;
            strategy.selfLon = BASE_LON;
            strategy.selfAlt = 80.0;
            strategy.selfBattery = 80.0;
            strategy.selfCapability = 1.0;
            strategy.swarmMembers = Collections.emptyList();
            strategy.availableTasks = Collections.emptyList();
            strategy.formationType = "line";
            strategy.formationCenterLat = BASE_LAT;
            strategy.formationCenterLon = BASE_LON;
            strategy.formationCenterAlt = 80.0;
            strategy.formationHeading = 0.0;
        }

        @Test
        @DisplayName("有可用任务时触发协同")
        void availableTasksTriggerCoordination() {
            strategy.availableTasks = new ArrayList<>();
            strategy.availableTasks.add(new SwarmCoordinationStrategy.TaskInfo(
                    "task-1", BASE_LAT + 0.01, BASE_LON + 0.01, 50.0, 0.8, 0.5));
            assertThat(strategy.shouldTrigger()).isTrue();
        }

        @Test
        @DisplayName("无任务无冲突不触发")
        void noTasksNoConflictNoTrigger() {
            assertThat(strategy.shouldTrigger()).isFalse();
            assertThat(strategy.evaluate()).isNull();
        }

        @Test
        @DisplayName("无人机冲突触发协同")
        void conflictTriggersCoordination() {
            strategy.swarmMembers = new ArrayList<>();
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    2, BASE_LAT + 0.0001, BASE_LON, 80.0, 70.0, 1.0, 0.0, 10.0));
            assertThat(strategy.shouldTrigger()).isTrue();
        }

        @Test
        @DisplayName("任务分配：自己中标最适合的任务")
        void taskAllocationAssignsBestTask() {
            strategy.availableTasks = new ArrayList<>();
            strategy.availableTasks.add(new SwarmCoordinationStrategy.TaskInfo(
                    "task-A", BASE_LAT + 0.001, BASE_LON + 0.001, 50.0, 0.5, 0.5));
            strategy.availableTasks.add(new SwarmCoordinationStrategy.TaskInfo(
                    "task-B", BASE_LAT + 0.1, BASE_LON + 0.1, 50.0, 0.3, 0.5));

            SwarmCoordinationStrategy.SwarmResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.coordinationType).isEqualTo("task_allocation");
            assertThat(result.assignedTaskId).isNotNull();
            assertThat(result.bidValue).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("任务分配：近任务中标概率更高")
        void nearerTaskWinsHigherBid() {
            strategy.availableTasks = new ArrayList<>();
            strategy.availableTasks.add(new SwarmCoordinationStrategy.TaskInfo(
                    "near", BASE_LAT + 0.001, BASE_LON + 0.001, 50.0, 0.5, 0.5));
            strategy.availableTasks.add(new SwarmCoordinationStrategy.TaskInfo(
                    "far", BASE_LAT + 0.1, BASE_LON + 0.1, 50.0, 0.5, 0.5));

            SwarmCoordinationStrategy.SwarmResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.assignedTaskId).isEqualTo("near");
        }

        @Test
        @DisplayName("任务分配：能力不足降低竞标值")
        void insufficientCapabilityReducesBid() {
            strategy.selfCapability = 0.3;
            strategy.availableTasks = new ArrayList<>();
            strategy.availableTasks.add(new SwarmCoordinationStrategy.TaskInfo(
                    "hard-task", BASE_LAT + 0.001, BASE_LON + 0.001, 50.0, 0.8, 0.9));

            SwarmCoordinationStrategy.SwarmResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.bidValue).isLessThan(100.0);
        }

        @Test
        @DisplayName("任务分配：多机竞标，最优者中标")
        void multiDroneBidBestWins() {
            strategy.availableTasks = new ArrayList<>();
            strategy.availableTasks.add(new SwarmCoordinationStrategy.TaskInfo(
                    "task-1", BASE_LAT + 0.001, BASE_LON + 0.001, 50.0, 0.8, 0.5));

            strategy.swarmMembers = new ArrayList<>();
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    2, BASE_LAT + 0.01, BASE_LON, 80.0, 50.0, 0.5, 0.0, 10.0));
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    3, BASE_LAT + 0.002, BASE_LON, 80.0, 90.0, 1.0, 0.0, 10.0));

            SwarmCoordinationStrategy.SwarmResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.taskAssignments).isNotEmpty();
        }

        @Test
        @DisplayName("编队调整：生成编队指令")
        void formationAdjustmentGeneratesCommands() {
            strategy.swarmMembers = new ArrayList<>();
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    2, BASE_LAT + 0.01, BASE_LON, 80.0, 80.0, 1.0, 0.0, 10.0));

            strategy.selfLat = BASE_LAT + 0.05;
            strategy.selfLon = BASE_LON + 0.05;

            SwarmCoordinationStrategy.SwarmResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.formationCommands).isNotEmpty();
        }

        @Test
        @DisplayName("编队类型line：横向排列")
        void lineFormationHorizontalArrangement() {
            strategy.swarmMembers = new ArrayList<>();
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    2, BASE_LAT, BASE_LON, 80.0, 80.0, 1.0, 0.0, 10.0));

            strategy.selfLat = BASE_LAT + 0.05;
            strategy.selfLon = BASE_LON + 0.05;
            strategy.formationType = "line";

            SwarmCoordinationStrategy.SwarmResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.formationCommands.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("编队类型circle：圆形排列")
        void circleFormationCircularArrangement() {
            strategy.swarmMembers = new ArrayList<>();
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    2, BASE_LAT, BASE_LON, 80.0, 80.0, 1.0, 0.0, 10.0));
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    3, BASE_LAT, BASE_LON, 80.0, 80.0, 1.0, 0.0, 10.0));

            strategy.selfLat = BASE_LAT + 0.05;
            strategy.selfLon = BASE_LON + 0.05;
            strategy.formationType = "circle";

            SwarmCoordinationStrategy.SwarmResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.formationCommands.size()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("编队类型v：V字形排列")
        void vFormationVShapeArrangement() {
            strategy.swarmMembers = new ArrayList<>();
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    2, BASE_LAT, BASE_LON, 80.0, 80.0, 1.0, 0.0, 10.0));
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    3, BASE_LAT, BASE_LON, 80.0, 80.0, 1.0, 0.0, 10.0));

            strategy.selfLat = BASE_LAT + 0.05;
            strategy.selfLon = BASE_LON + 0.05;
            strategy.formationType = "v";

            SwarmCoordinationStrategy.SwarmResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.formationCommands.size()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("evaluateAsDecisionResult返回SWARM类型")
        void evaluateAsDecisionResultReturnsSWARM() {
            strategy.availableTasks = new ArrayList<>();
            strategy.availableTasks.add(new SwarmCoordinationStrategy.TaskInfo(
                    "task-1", BASE_LAT + 0.01, BASE_LON + 0.01, 50.0, 0.8, 0.5));

            DecisionResult dr = strategy.evaluateAsDecisionResult();
            assertThat(dr).isNotNull();
            assertThat(dr.decisionType).isEqualTo("SWARM");
        }

        @Test
        @DisplayName("冲突消解置信度高于编队调整")
        void conflictResolutionHigherConfidence() {
            strategy.swarmMembers = new ArrayList<>();
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    2, BASE_LAT + 0.0001, BASE_LON, 80.0, 70.0, 1.0, 0.0, 10.0));

            SwarmCoordinationStrategy.SwarmResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            assertThat(result.confidence).isGreaterThanOrEqualTo(0.9);
        }

        @Test
        @DisplayName("编队指令包含目标位置")
        void formationCommandsContainTargetPositions() {
            strategy.swarmMembers = new ArrayList<>();
            strategy.swarmMembers.add(new SwarmCoordinationStrategy.DroneInfo(
                    2, BASE_LAT + 0.05, BASE_LON, 80.0, 80.0, 1.0, 0.0, 10.0));

            strategy.selfLat = BASE_LAT + 0.05;
            strategy.selfLon = BASE_LON + 0.05;

            SwarmCoordinationStrategy.SwarmResult result = strategy.evaluate();
            assertThat(result).isNotNull();
            for (SwarmCoordinationStrategy.FormationCommand cmd : result.formationCommands) {
                assertThat(cmd.targetLat).isNotNaN();
                assertThat(cmd.targetLon).isNotNaN();
                assertThat(cmd.targetAlt).isGreaterThan(0.0);
            }
        }
    }
}