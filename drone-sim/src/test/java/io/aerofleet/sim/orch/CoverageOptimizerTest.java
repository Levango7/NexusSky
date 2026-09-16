package io.aerofleet.sim.orch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * CoverageOptimizer 覆盖优化算法单测（M9 应急任务编排，T3 覆盖优化算法）。
 * <p>
 * 覆盖空输入、单架/多架部署、局部优化、连通性修复、Haversine 距离、覆盖半径、场景类型选择。
 */
@DisplayName("CoverageOptimizer 覆盖优化算法 (T3)")
class CoverageOptimizerTest {

    /** 北京纬度。 */
    private static final double BEIJING_LAT = 39.9042;
    /** 北京经度。 */
    private static final double BEIJING_LON = 116.4074;
    /** 上海纬度。 */
    private static final double SHANGHAI_LAT = 31.2304;
    /** 上海经度。 */
    private static final double SHANGHAI_LON = 121.4737;

    /** 构造基站类型集合。 */
    private static Set<Integer> cellTypes(int... types) {
        Set<Integer> set = new HashSet<>();
        for (int t : types) {
            set.add(t);
        }
        return set;
    }

    /** 构造无人机信息（位置 0,0）。 */
    private static DroneInfo drone(int id, int battery, int... cellTypes) {
        return new DroneInfo(id, battery, 0.0, 0.0, cellTypes(cellTypes));
    }

    @Test
    @DisplayName("空无人机列表 → 空部署方案，覆盖率 0")
    void emptyDronesReturnsEmptyPlan() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        DeploymentPlan plan = optimizer.optimize(0.0, 0.0, 1000.0,
                Collections.emptyList(), 0);

        assertThat(plan.getDeployments()).isEmpty();
        assertThat(plan.getCoverageRate()).isZero();
        assertThat(plan.getConnectRate()).isZero();
        assertThat(plan.getExpectedServiceMs()).isZero();
    }

    @Test
    @DisplayName("null 无人机列表 → 空部署方案")
    void nullDronesReturnsEmptyPlan() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        DeploymentPlan plan = optimizer.optimize(0.0, 0.0, 1000.0, null, 0);

        assertThat(plan.getDeployments()).isEmpty();
        assertThat(plan.getCoverageRate()).isZero();
    }

    @Test
    @DisplayName("单架无人机 → 部署在灾区中心，覆盖率 > 0")
    void singleDroneDeploysAtCenter() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        // 灾区中心 (0,0)，半径 1000m，一架 LTE 无人机（覆盖 2000m）
        DeploymentPlan plan = optimizer.optimize(0.0, 0.0, 1000.0,
                Collections.singletonList(drone(1, 80, 1)), 0);

        assertThat(plan.getDeployments()).hasSize(1);
        assertThat(plan.getCoverageRate()).isGreaterThan(0.0);

        DroneDeployment d = plan.getDeployment(1);
        assertThat(d).isNotNull();
        // 部署在灾区中心附近（网格步长 500m，中心 (0,0) 是候选点）
        assertThat(d.targetLat).isCloseTo(0.0, within(0.01));
        assertThat(d.targetLon).isCloseTo(0.0, within(0.01));
        // LTE 基站
        assertThat(d.cellType).isEqualTo(1);
    }

    @Test
    @DisplayName("多架无人机 → 贪心部署位置不重叠")
    void multipleDronesDeployAtDistinctPositions() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        // 灾区中心 (0,0)，半径 5000m，两架 LTE 无人机
        List<DroneInfo> drones = Arrays.asList(
                drone(1, 90, 1),
                drone(2, 80, 1));
        DeploymentPlan plan = optimizer.optimize(0.0, 0.0, 5000.0, drones, 0);

        assertThat(plan.getDeployments()).hasSize(2);

        DroneDeployment d1 = plan.getDeployment(1);
        DroneDeployment d2 = plan.getDeployment(2);
        assertThat(d1).isNotNull();
        assertThat(d2).isNotNull();

        // 两架无人机位置不同
        double dist = optimizer.haversineM(d1.targetLat, d1.targetLon, d2.targetLat, d2.targetLon);
        assertThat(dist).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("局部优化后覆盖率 ≥ 优化前")
    void localOptimizeDoesNotDecreaseCoverage() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        // 灾区中心 (0,0)，半径 3000m，两架 LTE 无人机
        List<DroneInfo> drones = Arrays.asList(
                drone(1, 90, 1),
                drone(2, 80, 1));

        // 贪心部署
        List<DroneDeployment> greedy = optimizer.greedyDeploy(0.0, 0.0, 3000.0, drones);
        double initialCoverage = optimizer.calculateCoverage(greedy, 0.0, 0.0, 3000.0);

        // 局部优化
        List<DroneDeployment> optimized = optimizer.localOptimize(greedy, 0.0, 0.0, 3000.0);
        double optimizedCoverage = optimizer.calculateCoverage(optimized, 0.0, 0.0, 3000.0);

        // 优化后覆盖率 ≥ 优化前（允许浮点误差）
        assertThat(optimizedCoverage).isGreaterThanOrEqualTo(initialCoverage - 0.01);
    }

    @Test
    @DisplayName("连通性修复：两架无人机距离 > meshRange → 分配 HAPS 中继")
    void fixConnectivityAssignsHapsForIsolatedDrones() {
        // 灾区半径 15000m，两架 LoRa 无人机（覆盖 5000m），meshRange 2000m
        // 贪心部署后两架距离约 5000m > meshRange*1.5=3000m → HAPS
        CoverageOptimizer optimizer = new CoverageOptimizer();
        List<DroneInfo> drones = Arrays.asList(
                drone(1, 90, 3),
                drone(2, 80, 3));
        DeploymentPlan plan = optimizer.optimize(0.0, 0.0, 15000.0, drones, 0);

        assertThat(plan.getDeployments()).hasSize(2);

        // 至少一架无人机被分配 HAPS 中继 (relayRole=2)
        boolean hasHaps = false;
        for (DroneDeployment d : plan.getDeployments()) {
            if (d.relayRole == 2) {
                hasHaps = true;
                break;
            }
        }
        assertThat(hasHaps).as("距离 > meshRange 的孤立无人机应分配 HAPS 中继").isTrue();
    }

    @Test
    @DisplayName("Haversine 距离计算正确（北京-上海约 1067km）")
    void haversineBeijingToShanghai() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        double dist = optimizer.haversineM(BEIJING_LAT, BEIJING_LON, SHANGHAI_LAT, SHANGHAI_LON);

        // 北京-上海球面距离约 1067km，容差 10km
        assertThat(dist).isCloseTo(1_067_000.0, within(10_000.0));
    }

    @Test
    @DisplayName("Haversine 相同点距离为 0")
    void haversineSamePointIsZero() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        double dist = optimizer.haversineM(30.0, 120.0, 30.0, 120.0);
        assertThat(dist).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("覆盖半径估算正确：LTE/WiFi/LoRa @ 20dBm")
    void coverageRadiusAt20dBm() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        assertThat(optimizer.coverageRadiusM(1, 20)).isCloseTo(2000.0, within(1e-9));  // LTE
        assertThat(optimizer.coverageRadiusM(2, 20)).isCloseTo(500.0, within(1e-9));   // WiFi
        assertThat(optimizer.coverageRadiusM(3, 20)).isCloseTo(5000.0, within(1e-9));  // LoRa
    }

    @Test
    @DisplayName("覆盖半径线性缩放：LTE @ 10dBm = 1000m, WiFi @ 40dBm = 1000m, LoRa @ 40dBm = 10000m")
    void coverageRadiusLinearScaling() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        assertThat(optimizer.coverageRadiusM(1, 10)).isCloseTo(1000.0, within(1e-9));
        assertThat(optimizer.coverageRadiusM(2, 40)).isCloseTo(1000.0, within(1e-9));
        assertThat(optimizer.coverageRadiusM(3, 40)).isCloseTo(10000.0, within(1e-9));
    }

    @Test
    @DisplayName("覆盖半径：cellType=0 返回 0")
    void coverageRadiusNoCellIsZero() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        assertThat(optimizer.coverageRadiusM(0, 20)).isZero();
    }

    @Test
    @DisplayName("场景类型影响基站类型选择：地震→LTE, 泥石流→LoRa, 火灾→WiFi")
    void scenarioTypeAffectsCellType() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        // 无人机支持所有基站类型
        DroneInfo allCapable = drone(1, 90, 1, 2, 3);

        // 地震 (scenarioType=0) → LTE (1)
        DeploymentPlan earthquake = optimizer.optimize(0.0, 0.0, 2000.0,
                Collections.singletonList(allCapable), 0);
        assertThat(earthquake.getDeployment(1).cellType).isEqualTo(1);

        // 泥石流 (scenarioType=1) → LoRa (3)
        DeploymentPlan mudslide = optimizer.optimize(0.0, 0.0, 2000.0,
                Collections.singletonList(allCapable), 1);
        assertThat(mudslide.getDeployment(1).cellType).isEqualTo(3);

        // 火灾 (scenarioType=2) → WiFi (2)
        DeploymentPlan fire = optimizer.optimize(0.0, 0.0, 2000.0,
                Collections.singletonList(allCapable), 2);
        assertThat(fire.getDeployment(1).cellType).isEqualTo(2);
    }

    @Test
    @DisplayName("DeploymentPlan.getDeployment 按 droneId 查找")
    void deploymentPlanGetDeploymentById() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        List<DroneInfo> drones = Arrays.asList(
                drone(10, 90, 1),
                drone(20, 80, 1));
        DeploymentPlan plan = optimizer.optimize(0.0, 0.0, 5000.0, drones, 0);

        assertThat(plan.getDeployment(10)).isNotNull();
        assertThat(plan.getDeployment(20)).isNotNull();
        assertThat(plan.getDeployment(99)).isNull();
        assertThat(plan.getDeployment(10).droneId).isEqualTo(10);
    }

    @Test
    @DisplayName("DroneInfo 不可变：supportedCellTypes 不可修改")
    void droneInfoSupportedCellTypesUnmodifiable() {
        DroneInfo info = new DroneInfo(1, 80, 0.0, 0.0, cellTypes(1, 2));
        Set<Integer> types = info.getSupportedCellTypes();
        org.assertj.core.api.Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> types.add(3));
    }

    @Test
    @DisplayName("DeploymentPlan 不可变：deployments 不可修改")
    void deploymentPlanDeploymentsUnmodifiable() {
        CoverageOptimizer optimizer = new CoverageOptimizer();
        DeploymentPlan plan = optimizer.optimize(0.0, 0.0, 1000.0,
                Collections.singletonList(drone(1, 80, 1)), 0);
        List<DroneDeployment> deployments = plan.getDeployments();
        org.assertj.core.api.Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> deployments.add(null));
    }
}
