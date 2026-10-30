package io.aerofleet.cloud.autodispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出警历史保留清理集成测试（@SpringBootTest + H2 内存数据库）。
 * <p>
 * 覆盖：① {@link DispatchHistoryRetentionJob} 按 retention-days 删除过期的历史行
 * （判据 = trigger_time，经 {@code fromRecord} 的正式写入路径造数据）；
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
@DisplayName("出警历史保留清理")
class DispatchHistoryRetentionTest {

    @Autowired
    private DispatchHistoryRepository repository;

    @Autowired
    private DispatchHistoryRetentionJob retentionJob;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        retentionJob.setRetentionDays(31);
    }

    @Test
    @DisplayName("retention-days=31：40 天前的行被删，1 小时前的留下")
    void purgeDeletesRowsOlderThanRetention() {
        saveRow("d-old", Instant.now().minus(40, ChronoUnit.DAYS));
        saveRow("d-recent", Instant.now().minus(1, ChronoUnit.HOURS));

        retentionJob.purgeOldEntries();

        assertThat(repository.findAll()).extracting(DispatchRecordEntity::getDispatchId)
                .containsExactly("d-recent");
    }

    @Test
    @DisplayName("retention-days<=0（默认）：保留任务不删任何行")
    void purgeDisabledWhenRetentionDaysNotPositive() {
        retentionJob.setRetentionDays(0);
        saveRow("d-ancient", Instant.now().minus(400, ChronoUnit.DAYS));

        retentionJob.purgeOldEntries();

        assertThat(repository.count()).isEqualTo(1);
    }

    /** 经正式写入路径（fromRecord）造行：triggerTime 可控、createdAt 为写入时刻。 */
    private void saveRow(String dispatchId, Instant triggerTime) {
        DispatchRecord record = new DispatchRecord(dispatchId, "alarm-1",
                triggerTime.toEpochMilli(), 22.59, 113.93, "DISPATCHED",
                List.of(new DispatchResult.DispatchedDrone(1, true, 30)));
        repository.save(DispatchRecordEntity.fromRecord(record));
    }
}
