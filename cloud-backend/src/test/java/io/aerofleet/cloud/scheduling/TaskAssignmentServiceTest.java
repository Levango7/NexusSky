package io.aerofleet.cloud.scheduling;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TaskAssignmentService 任务分配服务单测（M10 集群智能调度）。
 * <p>
 * 直接实例化 Service，使用可变 DeviceRegistry 桩覆盖分配/查询/取消/重分配/评分逻辑。
 */
@DisplayName("TaskAssignmentService 任务分配服务 (M10)")
class TaskAssignmentServiceTest {

    private StubRegistry registry;
    private TaskAssignmentService service;

    @BeforeEach
    void setUp() {
        registry = new StubRegistry();
        service = new TaskAssignmentService(registry);
    }

    @Test
    @DisplayName("assignTask 空设备列表返回失败结果，sysid=-1")
    void assignTaskFailsOnEmptyDrones() {
        TaskRequest req = new TaskRequest("t-1", "SURVEY", 5, 30.0, 120.0, 100.0);

        AssignmentResult result = service.assignTask(req);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getAssignedSysid()).isEqualTo(-1);
        assertThat(result.getReason()).contains("无可用无人机");
        assertThat(service.getAllAssignments()).isEmpty();
    }

    @Test
    @DisplayName("assignTask 有设备时分配到评分最高的无人机")
    void assignTaskSelectsHighestScoreDrone() {
        DroneSnapshot d1 = new DroneSnapshot(1);
        d1.battery = 80;
        DroneSnapshot d2 = new DroneSnapshot(2);
        d2.battery = 95; // 电量更高，得分更高
        registry.add(d1, d2);

        TaskRequest req = new TaskRequest("t-1", "SURVEY", 5, 30.0, 120.0, 100.0);
        AssignmentResult result = service.assignTask(req);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAssignedSysid()).isEqualTo(2);
        assertThat(result.getScore()).isGreaterThan(0);
        assertThat(result.getReason()).contains("sysid=2");
    }

    @Test
    @DisplayName("assignTask 评分逻辑：电量高的无人机得分高于电量低的")
    void assignTaskScoreReflectsBattery() {
        DroneSnapshot low = new DroneSnapshot(1);
        low.battery = 10;
        DroneSnapshot high = new DroneSnapshot(2);
        high.battery = 100;
        registry.add(low, high);

        TaskRequest req = new TaskRequest("t-battery", "SURVEY", 5, 30.0, 120.0, 100.0);
        AssignmentResult result = service.assignTask(req);

        assertThat(result.getAssignedSysid()).isEqualTo(2);

        // 反向：让 low 电量更高
        registry.clear();
        low.battery = 100;
        high.battery = 10;
        registry.add(low, high);

        AssignmentResult result2 = service.assignTask(
                new TaskRequest("t-battery2", "SURVEY", 5, 30.0, 120.0, 100.0));
        assertThat(result2.getAssignedSysid()).isEqualTo(1);
    }

    @Test
    @DisplayName("assignTask 优先级高的任务评分更高（priorityScore * 0.1）")
    void assignTaskScoreReflectsPriority() {
        DroneSnapshot d = new DroneSnapshot(1);
        d.battery = 50;
        registry.add(d);

        TaskRequest lowPri = new TaskRequest("t-low", "SURVEY", 0, 30.0, 120.0, 100.0);
        TaskRequest highPri = new TaskRequest("t-high", "SURVEY", 9, 30.0, 120.0, 100.0);

        AssignmentResult lowResult = service.assignTask(lowPri);
        AssignmentResult highResult = service.assignTask(highPri);

        assertThat(highResult.getScore()).isGreaterThan(lowResult.getScore());
    }

    @Test
    @DisplayName("assignTask 位置接近的无人机距离分更高")
    void assignTaskScoreReflectsDistance() {
        DroneSnapshot near = new DroneSnapshot(1);
        near.battery = 50;
        near.lat = 30.001;
        near.lon = 120.001;
        DroneSnapshot far = new DroneSnapshot(2);
        far.battery = 50;
        far.lat = 31.0;
        far.lon = 121.0;
        registry.add(near, far);

        TaskRequest req = new TaskRequest("t-dist", "SURVEY", 5, 30.0, 120.0, 100.0);
        AssignmentResult result = service.assignTask(req);

        assertThat(result.getAssignedSysid()).isEqualTo(1);
    }

    @Test
    @DisplayName("getAllAssignments 初始为空，分配后包含已分配任务")
    void getAllAssignmentsTracksAssignments() {
        assertThat(service.getAllAssignments()).isEmpty();

        DroneSnapshot d = new DroneSnapshot(1);
        d.battery = 80;
        registry.add(d);

        service.assignTask(new TaskRequest("t-1", "SURVEY", 5, 30.0, 120.0, 100.0));
        service.assignTask(new TaskRequest("t-2", "SURVEY", 5, 30.0, 120.0, 100.0));

        Map<String, AssignmentResult> all = service.getAllAssignments();
        assertThat(all).hasSize(2);
        assertThat(all.keySet()).containsExactlyInAnyOrder("t-1", "t-2");
    }

    @Test
    @DisplayName("getAllAssignments 返回不可修改视图")
    void getAllAssignmentsIsUnmodifiable() {
        Map<String, AssignmentResult> all = service.getAllAssignments();
        assertThatThrownBy(() -> all.put("x", new AssignmentResult()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("cancelTask 已分配任务返回 true 并移除")
    void cancelTaskRemovesAssignment() {
        DroneSnapshot d = new DroneSnapshot(1);
        d.battery = 80;
        registry.add(d);
        service.assignTask(new TaskRequest("t-1", "SURVEY", 5, 30.0, 120.0, 100.0));

        boolean ok = service.cancelTask("t-1");
        assertThat(ok).isTrue();
        assertThat(service.getAllAssignments()).isEmpty();
    }

    @Test
    @DisplayName("cancelTask 未分配任务返回 false")
    void cancelTaskReturnsFalseForUnknown() {
        assertThat(service.cancelTask("not-exist")).isFalse();
    }

    @Test
    @DisplayName("reassignAll 清空所有分配")
    void reassignAllClearsAssignments() {
        DroneSnapshot d = new DroneSnapshot(1);
        d.battery = 80;
        registry.add(d);
        service.assignTask(new TaskRequest("t-1", "SURVEY", 5, 30.0, 120.0, 100.0));
        service.assignTask(new TaskRequest("t-2", "SURVEY", 5, 30.0, 120.0, 100.0));
        assertThat(service.getAllAssignments()).hasSize(2);

        service.reassignAll();

        assertThat(service.getAllAssignments()).isEmpty();
    }

    @Test
    @DisplayName("reassignAll 空分配集不抛异常")
    void reassignAllOnEmptyDoesNotThrow() {
        service.reassignAll();
        assertThat(service.getAllAssignments()).isEmpty();
    }

    // ==================== GA 批量分配测试（M10 调度算法优化）====================

    @Test
    @DisplayName("assignTasks 空设备列表对每个任务返回失败结果")
    void assignTasksFailsOnEmptyDrones() {
        List<TaskRequest> reqs = Arrays.asList(
                new TaskRequest("b-1", "SURVEY", 5, 30.0, 120.0, 100.0),
                new TaskRequest("b-2", "SPRAY", 3, 31.0, 121.0, 100.0));

        List<AssignmentResult> results = service.assignTasks(reqs);

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.getAssignedSysid()).isEqualTo(-1);
            assertThat(r.getReason()).contains("无可用无人机");
        });
    }

    @Test
    @DisplayName("assignTasks 空任务列表返回空结果")
    void assignTasksEmptyRequestsReturnsEmpty() {
        DroneSnapshot d = new DroneSnapshot(1);
        d.battery = 80;
        registry.add(d);

        List<AssignmentResult> results = service.assignTasks(new ArrayList<>());

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("assignTasks 返回与输入数量相同的结果")
    void assignTasksReturnsResultForEachTask() {
        registry.add(onlineDrone(1, 80), onlineDrone(2, 90));
        List<TaskRequest> reqs = Arrays.asList(
                new TaskRequest("b-1", "SURVEY", 5, 30.0, 120.0, 100.0),
                new TaskRequest("b-2", "SPRAY", 3, 31.0, 121.0, 100.0),
                new TaskRequest("b-3", "RELAY", 7, 30.5, 120.5, 100.0));

        List<AssignmentResult> results = service.assignTasks(reqs);

        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(r -> assertThat(r.isSuccess()).isTrue());
    }

    @Test
    @DisplayName("assignTasks 任务≥4 且无人机≥4 时启用 GA（reason 含 GA）")
    void assignTasksUsesGaWhenEnoughTasksAndDrones() {
        for (int i = 1; i <= 4; i++) {
            registry.add(onlineDrone(i, 50 + i * 10));
        }
        List<TaskRequest> reqs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            reqs.add(new TaskRequest("ga-" + i, "SURVEY", 5, 30.0 + i * 0.1, 120.0 + i * 0.1, 100.0));
        }

        List<AssignmentResult> results = service.assignTasks(reqs);

        assertThat(results).hasSize(4);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getReason()).startsWith("GA");
        });
    }

    @Test
    @DisplayName("assignTasks 任务<4 时回退简单评分（reason 含 SCORE）")
    void assignTasksFallsBackToScoreWhenFewTasks() {
        for (int i = 1; i <= 5; i++) {
            registry.add(onlineDrone(i, 60 + i * 5));
        }
        List<TaskRequest> reqs = Arrays.asList(
                new TaskRequest("s-1", "SURVEY", 5, 30.0, 120.0, 100.0),
                new TaskRequest("s-2", "SPRAY", 3, 31.0, 121.0, 100.0),
                new TaskRequest("s-3", "RELAY", 7, 30.5, 120.5, 100.0));

        List<AssignmentResult> results = service.assignTasks(reqs);

        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(r -> assertThat(r.getReason()).startsWith("SCORE"));
    }

    @Test
    @DisplayName("assignTasks 无人机<4 时回退简单评分（reason 含 SCORE）")
    void assignTasksFallsBackToScoreWhenFewDrones() {
        registry.add(onlineDrone(1, 80), onlineDrone(2, 90), onlineDrone(3, 70));
        List<TaskRequest> reqs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            reqs.add(new TaskRequest("sd-" + i, "SURVEY", 5, 30.0 + i * 0.1, 120.0, 100.0));
        }

        List<AssignmentResult> results = service.assignTasks(reqs);

        assertThat(results).hasSize(6);
        assertThat(results).allSatisfy(r -> assertThat(r.getReason()).startsWith("SCORE"));
    }

    @Test
    @DisplayName("assignTasks GA 100 次迭代在 100ms 内完成")
    void assignTasksGaCompletesWithin100ms() {
        for (int i = 1; i <= 10; i++) {
            DroneSnapshot d = onlineDrone(i, 40 + i * 5);
            d.lat = 30.0 + i * 0.01;
            d.lon = 120.0 + i * 0.01;
            registry.add(d);
        }
        List<TaskRequest> reqs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            reqs.add(new TaskRequest("p-" + i, "SURVEY", i % 10, 30.05 + i * 0.01, 120.05, 100.0));
        }

        long start = System.nanoTime();
        List<AssignmentResult> results = service.assignTasks(reqs);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(results).hasSize(10);
        assertThat(elapsedMs).isLessThan(100);
    }

    @Test
    @DisplayName("assignTasks GA 分配的 sysid 均为有效无人机")
    void assignTasksGaAssignsValidSysids() {
        int[] sysids = {1, 2, 3, 4, 5};
        for (int s : sysids) {
            registry.add(onlineDrone(s, 50 + s * 8));
        }
        List<TaskRequest> reqs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            reqs.add(new TaskRequest("v-" + i, "SURVEY", i, 30.0 + i * 0.2, 120.0 + i * 0.2, 100.0));
        }

        List<AssignmentResult> results = service.assignTasks(reqs);

        assertThat(results).hasSize(6);
        assertThat(results).allSatisfy(r ->
                assertThat(r.getAssignedSysid()).isIn(1, 2, 3, 4, 5));
    }

    @Test
    @DisplayName("assignTasks GA 多任务多无人机时负载分散到至少 2 台无人机")
    void assignTasksGaDistributesLoadAcrossDrones() {
        for (int i = 1; i <= 4; i++) {
            registry.add(onlineDrone(i, 80));
        }
        List<TaskRequest> reqs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            reqs.add(new TaskRequest("d-" + i, "SURVEY", 5, 30.0, 120.0, 100.0));
        }

        List<AssignmentResult> results = service.assignTasks(reqs);

        long distinctDrones = results.stream()
                .map(AssignmentResult::getAssignedSysid)
                .distinct()
                .count();
        assertThat(distinctDrones).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("assignTasks GA 倾向将任务分配给电量充足的无人机")
    void assignTasksGaPrefersHighBatteryDrones() {
        // 2 台高电量、2 台低电量，8 个相同任务；高电量无人机应承担更多任务
        registry.add(onlineDrone(1, 20), onlineDrone(2, 25));
        registry.add(onlineDrone(3, 95), onlineDrone(4, 90));
        List<TaskRequest> reqs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            reqs.add(new TaskRequest("bat-" + i, "SURVEY", 5, 30.0, 120.0, 100.0));
        }

        List<AssignmentResult> results = service.assignTasks(reqs);

        long highBatteryTasks = results.stream()
                .filter(r -> r.getAssignedSysid() == 3 || r.getAssignedSysid() == 4)
                .count();
        long lowBatteryTasks = results.stream()
                .filter(r -> r.getAssignedSysid() == 1 || r.getAssignedSysid() == 2)
                .count();
        assertThat(highBatteryTasks).isGreaterThan(lowBatteryTasks);
    }

    @Test
    @DisplayName("assignTasks 批量分配后 getAllAssignments 包含全部任务")
    void assignTasksTracksAllAssignments() {
        for (int i = 1; i <= 4; i++) {
            registry.add(onlineDrone(i, 70 + i * 5));
        }
        List<TaskRequest> reqs = Arrays.asList(
                new TaskRequest("t-1", "SURVEY", 5, 30.0, 120.0, 100.0),
                new TaskRequest("t-2", "SPRAY", 3, 31.0, 121.0, 100.0),
                new TaskRequest("t-3", "RESCUE", 9, 30.5, 120.5, 100.0),
                new TaskRequest("t-4", "RELAY", 1, 30.2, 120.2, 100.0));

        service.assignTasks(reqs);

        Map<String, AssignmentResult> all = service.getAllAssignments();
        assertThat(all).hasSize(4);
        assertThat(all.keySet()).containsExactlyInAnyOrder("t-1", "t-2", "t-3", "t-4");
    }

    @Test
    @DisplayName("assignTasks GA 离线无人机不被分配")
    void assignTasksGaSkipsOfflineDrones() {
        // 4 台无人机：前 2 台离线，后 2 台在线
        DroneSnapshot off1 = new DroneSnapshot(1);
        off1.battery = 90;
        off1.online = false;
        DroneSnapshot off2 = new DroneSnapshot(2);
        off2.battery = 90;
        off2.online = false;
        registry.add(off1, off2, onlineDrone(3, 80), onlineDrone(4, 85));
        List<TaskRequest> reqs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            reqs.add(new TaskRequest("off-" + i, "SURVEY", 5, 30.0, 120.0, 100.0));
        }

        List<AssignmentResult> results = service.assignTasks(reqs);

        // 离线无人机能力匹配为 0，GA 应避免分配；断言所有任务分给在线无人机
        assertThat(results).allSatisfy(r ->
                assertThat(r.getAssignedSysid()).isIn(3, 4));
    }

    /** 构造在线无人机快照，电量由参数指定。 */
    private static DroneSnapshot onlineDrone(int sysid, int battery) {
        DroneSnapshot d = new DroneSnapshot(sysid);
        d.battery = battery;
        d.online = true;
        return d;
    }

    /** 可变 DeviceRegistry 桩，绕过 Spring 注入。 */
    private static class StubRegistry extends DeviceRegistry {
        private final List<DroneSnapshot> drones = new ArrayList<>();

        void add(DroneSnapshot... snapshots) {
            for (DroneSnapshot s : snapshots) {
                drones.add(s);
            }
        }

        void clear() {
            drones.clear();
        }

        @Override
        public List<DroneSnapshot> all() {
            return new ArrayList<>(drones);
        }
    }
}
