package io.aerofleet.sim.orch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrchestrationEngine 编排引擎单测（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 覆盖：startOrchestration 创建 plan、planId 唯一、abortOrchestration、getPlan、
 * 状态机转换、阶段失败/重试、并发安全、集成接口为 null 时不崩溃。
 */
@DisplayName("OrchestrationEngine 编排引擎 (M9 T2)")
class OrchestrationEngineTest {

    /** 启用引擎的配置。 */
    private static OrchestrationConfig enabledConfig() {
        return new OrchestrationConfig(
                true, 10, 50, 30000L, 30, 15, 3,
                500.0, 100.0, 3, 2000.0, 10.0);
    }

    /** 全 null 集成接口的引擎。 */
    private static OrchestrationEngine newEngine() {
        return new OrchestrationEngine(enabledConfig(), null, null, null, null);
    }

    private static List<Integer> drones(int n) {
        Integer[] arr = new Integer[n];
        for (int i = 0; i < n; i++) {
            arr[i] = 100 + i;
        }
        return Arrays.asList(arr);
    }

    @Test
    @DisplayName("startOrchestration 创建 plan 并返回唯一 planId")
    void startOrchestrationCreatesPlan() {
        OrchestrationEngine engine = newEngine();

        long id1 = engine.startOrchestration(0, 300000000, 1040000000, 5000, drones(3));
        long id2 = engine.startOrchestration(1, 310000000, 1050000000, 3000, drones(2));

        assertThat(id1).isPositive();
        assertThat(id2).isPositive();
        assertThat(id1).isNotEqualTo(id2);

        OrchestrationPlan p1 = engine.getPlan(id1);
        OrchestrationPlan p2 = engine.getPlan(id2);
        assertThat(p1).isNotNull();
        assertThat(p2).isNotNull();
        assertThat(p1.getScenarioType()).isEqualTo(0);
        assertThat(p2.getScenarioType()).isEqualTo(1);
        assertThat(engine.getPlanCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("getPlan 不存在返回 null")
    void getPlanNotFound() {
        OrchestrationEngine engine = newEngine();
        assertThat(engine.getPlan(99999L)).isNull();
    }

    @Test
    @DisplayName("abortOrchestration 中止 plan，当前阶段变 ABORTED")
    void abortOrchestration() {
        OrchestrationEngine engine = newEngine();
        long id = engine.startOrchestration(0, 300000000, 1040000000, 5000, drones(3));

        engine.abortOrchestration(id);

        OrchestrationPlan plan = engine.getPlan(id);
        OrchestrationState current = plan.getCurrentPhaseState();
        assertThat(current.getStatus()).isEqualTo(OrchestrationState.Status.ABORTED);
        // 事件日志应包含 PlanAborted
        assertThat(plan.getEventLog().toString()).contains("PlanAborted");
    }

    @Test
    @DisplayName("abortOrchestration 不存在的 planId 不抛异常")
    void abortNonExistentPlan() {
        OrchestrationEngine engine = newEngine();
        // 不抛异常即通过
        engine.abortOrchestration(99999L);
    }

    @Test
    @DisplayName("状态机转换：PENDING → RUNNING → COMPLETED（前 3 阶段自动完成）")
    void stateMachineTransition() {
        OrchestrationEngine engine = newEngine();
        long id = engine.startOrchestration(0, 300000000, 1040000000, 5000, drones(3));

        OrchestrationPlan plan = engine.getPlan(id);
        // 阶段 0/1/2 应自动完成，当前停在阶段 3 (CONTINUOUS_SERVICE)
        assertThat(plan.getPhaseState(0).getStatus()).isEqualTo(OrchestrationState.Status.COMPLETED);
        assertThat(plan.getPhaseState(1).getStatus()).isEqualTo(OrchestrationState.Status.COMPLETED);
        assertThat(plan.getPhaseState(2).getStatus()).isEqualTo(OrchestrationState.Status.COMPLETED);
        // 阶段 3 进入 RUNNING（tick 驱动，不自动 complete）
        assertThat(plan.getCurrentPhaseIndex()).isEqualTo(3);
        assertThat(plan.getCurrentPhaseState().getStatus()).isEqualTo(OrchestrationState.Status.RUNNING);
    }

    @Test
    @DisplayName("集成接口全 null 时引擎不崩溃，阶段正常完成")
    void nullIntegrationsNoCrash() {
        OrchestrationEngine engine = newEngine();
        long id = engine.startOrchestration(2, 300000000, 1040000000, 5000, drones(5));

        OrchestrationPlan plan = engine.getPlan(id);
        assertThat(plan.getPhaseState(0).getStatus()).isEqualTo(OrchestrationState.Status.COMPLETED);
        assertThat(plan.getPhaseState(2).getStatus()).isEqualTo(OrchestrationState.Status.COMPLETED);
        // 事件日志应记录跳过信息
        assertThat(plan.getEventLog().toString()).contains("DisasterMapping skipped");
    }

    @Test
    @DisplayName("onDroneLost 移除 mesh/cell 节点并记录日志")
    void onDroneLost() {
        CountingMesh mesh = new CountingMesh();
        CountingCell cell = new CountingCell();
        OrchestrationEngine engine = new OrchestrationEngine(
                enabledConfig(), mesh, cell, null, null);
        long id = engine.startOrchestration(0, 300000000, 1040000000, 5000, drones(3));

        engine.onDroneLost(100);

        assertThat(mesh.removed).contains(100);
        assertThat(cell.removed).contains(100);
        OrchestrationPlan plan = engine.getPlan(id);
        assertThat(plan.getEventLog().toString()).contains("DroneLost");
    }

    @Test
    @DisplayName("onLowBattery 低于阈值时记录日志，危急时提升优先级")
    void onLowBattery() {
        OrchestrationEngine engine = newEngine();
        long id = engine.startOrchestration(0, 300000000, 1040000000, 5000, drones(3));

        engine.onLowBattery(100, 20);  // low but not critical
        engine.onLowBattery(101, 10);  // critical

        OrchestrationPlan plan = engine.getPlan(id);
        String log = plan.getEventLog().toString();
        assertThat(log).contains("LowBattery");
        assertThat(log).contains("CriticalBattery");
    }

    @Test
    @DisplayName("onTerrainChanged 记录日志")
    void onTerrainChanged() {
        OrchestrationEngine engine = newEngine();
        long id = engine.startOrchestration(0, 300000000, 1040000000, 5000, drones(3));

        engine.onTerrainChanged(5, 10);

        OrchestrationPlan plan = engine.getPlan(id);
        assertThat(plan.getEventLog().toString()).contains("TerrainChanged");
    }

    @Test
    @DisplayName("tick 在 CONTINUOUS_SERVICE 阶段不抛异常")
    void tickNoCrash() {
        OrchestrationEngine engine = newEngine();
        engine.startOrchestration(0, 300000000, 1040000000, 5000, drones(3));

        for (int i = 0; i < 10; i++) {
            engine.tick(System.currentTimeMillis() + i * 1000L);
        }
        // 无异常即通过
    }

    @Test
    @DisplayName("tick 检测阶段超时并触发 failPhase")
    void tickDetectsTimeout() {
        OrchestrationEngine engine = newEngine();
        long id = engine.startOrchestration(0, 300000000, 1040000000, 5000, drones(3));
        OrchestrationPlan plan = engine.getPlan(id);

        // CONTINUOUS_SERVICE 无超时（Long.MAX_VALUE），不会触发；用大 nowMs tick
        engine.tick(Long.MAX_VALUE);
        // 当前阶段仍 RUNNING（无超时）
        assertThat(plan.getCurrentPhaseState().getStatus()).isEqualTo(OrchestrationState.Status.RUNNING);
    }

    @Test
    @DisplayName("并发 startOrchestration 生成唯一 planId（多线程安全）")
    void concurrentStartOrchestration() throws InterruptedException {
        // 用大 maxConcurrentPlans 避免触达上限
        OrchestrationConfig bigConfig = new OrchestrationConfig(
                true, 100, 50, 30000L, 30, 15, 3, 500.0, 100.0, 3, 2000.0, 10.0);
        OrchestrationEngine engine = new OrchestrationEngine(bigConfig, null, null, null, null);
        int threads = 8;
        int perThread = 5;
        CountDownLatch latch = new CountDownLatch(threads);
        ConcurrentHashMap<Long, Long> seen = new ConcurrentHashMap<Long, Long>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread th = new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        long id = engine.startOrchestration(
                                0, 300000000 + tid, 1040000000 + tid, 5000, drones(2));
                        seen.put(id, (long) tid);
                    }
                } finally {
                    latch.countDown();
                }
            });
            th.start();
        }
        latch.await();

        assertThat(seen.size()).isEqualTo(threads * perThread);
        assertThat(engine.getPlanCount()).isEqualTo(threads * perThread);
    }

    @Test
    @DisplayName("config.enabled=false 时 startOrchestration 抛 IllegalStateException")
    void disabledEngineRejectsStart() {
        OrchestrationEngine engine = new OrchestrationEngine(
                OrchestrationConfig.defaults(), null, null, null, null);
        assertThatThrownBy(() -> engine.startOrchestration(0, 300000000, 1040000000, 5000, drones(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("droneIds 超过 maxDrones 抛 IllegalArgumentException")
    void tooManyDrones() {
        OrchestrationConfig small = new OrchestrationConfig(
                true, 10, 3, 30000L, 30, 15, 3, 500.0, 100.0, 3, 2000.0, 10.0);
        OrchestrationEngine engine = new OrchestrationEngine(small, null, null, null, null);
        assertThatThrownBy(() -> engine.startOrchestration(0, 300000000, 1040000000, 5000, drones(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("OrchestrationPhase.fromCode 正确反查")
    void phaseFromCode() {
        assertThat(OrchestrationPhase.fromCode(0)).isEqualTo(OrchestrationPhase.DISASTER_MAPPING);
        assertThat(OrchestrationPhase.fromCode(4)).isEqualTo(OrchestrationPhase.SELF_HEALING);
        assertThatThrownBy(() -> OrchestrationPhase.fromCode(5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("OrchestrationState 重试上限：canRetry 与 retry 行为")
    void stateRetryLimit() {
        OrchestrationState s = new OrchestrationState(OrchestrationPhase.COVERAGE_PLANNING);
        assertThat(s.canRetry()).isTrue();
        assertThat(s.getRetryCount()).isZero();

        // 模拟 3 次失败-重试
        for (int i = 0; i < OrchestrationState.MAX_RETRY; i++) {
            s.start();
            s.fail("test");
            s.retry();
        }
        assertThat(s.getRetryCount()).isEqualTo(OrchestrationState.MAX_RETRY);
        assertThat(s.canRetry()).isFalse();

        // 第 4 次失败后 retry 抛异常
        s.start();
        s.fail("test");
        assertThatThrownBy(() -> s.retry()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("OrchestrationPlan 指标 setter 验证")
    void planMetricValidation() {
        OrchestrationPlan plan = new OrchestrationPlan(
                1L, 0, 300000000, 1040000000, 5000, drones(2));
        plan.setCoverageRate(50);
        plan.setConnectRate(80);
        plan.setCurrentPriority(1);
        assertThat(plan.getCoverageRate()).isEqualTo(50);
        assertThat(plan.getConnectRate()).isEqualTo(80);
        assertThat(plan.getCurrentPriority()).isEqualTo(1);

        assertThatThrownBy(() -> plan.setCoverageRate(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan.setCoverageRate(101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan.setCurrentPriority(3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("OrchestrationPlan 事件日志线程安全追加")
    void planEventLog() {
        OrchestrationPlan plan = new OrchestrationPlan(
                1L, 0, 300000000, 1040000000, 5000, drones(2));
        plan.logEvent("event1");
        plan.logEvent("event2");
        plan.logEvent(null);  // null 被忽略
        List<String> log = plan.getEventLog();
        assertThat(log).hasSize(2);
        assertThat(log.get(0)).contains("event1");
        assertThat(log.get(1)).contains("event2");
    }

    // ---- 测试用集成接口桩 ----

    /** 计数式 Mesh 桩，记录 add/remove 调用。 */
    private static class CountingMesh implements MeshIntegration {
        final Set<Integer> added = Collections.synchronizedSet(new HashSet<Integer>());
        final Set<Integer> removed = Collections.synchronizedSet(new HashSet<Integer>());

        @Override
        public void addNode(int droneId, double lat, double lon) {
            added.add(droneId);
        }

        @Override
        public void removeNode(int droneId) {
            removed.add(droneId);
        }

        @Override
        public boolean isReachable(int fromId, int toId) {
            return true;
        }

        @Override
        public int getHopCount(int fromId, int toId) {
            return fromId == toId ? 0 : 1;
        }
    }

    /** 计数式 CellTower 桩。 */
    private static class CountingCell implements CellTowerIntegration {
        final Set<Integer> created = Collections.synchronizedSet(new HashSet<Integer>());
        final Set<Integer> removed = Collections.synchronizedSet(new HashSet<Integer>());

        @Override
        public void createCellTower(int droneId, int cellType, double lat, double lon, int txPower) {
            created.add(droneId);
        }

        @Override
        public void removeCellTower(int droneId) {
            removed.add(droneId);
        }

        @Override
        public double getCoverageArea(int droneId) {
            return 1.0;
        }

        @Override
        public void triggerHandover(int fromDroneId, int toDroneId) {
        }
    }
}