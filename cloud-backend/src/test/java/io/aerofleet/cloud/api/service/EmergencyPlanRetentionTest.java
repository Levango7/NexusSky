package io.aerofleet.cloud.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应急编排计划保留清理集成测试（@SpringBootTest + H2 内存数据库）。
 * <p>
 * 覆盖：① {@link EmergencyPlanRetentionJob} 按 retention-days 删除「超过保留期未触碰」
 * 的计划（判据 = updated_at——活文档按最后触碰时刻，不是创建时刻）；
 * ② retention-days&lt;=0 时不动数据。共享上下文配置见
 * {@link io.aerofleet.cloud.geofence.GeofenceBreachRetentionTest} 的说明。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:retention-jobs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "aerofleet.geofence.breach-retention-days=31",
        "aerofleet.autodispatch.retention-days=31",
        "aerofleet.delivery.retention-days=31",
        "aerofleet.emergency-orch.retention-days=31",
        "aerofleet.maintenance.retention-days=31",
        "aerofleet.device-registry.persist=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("应急编排计划保留清理")
class EmergencyPlanRetentionTest {

    @Autowired
    private EmergencyPlanRepository repository;

    @Autowired
    private EmergencyPlanRetentionJob retentionJob;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        retentionJob.setRetentionDays(31);
    }

    @Test
    @DisplayName("retention-days=31：40 天未触碰的计划被删，1 小时前触碰的留下")
    void purgeDeletesPlansNotTouchedWithinRetention() {
        savePlan(101L, Instant.now().minus(40, ChronoUnit.DAYS));
        savePlan(102L, Instant.now().minus(1, ChronoUnit.HOURS));

        retentionJob.purgeOldEntries();

        assertThat(repository.findAll()).extracting(EmergencyPlanEntity::getPlanId)
                .containsExactly(102L);
    }

    @Test
    @DisplayName("retention-days<=0（默认）：保留任务不删任何行")
    void purgeDisabledWhenRetentionDaysNotPositive() {
        retentionJob.setRetentionDays(0);
        savePlan(103L, Instant.now().minus(400, ChronoUnit.DAYS));

        retentionJob.purgeOldEntries();

        assertThat(repository.count()).isEqualTo(1);
    }

    private void savePlan(long planId, Instant updatedAt) {
        EmergencyPlanEntity e = new EmergencyPlanEntity(planId);
        e.setStatus("RUNNING");
        e.setSnapshot("{}");
        e.setUpdatedAt(updatedAt);
        repository.save(e);
    }
}
