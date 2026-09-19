package io.aerofleet.cloud.alarm;

import io.aerofleet.cloud.api.EmergencyOrchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AlarmLinkageEngine} 单元测试（M10 报警联动编排，FR-31）。
 * <p>
 * 覆盖规则添加/移除、事件处理、匹配触发/不匹配不触发、并发安全、禁用规则等。
 */
@DisplayName("AlarmLinkageEngine 联动引擎 (FR-31)")
class AlarmLinkageEngineTest {

    private AlarmEventStore store;
    private AlarmToOrchBridge bridge;
    private AlarmLinkageEngine engine;

    @BeforeEach
    void setUp() {
        // EmergencyOrchService pusher 传 null：测试不验证 WebSocket 推送
        EmergencyOrchService orchService = new EmergencyOrchService(null);
        store = new AlarmEventStore();
        bridge = new AlarmToOrchBridge(orchService);
        engine = new AlarmLinkageEngine(store, bridge);
    }

    private static AlarmEvent fireEvent(String deviceId) {
        return new AlarmEvent("e-" + deviceId, deviceId, "dev",
                AlarmEvent.EventType.FIRE, AlarmEvent.Severity.CRITICAL,
                "fire alarm", 39.9, 116.3, 0, System.currentTimeMillis(), false);
    }

    private static AlarmEvent event(AlarmEvent.EventType type, AlarmEvent.Severity severity,
                                    String deviceId) {
        return new AlarmEvent("e-" + deviceId + "-" + type, deviceId, "dev",
                type, severity, "desc", 39.9, 116.3, 0, System.currentTimeMillis(), false);
    }

    private static AlarmLinkageRule deployRule(String id, AlarmEvent.EventType matchType,
                                               AlarmEvent.Severity minSeverity) {
        return new AlarmLinkageRule(id, "rule-" + id, true,
                matchType, minSeverity, Set.of(),
                AlarmLinkageRule.ActionType.DEPLOY_DRONE,
                2, Double.NaN, Double.NaN, 500, 80, null);
    }

    @Test
    @DisplayName("addRule 添加规则后可查询")
    void addRuleAndQuery() {
        AlarmLinkageRule r = deployRule("r1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO);
        engine.addRule(r);
        assertThat(engine.getRule("r1")).isSameAs(r);
        assertThat(engine.ruleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("addRule 相同 ID 覆盖旧规则")
    void addRuleOverwritesSameId() {
        engine.addRule(deployRule("r1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO));
        engine.addRule(deployRule("r1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN));
        assertThat(engine.ruleCount()).isEqualTo(1);
        assertThat(engine.getRule("r1").getMatchEventType()).isEqualTo(AlarmEvent.EventType.MOTION);
    }

    @Test
    @DisplayName("removeRule 移除规则并返回被移除的规则")
    void removeRuleReturnsRemoved() {
        AlarmLinkageRule r = deployRule("r1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO);
        engine.addRule(r);
        AlarmLinkageRule removed = engine.removeRule("r1");
        assertThat(removed).isSameAs(r);
        assertThat(engine.ruleCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("removeRule 不存在的 ID 返回 null")
    void removeRuleNonExistentReturnsNull() {
        assertThat(engine.removeRule("nonexistent")).isNull();
    }

    @Test
    @DisplayName("processEvent 匹配规则触发联动动作")
    void processEventMatchesAndTriggers() {
        engine.addRule(deployRule("r1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO));
        AlarmLinkageEngine.ProcessResult result = engine.processEvent(fireEvent("dev-1"));

        assertThat(result.getMatchedCount()).isEqualTo(1);
        assertThat(result.getExecutions()).hasSize(1);
        assertThat(result.getExecutions().get(0).getActionType()).isEqualTo("DEPLOY_DRONE");
        assertThat(result.getExecutions().get(0).getStatus()).isEqualTo("DEPLOYED");
        assertThat(result.getExecutions().get(0).getPlanId()).isGreaterThan(0);
    }

    @Test
    @DisplayName("processEvent 不匹配规则不触发联动")
    void processEventNoMatchNoTrigger() {
        engine.addRule(deployRule("r1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO));
        AlarmLinkageEngine.ProcessResult result = engine.processEvent(
                event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, "dev-1"));

        assertThat(result.getMatchedCount()).isEqualTo(0);
        assertThat(result.getExecutions()).isEmpty();
    }

    @Test
    @DisplayName("processEvent 无规则时匹配数为 0")
    void processEventNoRules() {
        AlarmLinkageEngine.ProcessResult result = engine.processEvent(fireEvent("dev-1"));
        assertThat(result.getMatchedCount()).isEqualTo(0);
        assertThat(result.getExecutions()).isEmpty();
    }

    @Test
    @DisplayName("processEvent 事件被存储到 EventStore")
    void processEventStoresEvent() {
        AlarmEvent e = fireEvent("dev-1");
        engine.processEvent(e);
        assertThat(store.getById(e.getId())).isSameAs(e);
    }

    @Test
    @DisplayName("processEvent 多规则匹配全部触发")
    void processEventMultipleRulesAllMatch() {
        engine.addRule(deployRule("r1", null, AlarmEvent.Severity.INFO));
        engine.addRule(deployRule("r2", null, AlarmEvent.Severity.INFO));
        engine.addRule(deployRule("r3", null, AlarmEvent.Severity.INFO));

        AlarmLinkageEngine.ProcessResult result = engine.processEvent(fireEvent("dev-1"));
        assertThat(result.getMatchedCount()).isEqualTo(3);
        assertThat(result.getExecutions()).hasSize(3);
    }

    @Test
    @DisplayName("禁用规则不参与匹配")
    void disabledRuleNotMatched() {
        AlarmLinkageRule r = new AlarmLinkageRule("r1", "rule", false,
                AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO, Set.of(),
                AlarmLinkageRule.ActionType.DEPLOY_DRONE,
                1, Double.NaN, Double.NaN, 100, 50, null);
        engine.addRule(r);

        AlarmLinkageEngine.ProcessResult result = engine.processEvent(fireEvent("dev-1"));
        assertThat(result.getMatchedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("getActiveRules 仅返回启用规则")
    void getActiveRulesReturnsOnlyEnabled() {
        AlarmLinkageRule r1 = deployRule("r1", null, AlarmEvent.Severity.INFO);
        AlarmLinkageRule r2 = deployRule("r2", null, AlarmEvent.Severity.INFO);
        r2.setEnabled(false);
        engine.addRule(r1);
        engine.addRule(r2);

        List<AlarmLinkageRule> active = engine.getActiveRules();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getId()).isEqualTo("r1");
    }

    @Test
    @DisplayName("NOTIFY_ONLY 动作返回 NOTIFIED 状态")
    void notifyOnlyActionReturnsNotified() {
        AlarmLinkageRule r = new AlarmLinkageRule("r1", "rule", true,
                AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO, Set.of(),
                AlarmLinkageRule.ActionType.NOTIFY_ONLY,
                0, Double.NaN, Double.NaN, 0, 0, null);
        engine.addRule(r);

        AlarmLinkageEngine.ProcessResult result = engine.processEvent(fireEvent("dev-1"));
        assertThat(result.getExecutions().get(0).getStatus()).isEqualTo("NOTIFIED");
        assertThat(result.getExecutions().get(0).getPlanId()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("RECORD_VIDEO 动作返回 RECORDING 状态")
    void recordVideoActionReturnsRecording() {
        AlarmLinkageRule r = new AlarmLinkageRule("r1", "rule", true,
                AlarmEvent.EventType.FIRE, AlarmEvent.Severity.INFO, Set.of(),
                AlarmLinkageRule.ActionType.RECORD_VIDEO,
                0, Double.NaN, Double.NaN, 0, 0, null);
        engine.addRule(r);

        AlarmLinkageEngine.ProcessResult result = engine.processEvent(fireEvent("dev-1"));
        assertThat(result.getExecutions().get(0).getStatus()).isEqualTo("RECORDING");
    }

    @Test
    @DisplayName("并发安全：多线程同时添加规则与处理事件不抛异常")
    void concurrentSafety() throws InterruptedException {
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        // 交替添加规则与处理事件
                        if (i % 2 == 0) {
                            engine.addRule(deployRule("r-" + tid + "-" + i,
                                    null, AlarmEvent.Severity.INFO));
                        } else {
                            engine.processEvent(event(AlarmEvent.EventType.MOTION,
                                    AlarmEvent.Severity.WARN, "dev-" + tid + "-" + i));
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(errors.get()).isEqualTo(0);
        assertThat(engine.ruleCount()).isGreaterThan(0);
        assertThat(store.size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("并发安全：多线程同时移除同一规则无竞态")
    void concurrentRemoveSameRule() throws InterruptedException {
        engine.addRule(deployRule("r-shared", null, AlarmEvent.Severity.INFO));
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger removedCount = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    if (engine.removeRule("r-shared") != null) {
                        removedCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(5, TimeUnit.SECONDS);
        pool.shutdown();
        // 恰好一个线程成功移除
        assertThat(removedCount.get()).isEqualTo(1);
        assertThat(engine.ruleCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("getAllRules 返回全部规则不可变快照")
    void getAllRulesReturnsSnapshot() {
        engine.addRule(deployRule("r1", null, AlarmEvent.Severity.INFO));
        engine.addRule(deployRule("r2", null, AlarmEvent.Severity.INFO));
        List<AlarmLinkageRule> all = engine.getAllRules();
        assertThat(all).hasSize(2);
        // 返回不可变快照，修改不影响引擎内部状态
        assertThatThrownBy(all::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(engine.ruleCount()).isEqualTo(2);
    }
}