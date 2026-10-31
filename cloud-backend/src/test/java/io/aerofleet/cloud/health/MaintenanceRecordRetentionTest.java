package io.aerofleet.cloud.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维护记录保留清理集成测试（@SpringBootTest + H2 内存数据库）。
 * <p>
 * 覆盖：① {@link MaintenanceRecordRetentionJob} 按 retention-days 删除过期历史行
 * （判据 = created_at）；② retention-days&lt;=0 时不动数据（默认关闭——
 * 维护记录带适航/合规属性，数值由运营/合规拍板后再开）。
 * {@code created_at} 由 {@code fromRecord} 写为「此刻」，无法经实体 API 回拨——
 * 测试用 JdbcTemplate 直改真库把行改老。共享上下文配置见
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
@DisplayName("维护记录保留清理")
class MaintenanceRecordRetentionTest {

    @Autowired
    private MaintenanceRecordRepository repository;

    @Autowired
    private MaintenanceRecordRetentionJob retentionJob;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        retentionJob.setRetentionDays(31);
    }

    @Test
    @DisplayName("retention-days=31：40 天前的行被删，1 小时前的留下")
    void purgeDeletesRowsOlderThanRetention() {
        saveRow("m-old", Instant.now().minus(40, ChronoUnit.DAYS));
        saveRow("m-recent", Instant.now().minus(1, ChronoUnit.HOURS));

        retentionJob.purgeOldEntries();

        assertThat(repository.findAll()).extracting(MaintenanceRecordEntity::getId)
                .containsExactly("m-recent");
    }

    @Test
    @DisplayName("retention-days<=0（默认）：保留任务不删任何行")
    void purgeDisabledWhenRetentionDaysNotPositive() {
        retentionJob.setRetentionDays(0);
        saveRow("m-ancient", Instant.now().minus(400, ChronoUnit.DAYS));

        retentionJob.purgeOldEntries();

        assertThat(repository.count()).isEqualTo(1);
    }

    /** 经正式写入路径（fromRecord）造行，再直改真库的 created_at 回拨到指定时刻。 */
    private void saveRow(String id, Instant createdAt) {
        MaintenanceRecord r = new MaintenanceRecord(id, 1, ComponentType.MOTOR,
                MaintenanceRecord.MaintenanceType.INSPECT, LocalDate.now(),
                MaintenanceRecord.Status.SCHEDULED);
        repository.save(MaintenanceRecordEntity.fromRecord(r));
        jdbc.update("update maintenance_records set created_at = ? where id = ?",
                java.sql.Timestamp.from(createdAt), id);
    }
}
