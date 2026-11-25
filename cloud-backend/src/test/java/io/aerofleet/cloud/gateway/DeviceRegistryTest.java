package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.mission.common.MissionPlanStore;
import io.aerofleet.cloud.security.ApiKeyContext;
import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DeviceRegistry 单元测试：直接实例化（无 Spring 上下文），用 AssertJ 断言。
 * <p>
 * 无参构造器下 heartbeatTimeoutSeconds=0、persist=false、repository=null，
 * 因此 sweepOffline 的 cutoff=0，任何 lastHeartbeatMs>0 的在线设备都会被标记离线；
 * 而刚注册的设备 lastHeartbeatMs 默认为 0，不会被标记。
 */
class DeviceRegistryTest {

    @AfterEach
    void clearTenantContext() {
        ApiKeyContext.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("registerIfAbsent 新 sysid 创建快照并标记在线")
    void registerIfAbsent_newSysid_createsSnapshot() {
        DeviceRegistry registry = new DeviceRegistry();

        DroneSnapshot s = registry.registerIfAbsent(7);

        assertThat(s).isNotNull();
        assertThat(s.sysid).isEqualTo(7);
        assertThat(s.online).isTrue();
    }

    @Test
    @DisplayName("registerIfAbsent 重复 sysid 返回同一快照实例")
    void registerIfAbsent_existingSysid_returnsSameSnapshot() {
        DeviceRegistry registry = new DeviceRegistry();

        DroneSnapshot first = registry.registerIfAbsent(3);
        DroneSnapshot second = registry.registerIfAbsent(3);

        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("get 未知 sysid 返回 null")
    void get_unknownSysid_returnsNull() {
        DeviceRegistry registry = new DeviceRegistry();

        assertThat(registry.get(99)).isNull();
    }

    @Test
    @DisplayName("get 已知 sysid 返回已注册快照")
    void get_knownSysid_returnsSnapshot() {
        DeviceRegistry registry = new DeviceRegistry();

        DroneSnapshot registered = registry.registerIfAbsent(11);
        DroneSnapshot fetched = registry.get(11);

        assertThat(fetched).isSameAs(registered);
    }

    @Test
    @DisplayName("all 空注册表返回空列表")
    void all_empty_returnsEmptyList() {
        DeviceRegistry registry = new DeviceRegistry();

        assertThat(registry.all()).isEmpty();
    }

    @Test
    @DisplayName("all 多设备按 sysid 升序排列")
    void all_multipleDrones_sortedBySysid() {
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(5);
        registry.registerIfAbsent(1);
        registry.registerIfAbsent(9);

        List<DroneSnapshot> all = registry.all();

        assertThat(all).hasSize(3);
        assertThat(all.get(0).sysid).isEqualTo(1);
        assertThat(all.get(1).sysid).isEqualTo(5);
        assertThat(all.get(2).sysid).isEqualTo(9);
    }

    @Test
    @DisplayName("sweepOffline 超时设备被标记离线并返回其 sysid")
    void sweepOffline_marksStaleDroneOffline() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(4);
        // 模拟 100 秒前最后一次心跳
        s.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;

        List<Integer> swept = registry.sweepOffline();

        assertThat(swept).contains(4);
        assertThat(s.online).isFalse();
    }

    @Test
    @DisplayName("sweepOffline 刚注册设备（无心跳）保持在线")
    void sweepOffline_freshDrone_staysOnline() {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot s = registry.registerIfAbsent(6);
        // lastHeartbeatMs 默认 0，sweepOffline 要求 lastHeartbeatMs > 0 才会处理

        List<Integer> swept = registry.sweepOffline();

        assertThat(swept).doesNotContain(6);
        assertThat(s.online).isTrue();
    }

    @Test
    @DisplayName("离线扫描持锁期间撤销等待，扫描保存完成后才删除设备")
    void sweepOfflineAndDeregisterSerialize() throws Exception {
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot snapshot = registry.registerIfAbsent(8);
        snapshot.lastHeartbeatMs = System.currentTimeMillis() - 100_000L;
        DeviceRepository repository = mock(DeviceRepository.class);
        DeviceEntity entity = new DeviceEntity(8);
        CountDownLatch findStarted = new CountDownLatch(1);
        CountDownLatch allowFind = new CountDownLatch(1);
        when(repository.findById(8)).thenAnswer(invocation -> {
            findStarted.countDown();
            assertThat(allowFind.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(entity);
        });
        when(repository.existsById(8)).thenReturn(true);
        ReflectionTestUtils.setField(registry, "repository", repository);
        ReflectionTestUtils.setField(registry, "persist", true);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<List<Integer>> sweep = executor.submit(registry::sweepOffline);
            assertThat(findStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> deregister = executor.submit(() -> registry.deregister(8));
            assertThatThrownBy(() -> deregister.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowFind.countDown();
            assertThat(sweep.get(5, TimeUnit.SECONDS)).containsExactly(8);
            assertThat(deregister.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.isKnownDevice(8)).isFalse();
            InOrder calls = inOrder(repository);
            calls.verify(repository).findById(8);
            calls.verify(repository).save(entity);
            calls.verify(repository).existsById(8);
            calls.verify(repository).deleteById(8);
        } finally {
            allowFind.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("事务提交失败时设备与任务内存态都不提前删除")
    void commitFailureKeepsDeviceAndMissionInMemory() {
        ApiKeyContext.clear();
        TenantContext.clear();
        DeviceRegistry registry = new DeviceRegistry();
        registry.registerIfAbsent(1);
        MissionPlanStore plans = new MissionPlanStore();
        plans.save(1, null, List.of(), "drone");
        ReflectionTestUtils.setField(registry, "missionPlans", plans);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        doThrow(new RuntimeException("commit failed")).when(transactionManager).commit(status);
        ReflectionTestUtils.setField(registry, "transactionManager", transactionManager);

        assertThat(plans.findVisible(1)).isNotNull();
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> registry.deregister(1))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("commit failed");
            assertThat(registry.isKnownDevice(1)).isTrue();
            assertThat(plans.findVisible(1)).isNotNull();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}