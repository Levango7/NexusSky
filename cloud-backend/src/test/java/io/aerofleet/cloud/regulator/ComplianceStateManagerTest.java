package io.aerofleet.cloud.regulator;

import io.aerofleet.cloud.regulator.model.ComplianceState;
import io.aerofleet.cloud.regulator.model.ComplianceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ComplianceStateManager} 单元测试（直接实例化，无 Spring 上下文）。
 * <p>
 * 验证状态流转（正向/非法逆向）、并发安全、查询与清理功能。
 */
@DisplayName("ComplianceStateManager 合规状态管理器")
class ComplianceStateManagerTest {

    private ComplianceStateManager manager;

    @BeforeEach
    void setUp() {
        manager = new ComplianceStateManager();
    }

    // ─── 正向状态流转 ───

    @Nested
    @DisplayName("正向状态流转")
    class ForwardTransition {

        @Test
        @DisplayName("UNVERIFIED 初始化")
        void transition_initUnverified() {
            ComplianceStatus status = manager.transition(1, ComplianceState.UNVERIFIED);

            assertThat(status.sysid()).isEqualTo(1);
            assertThat(status.status()).isEqualTo(ComplianceState.UNVERIFIED);
            assertThat(status.productSerialNo()).isNull();
        }

        @Test
        @DisplayName("UNVERIFIED → VERIFIED")
        void transition_unverifiedToVerified() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            ComplianceStatus status = manager.transition(1, ComplianceState.VERIFIED);

            assertThat(status.status()).isEqualTo(ComplianceState.VERIFIED);
        }

        @Test
        @DisplayName("VERIFIED → ACTIVATED")
        void transition_verifiedToActivated() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.VERIFIED);
            ComplianceStatus status = manager.transition(1, ComplianceState.ACTIVATED);

            assertThat(status.status()).isEqualTo(ComplianceState.ACTIVATED);
        }

        @Test
        @DisplayName("ACTIVATED → OPERATING")
        void transition_activatedToOperating() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.VERIFIED);
            manager.transition(1, ComplianceState.ACTIVATED);
            ComplianceStatus status = manager.transition(1, ComplianceState.OPERATING);

            assertThat(status.status()).isEqualTo(ComplianceState.OPERATING);
        }

        @Test
        @DisplayName("ACTIVATED → CANCELLED")
        void transition_activatedToCancelled() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.VERIFIED);
            manager.transition(1, ComplianceState.ACTIVATED);
            ComplianceStatus status = manager.transition(1, ComplianceState.CANCELLED);

            assertThat(status.status()).isEqualTo(ComplianceState.CANCELLED);
        }

        @Test
        @DisplayName("OPERATING → CANCELLED")
        void transition_operatingToCancelled() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.VERIFIED);
            manager.transition(1, ComplianceState.ACTIVATED);
            manager.transition(1, ComplianceState.OPERATING);
            ComplianceStatus status = manager.transition(1, ComplianceState.CANCELLED);

            assertThat(status.status()).isEqualTo(ComplianceState.CANCELLED);
        }

        @Test
        @DisplayName("完整生命周期流转 UNVERIFIED → VERIFIED → ACTIVATED → OPERATING → CANCELLED")
        void transition_fullLifecycle() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.VERIFIED);
            manager.transition(1, ComplianceState.ACTIVATED);
            manager.transition(1, ComplianceState.OPERATING);
            ComplianceStatus status = manager.transition(1, ComplianceState.CANCELLED);

            assertThat(status.status()).isEqualTo(ComplianceState.CANCELLED);
        }
    }

    // ─── 非法状态流转 ───

    @Nested
    @DisplayName("非法状态流转")
    class IllegalTransition {

        @Test
        @DisplayName("不存在 sysid 直接初始化为 VERIFIED 抛出 IllegalStateException")
        void transition_nonExistentToVerified_throws() {
            assertThatThrownBy(() -> manager.transition(1, ComplianceState.VERIFIED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UNVERIFIED");
        }

        @Test
        @DisplayName("不存在 sysid 直接初始化为 ACTIVATED 抛出 IllegalStateException")
        void transition_nonExistentToActivated_throws() {
            assertThatThrownBy(() -> manager.transition(1, ComplianceState.ACTIVATED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UNVERIFIED");
        }

        @Test
        @DisplayName("VERIFIED → UNVERIFIED 逆向流转抛出 IllegalStateException")
        void transition_verifiedToUnverified_throws() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.VERIFIED);

            assertThatThrownBy(() -> manager.transition(1, ComplianceState.UNVERIFIED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非法状态流转");
        }

        @Test
        @DisplayName("ACTIVATED → VERIFIED 逆向流转抛出 IllegalStateException")
        void transition_activatedToVerified_throws() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.VERIFIED);
            manager.transition(1, ComplianceState.ACTIVATED);

            assertThatThrownBy(() -> manager.transition(1, ComplianceState.VERIFIED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非法状态流转");
        }

        @Test
        @DisplayName("UNVERIFIED → ACTIVATED 跳跃流转抛出 IllegalStateException")
        void transition_unverifiedToActivated_throws() {
            manager.transition(1, ComplianceState.UNVERIFIED);

            assertThatThrownBy(() -> manager.transition(1, ComplianceState.ACTIVATED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非法状态流转");
        }

        @Test
        @DisplayName("CANCELLED → 任何状态流转抛出 IllegalStateException（终态）")
        void transition_cancelledToAny_throws() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.VERIFIED);
            manager.transition(1, ComplianceState.ACTIVATED);
            manager.transition(1, ComplianceState.CANCELLED);

            assertThatThrownBy(() -> manager.transition(1, ComplianceState.OPERATING))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> manager.transition(1, ComplianceState.UNVERIFIED))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> manager.transition(1, ComplianceState.VERIFIED))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("OPERATING → ACTIVATED 逆向流转抛出 IllegalStateException")
        void transition_operatingToActivated_throws() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.VERIFIED);
            manager.transition(1, ComplianceState.ACTIVATED);
            manager.transition(1, ComplianceState.OPERATING);

            assertThatThrownBy(() -> manager.transition(1, ComplianceState.ACTIVATED))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ─── 查询功能 ───

    @Nested
    @DisplayName("查询功能")
    class QueryOperations {

        @Test
        @DisplayName("get 不存在的 sysid 返回 null")
        void get_nonExistent_returnsNull() {
            assertThat(manager.get(99)).isNull();
        }

        @Test
        @DisplayName("get 已存在的 sysid 返回状态记录")
        void get_existing_returnsStatus() {
            manager.transition(1, ComplianceState.UNVERIFIED);

            ComplianceStatus status = manager.get(1);

            assertThat(status).isNotNull();
            assertThat(status.sysid()).isEqualTo(1);
            assertThat(status.status()).isEqualTo(ComplianceState.UNVERIFIED);
        }

        @Test
        @DisplayName("getAll 空管理器返回空列表")
        void getAll_empty_returnsEmptyList() {
            List<ComplianceStatus> all = manager.getAll();

            assertThat(all).isEmpty();
        }

        @Test
        @DisplayName("getAll 返回所有记录并按 sysid 升序排列")
        void getAll_returnsAllSortedBySysid() {
            manager.transition(3, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(2, ComplianceState.UNVERIFIED);

            List<ComplianceStatus> all = manager.getAll();

            assertThat(all).hasSize(3);
            assertThat(all.get(0).sysid()).isEqualTo(1);
            assertThat(all.get(1).sysid()).isEqualTo(2);
            assertThat(all.get(2).sysid()).isEqualTo(3);
        }
    }

    // ─── 移除功能 ───

    @Nested
    @DisplayName("移除功能")
    class RemoveOperations {

        @Test
        @DisplayName("remove 已存在的 sysid 返回被移除的记录")
        void remove_existing_returnsRemoved() {
            manager.transition(1, ComplianceState.UNVERIFIED);

            ComplianceStatus removed = manager.remove(1);

            assertThat(removed).isNotNull();
            assertThat(removed.sysid()).isEqualTo(1);
            assertThat(manager.get(1)).isNull();
        }

        @Test
        @DisplayName("remove 不存在的 sysid 返回 null")
        void remove_nonExistent_returnsNull() {
            assertThat(manager.remove(99)).isNull();
        }

        @Test
        @DisplayName("remove 后可以重新初始化")
        void remove_thenReinitialize() {
            manager.transition(1, ComplianceState.UNVERIFIED);
            manager.transition(1, ComplianceState.VERIFIED);
            manager.remove(1);

            // 重新初始化不应抛出异常
            ComplianceStatus status = manager.transition(1, ComplianceState.UNVERIFIED);
            assertThat(status.status()).isEqualTo(ComplianceState.UNVERIFIED);
        }
    }

    // ─── 并发安全 ───

    @Nested
    @DisplayName("并发安全")
    class ConcurrencySafety {

        @Test
        @DisplayName("多线程对不同 sysid 操作互不干扰")
        void concurrentTransition_differentSysids_noInterference() throws InterruptedException {
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int sysid = i + 1;
                executor.submit(() -> {
                    try {
                        manager.transition(sysid, ComplianceState.UNVERIFIED);
                        manager.transition(sysid, ComplianceState.VERIFIED);
                        manager.transition(sysid, ComplianceState.ACTIVATED);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            for (int i = 1; i <= threadCount; i++) {
                ComplianceStatus status = manager.get(i);
                assertThat(status).isNotNull();
                assertThat(status.status()).isEqualTo(ComplianceState.ACTIVATED);
            }
        }

        @Test
        @DisplayName("多线程对同一 sysid 并发初始化只有一个成功")
        void concurrentTransition_sameSysid_onlyOneSucceeds() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            int[] successCount = {0};
            int[] failureCount = {0};

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        manager.transition(1, ComplianceState.UNVERIFIED);
                        synchronized (successCount) {
                            successCount[0]++;
                        }
                    } catch (IllegalStateException e) {
                        synchronized (failureCount) {
                            failureCount[0]++;
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 只有一个线程成功初始化
            assertThat(successCount[0]).isEqualTo(1);
            assertThat(failureCount[0]).isEqualTo(threadCount - 1);
            assertThat(manager.get(1).status()).isEqualTo(ComplianceState.UNVERIFIED);
        }
    }

    // ─── 产品序列号绑定（遥测上报的身份来源）──

    @Nested
    @DisplayName("产品序列号绑定")
    class SerialBinding {

        @Test
        @DisplayName("初始化时带 productSerialNo 即落库")
        void initBindsSerial() {
            ComplianceStatus status = manager.transition(7, ComplianceState.UNVERIFIED, "TEST-001");
            assertThat(status.productSerialNo()).isEqualTo("TEST-001");
        }

        @Test
        @DisplayName("已绑定的序列号在后续流转中保留（遥测上报据此拼身份）")
        void serialSurvivesTransitions() {
            manager.transition(7, ComplianceState.UNVERIFIED, "TEST-001");
            ComplianceStatus verified = manager.transition(7, ComplianceState.VERIFIED, "TEST-001");
            ComplianceStatus activated = manager.transition(7, ComplianceState.ACTIVATED, "TEST-001");
            ComplianceStatus operating = manager.transition(7, ComplianceState.OPERATING);

            assertThat(verified.productSerialNo()).isEqualTo("TEST-001");
            assertThat(activated.productSerialNo()).isEqualTo("TEST-001");
            // 不带序列号的调用（如遥测驱动的 ACTIVATED → OPERATING）不能把身份擦掉
            assertThat(operating.productSerialNo()).isEqualTo("TEST-001");
        }

        @Test
        @DisplayName("新值非空则覆盖（以最新一次实名/激活请求为准）")
        void serialRebindOverwrites() {
            manager.transition(7, ComplianceState.UNVERIFIED, "OLD-001");
            ComplianceStatus rebound = manager.transition(7, ComplianceState.VERIFIED, "NEW-002");
            assertThat(rebound.productSerialNo()).isEqualTo("NEW-002");
        }

        @Test
        @DisplayName("两参重载不改变已绑定的序列号")
        void twoArgOverloadKeepsSerial() {
            manager.transition(7, ComplianceState.UNVERIFIED, "TEST-001");
            manager.transition(7, ComplianceState.VERIFIED, "TEST-001");
            ComplianceStatus activated = manager.transition(7, ComplianceState.ACTIVATED);
            assertThat(activated.productSerialNo()).isEqualTo("TEST-001");
        }
    }

    // ─── 状态记录字段保持 ───

    @Test
    @DisplayName("两参流转保持既有字段不变（未绑定时序列号仍为 null）")
    void transition_preservesFields() {
        manager.transition(1, ComplianceState.UNVERIFIED);
        manager.transition(1, ComplianceState.VERIFIED);
        ComplianceStatus afterActivated = manager.transition(1, ComplianceState.ACTIVATED);

        assertThat(afterActivated.sysid()).isEqualTo(1);
        assertThat(afterActivated.productSerialNo()).isNull();
        assertThat(afterActivated.activationId()).isNull();
        assertThat(afterActivated.cancellationId()).isNull();
    }
}