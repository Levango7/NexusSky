package io.aerofleet.cloud.scenario;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScenarioLauncherService} 场景启动器单测（P0-2）。
 * <p>
 * 测试：
 * <ul>
 *   <li>场景启动（有足够无人机）</li>
 *   <li>场景启动（无人机不足）</li>
 *   <li>场景启动（无可用无人机）</li>
 *   <li>协同策略分配（RECON_ONLY / RECON_RELAY / RECON_RELAY_EXEC）</li>
 *   <li>航点生成</li>
 * </ul>
 */
@DisplayName("ScenarioLauncherService 场景启动器 (P0-2)")
class ScenarioLauncherServiceTest {

    private DeviceRegistry registry;
    private ScenarioLauncherService service;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        service = new ScenarioLauncherService(registry);
    }

    /** 注册 N 架在线无人机。 */
    private void registerOnlineDrones(int n) {
        for (int i = 1; i <= n; i++) {
            DroneSnapshot s = registry.registerIfAbsent(i);
            s.online = true;
            s.lastHeartbeatMs = System.currentTimeMillis();
        }
    }

    @Test
    @DisplayName("有足够无人机时启动成功（SUCCESS）")
    void launchSuccessWithEnoughDrones() {
        registerOnlineDrones(5);
        ScenarioTemplate template = ScenarioPresetFactory.byId("preset-FIRE-SMALL");

        LaunchResult result = service.launch(template, 39.9, 116.3);

        assertThat(result.getStatus()).isEqualTo(LaunchResult.Status.SUCCESS);
        assertThat(result.getAssignedDrones()).hasSize(2);
        assertThat(result.getPlanId()).isGreaterThan(0);
        assertThat(result.getLaunchId()).startsWith("launch-");
        assertThat(result.getEstimatedCoveragePct()).isGreaterThan(0);
        assertThat(result.getMessage()).contains("success");
    }

    @Test
    @DisplayName("无人机不足时部分启动（PARTIAL）")
    void launchPartialWithInsufficientDrones() {
        registerOnlineDrones(1);
        ScenarioTemplate template = ScenarioPresetFactory.byId("preset-FIRE-LARGE");

        LaunchResult result = service.launch(template, 39.9, 116.3);

        assertThat(result.getStatus()).isEqualTo(LaunchResult.Status.PARTIAL);
        assertThat(result.getAssignedDrones()).hasSize(1);
        assertThat(result.getMessage()).contains("partial");
        assertThat(result.getEstimatedCoveragePct()).isGreaterThan(0);
    }

    @Test
    @DisplayName("无可用无人机时启动失败（FAILED）")
    void launchFailedWithNoDrones() {
        ScenarioTemplate template = ScenarioPresetFactory.byId("preset-FIRE-SMALL");

        LaunchResult result = service.launch(template, 39.9, 116.3);

        assertThat(result.getStatus()).isEqualTo(LaunchResult.Status.FAILED);
        assertThat(result.getAssignedDrones()).isEmpty();
        assertThat(result.getEstimatedCoveragePct()).isEqualTo(0.0);
        assertThat(result.getMessage()).contains("no available drones");
    }

    @Test
    @DisplayName("离线无人机不被选取")
    void offlineDronesNotSelected() {
        DroneSnapshot online = registry.registerIfAbsent(1);
        online.online = true;
        online.lastHeartbeatMs = System.currentTimeMillis();
        DroneSnapshot offline = registry.registerIfAbsent(2);
        offline.online = false;

        ScenarioTemplate template = ScenarioPresetFactory.byId("preset-FIRE-SMALL");
        LaunchResult result = service.launch(template, 39.9, 116.3);

        assertThat(result.getAssignedDrones()).containsExactly(1);
        assertThat(result.getAssignedDrones()).doesNotContain(2);
    }

    @Test
    @DisplayName("协同策略 RECON_ONLY：全部无人机分配侦察角色")
    void assignRolesReconOnly() {
        ScenarioTemplate template = ScenarioPresetFactory.byId("preset-FIRE-SMALL");
        List<String> roles = service.assignRoles(template, 2);

        assertThat(roles).hasSize(2);
        assertThat(roles).allMatch("RECON"::equals);
    }

    @Test
    @DisplayName("协同策略 RECON_RELAY：侦察 + 中继角色")
    void assignRolesReconRelay() {
        ScenarioTemplate template = ScenarioPresetFactory.byId("preset-MUDSLIDE-MEDIUM");
        List<String> roles = service.assignRoles(template, 6);

        assertThat(roles).hasSize(6);
        assertThat(roles).contains("RECON");
        assertThat(roles).contains("RELAY");
        long reconCount = roles.stream().filter("RECON"::equals).count();
        long relayCount = roles.stream().filter("RELAY"::equals).count();
        assertThat(reconCount + relayCount).isEqualTo(6);
    }

    @Test
    @DisplayName("协同策略 RECON_RELAY_EXEC：侦察 + 中继 + 执行角色")
    void assignRolesReconRelayExec() {
        ScenarioTemplate template = ScenarioPresetFactory.byId("preset-FIRE-LARGE");
        List<String> roles = service.assignRoles(template, 6);

        assertThat(roles).hasSize(6);
        assertThat(roles).contains("RECON");
        assertThat(roles).contains("RELAY");
        assertThat(roles).contains("EXEC");
    }

    @Test
    @DisplayName("RECON_RELAY_EXEC 至少分配 1 个 EXEC 角色")
    void assignRolesReconRelayExecHasExec() {
        ScenarioTemplate template = ScenarioPresetFactory.byId("preset-EARTHQUAKE-LARGE");
        List<String> roles = service.assignRoles(template, 12);

        long execCount = roles.stream().filter("EXEC"::equals).count();
        assertThat(execCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("航点生成：围绕中心点圆形分布")
    void generateWaypointsCircularDistribution() {
        List<ScenarioTemplate.Wp> wps = service.generateWaypoints(39.9, 116.3, 2.0, 4);

        assertThat(wps).hasSize(4);
        // 每个航点距中心点约 2km
        for (ScenarioTemplate.Wp wp : wps) {
            double distKm = haversineKm(39.9, 116.3, wp.lat, wp.lon);
            assertThat(distKm).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
        }
    }

    @Test
    @DisplayName("航点生成：0 架无人机返回空列表")
    void generateWaypointsEmptyForZeroDrones() {
        List<ScenarioTemplate.Wp> wps = service.generateWaypoints(39.9, 116.3, 2.0, 0);
        assertThat(wps).isEmpty();
    }

    @Test
    @DisplayName("启动后可通过 launchId 查询状态")
    void launchStatusQueryable() {
        registerOnlineDrones(3);
        ScenarioTemplate template = ScenarioPresetFactory.byId("preset-FLOOD-SMALL");

        LaunchResult result = service.launch(template, 39.9, 116.3);
        ScenarioLauncherService.LaunchRecord record = service.getStatus(result.getLaunchId());

        assertThat(record).isNotNull();
        assertThat(record.launchId).isEqualTo(result.getLaunchId());
        assertThat(record.status).isEqualTo(ScenarioLauncherService.LaunchStatus.RUNNING);
        assertThat(record.templateId).isEqualTo(template.getId());
    }

    @Test
    @DisplayName("中止进行中的场景")
    void abortRunningLaunch() {
        registerOnlineDrones(3);
        ScenarioTemplate template = ScenarioPresetFactory.byId("preset-FLOOD-SMALL");

        LaunchResult result = service.launch(template, 39.9, 116.3);
        boolean aborted = service.abort(result.getLaunchId());

        assertThat(aborted).isTrue();
        ScenarioLauncherService.LaunchRecord record = service.getStatus(result.getLaunchId());
        assertThat(record.status).isEqualTo(ScenarioLauncherService.LaunchStatus.ABORTED);
    }

    @Test
    @DisplayName("中止不存在的 launchId 返回 false")
    void abortNonExistentReturnsFalse() {
        assertThat(service.abort("non-existent")).isFalse();
    }

    @Test
    @DisplayName("查询进行中的场景")
    void getActiveLaunches() {
        registerOnlineDrones(10);
        ScenarioTemplate t1 = ScenarioPresetFactory.byId("preset-FIRE-SMALL");
        ScenarioTemplate t2 = ScenarioPresetFactory.byId("preset-FLOOD-SMALL");

        service.launch(t1, 39.9, 116.3);
        service.launch(t2, 40.0, 117.0);

        List<ScenarioLauncherService.LaunchRecord> active = service.getActiveLaunches();
        assertThat(active).hasSize(2);
    }

    @Test
    @DisplayName("查询历史启动记录")
    void getHistory() {
        registerOnlineDrones(10);
        ScenarioTemplate t1 = ScenarioPresetFactory.byId("preset-FIRE-SMALL");
        ScenarioTemplate t2 = ScenarioPresetFactory.byId("preset-FLOOD-SMALL");

        service.launch(t1, 39.9, 116.3);
        service.launch(t2, 40.0, 117.0);

        List<ScenarioLauncherService.LaunchRecord> history = service.getHistory();
        assertThat(history).hasSize(2);
    }

    @Test
    @DisplayName("预估覆盖率：全部无人机可用时接近 80%")
    void estimateCoverageFull() {
        double coverage = service.estimateCoverage(6, 6, 3.0);
        assertThat(coverage).isCloseTo(80.0, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    @DisplayName("预估覆盖率：半数无人机可用时约 40%")
    void estimateCoverageHalf() {
        double coverage = service.estimateCoverage(3, 6, 3.0);
        assertThat(coverage).isCloseTo(40.0, org.assertj.core.data.Offset.offset(0.1));
    }

    /** Haversine 距离公式（km）。 */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}