package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
