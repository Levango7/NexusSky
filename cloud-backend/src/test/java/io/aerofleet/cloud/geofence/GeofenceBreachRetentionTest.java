package io.aerofleet.cloud.geofence;

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
 * 越界事件保留清理集成测试（@SpringBootTest + H2 内存数据库）。
 * <p>
 * 覆盖：① {@link GeofenceBreachRetentionJob} 按 retention-days 删除过期历史行；
 * ② retention-days&lt;=0 时不动数据（默认关闭，零行为变化）。
 * <p>
 * 与其余四个留存测试共用同一份 @TestPropertySource（含同一 H2 URL）——Spring
 * 测试上下文按配置缓存，五份相同配置只起一个上下文；各类在 @BeforeEach 里
 * 清自己的表，互不干扰。
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
@DisplayName("越界事件保留清理")
class GeofenceBreachRetentionTest {

    @Autowired
    private GeofenceBreachEventRepository repository;

    @Autowired
    private GeofenceBreachRetentionJob retentionJob;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        // 上下文单例可能被其它测试类改过，逐轮复位为 31 天
        retentionJob.setRetentionDays(31);
    }

    @Test
    @DisplayName("retention-days=31：40 天前的行被删，1 小时前的留下")
    void purgeDeletesRowsOlderThanRetention() {
        saveRow(Instant.now().minus(40, ChronoUnit.DAYS));
        saveRow(Instant.now().minus(1, ChronoUnit.HOURS));

        retentionJob.purgeOldEntries();

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("retention-days<=0（默认）：保留任务不删任何行")
    void purgeDisabledWhenRetentionDaysNotPositive() {
        retentionJob.setRetentionDays(0);
        saveRow(Instant.now().minus(400, ChronoUnit.DAYS));

        retentionJob.purgeOldEntries();

        assertThat(repository.count()).isEqualTo(1);
    }

    private void saveRow(Instant timestamp) {
        GeofenceBreachEventEntity e = new GeofenceBreachEventEntity();
        e.setSysid(1);
        e.setZoneId(1);
        e.setZoneName("Z-1");
        e.setBreachType("INTRUSION");
        e.setLat(22.59);
        e.setLon(113.93);
        e.setTimestampMs(timestamp.toEpochMilli());
        repository.save(e);
    }
}
