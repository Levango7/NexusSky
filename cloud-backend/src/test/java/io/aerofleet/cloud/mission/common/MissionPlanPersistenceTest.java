package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DeviceRepository;
import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:missionplan-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "aerofleet.device-registry.persist=true",
        "aerofleet.mission.persist=true",
        "aerofleet.flightlog.persist-to-db=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@DisplayName("MissionPlan H2/Flyway 持久化联动")
class MissionPlanPersistenceTest {

    private static final MissionPlanStore.StoredMissionItem ITEM =
            new MissionPlanStore.StoredMissionItem(0, 16, 22.59, 113.93, 30, 0, 3);

    @Autowired
    private MissionPlanStore store;

    @Autowired
    private MissionPlanRepository repository;

    @Autowired
    private DeviceRegistry registry;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private PlatformTransactionManager failingCommitManager() {
        PlatformTransactionManager failing = mock(PlatformTransactionManager.class);
        when(failing.getTransaction(any())).thenAnswer(invocation ->
                transactionManager.getTransaction(invocation.getArgument(0)));
        doAnswer(invocation -> {
            TransactionStatus status = invocation.getArgument(0);
            if (!status.isCompleted()) {
                transactionManager.rollback(status);
            }
            return null;
        }).when(failing).rollback(any());
        doAnswer(invocation -> {
            TransactionStatus status = invocation.getArgument(0);
            transactionManager.rollback(status);
            throw new RuntimeException("commit failed");
        }).when(failing).commit(any());
        return failing;
    }

    @BeforeEach
    void clearContext() {
        TenantContext.clear();
    }

    @AfterEach
    void clearContextAfter() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("V44 建表后可落盘，并在新 Store 启动时恢复")
    void snapshotRestoresFromDatabase() {
        store.save(201, 1, List.of(ITEM), "upload");
        assertThat(repository.findById(201)).isPresent();

        MissionPlanStore restarted = new MissionPlanStore(repository);
        ReflectionTestUtils.setField(restarted, "persist", true);
        restarted.restoreFromRepository();

        assertThat(restarted.findVisible(201).items()).containsExactly(ITEM);
        assertThat(restarted.findVisible(201).tenantId()).isEqualTo(1);
    }

    @Test
    @DisplayName("撤销设备同时删除内存和库中的任务快照")
    void deregisterDeletesMissionSnapshot() {
        registry.provision(202, 1);
        store.save(202, 1, List.of(ITEM), "upload");

        assertThat(registry.deregister(202)).isTrue();

        assertThat(store.findVisible(202)).isNull();
        assertThat(repository.findById(202)).isEmpty();
    }

    @Test
    @DisplayName("设备改租户同步任务快照归属")
    void tenantAssignmentMovesMissionSnapshot() {
        registry.provision(203, 1);
        store.save(203, 1, List.of(ITEM), "upload");

        assertThat(registry.assignTenant(203, 2)).isTrue();

        assertThat(repository.findById(203).orElseThrow().getTenantId()).isEqualTo(2);
        TenantContext.setTenantId(1);
        assertThat(store.findVisible(203)).isNull();
        TenantContext.setTenantId(2);
        assertThat(store.findVisible(203)).isNotNull();
    }

    @Test
    @DisplayName("任务快照删除失败时设备删除整体回滚，内存与库行都保留")
    void missionDeleteFailureRollsBackDeviceDeregistration() {
        registry.provision(204, 1);
        store.save(204, 1, List.of(ITEM), "upload");
        MissionPlanRepository failing = mock(MissionPlanRepository.class);
        doThrow(new RuntimeException("delete failed")).when(failing).deleteById(204);
        ReflectionTestUtils.setField(store, "repository", failing);
        try {
            assertThatThrownBy(() -> registry.deregister(204))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("任务快照删除失败");
        } finally {
            ReflectionTestUtils.setField(store, "repository", repository);
        }

        assertThat(registry.isKnownDevice(204)).isTrue();
        assertThat(repository.findById(204)).isPresent();
        assertThat(store.findVisible(204).items()).containsExactly(ITEM);
    }

    @Test
    @DisplayName("任务快照改租户失败时设备归属整体回滚")
    void missionTenantFailureRollsBackDeviceAssignment() {
        registry.provision(205, 1);
        store.save(205, 1, List.of(ITEM), "upload");
        MissionPlanEntity mission = repository.findById(205).orElseThrow();
        MissionPlanRepository failing = mock(MissionPlanRepository.class);
        when(failing.findById(205)).thenReturn(Optional.of(mission));
        when(failing.save(mission)).thenThrow(new RuntimeException("update failed"));
        ReflectionTestUtils.setField(store, "repository", failing);
        try {
            assertThatThrownBy(() -> registry.assignTenant(205, 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("任务快照租户同步失败");
        } finally {
            ReflectionTestUtils.setField(store, "repository", repository);
        }

        assertThat(registry.tenantOf(205)).isEqualTo(1);
        assertThat(store.findVisible(205).tenantId()).isEqualTo(1);
        assertThat(deviceRepository.findById(205).orElseThrow().getTenantId()).isEqualTo(1);
        assertThat(repository.findById(205).orElseThrow().getTenantId()).isEqualTo(1);
    }

    @Test
    @DisplayName("真实设备与任务删除执行后提交失败，数据库和内存均回滚")
    void commitFailureRollsBackExecutedDeregistration() {
        registry.provision(206, 1);
        store.save(206, 1, List.of(ITEM), "upload");
        ReflectionTestUtils.setField(registry, "transactionManager", failingCommitManager());
        try {
            assertThatThrownBy(() -> registry.deregister(206))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("commit failed");

            assertThat(deviceRepository.findById(206)).isPresent();
            assertThat(repository.findById(206)).isPresent();
            assertThat(registry.isKnownDevice(206)).isTrue();
            assertThat(store.findVisible(206).items()).containsExactly(ITEM);
        } finally {
            ReflectionTestUtils.setField(registry, "transactionManager", transactionManager);
        }
    }

    @Test
    @DisplayName("真实设备与任务改租户执行后提交失败，数据库和内存均回滚")
    void commitFailureRollsBackExecutedTenantAssignment() {
        registry.provision(207, 1);
        store.save(207, 1, List.of(ITEM), "upload");
        ReflectionTestUtils.setField(registry, "transactionManager", failingCommitManager());
        try {
            assertThatThrownBy(() -> registry.assignTenant(207, 2))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("commit failed");

            assertThat(deviceRepository.findById(207).orElseThrow().getTenantId()).isEqualTo(1);
            assertThat(repository.findById(207).orElseThrow().getTenantId()).isEqualTo(1);
            assertThat(registry.tenantOf(207)).isEqualTo(1);
            assertThat(store.findVisible(207).tenantId()).isEqualTo(1);
        } finally {
            ReflectionTestUtils.setField(registry, "transactionManager", transactionManager);
        }
    }
}
