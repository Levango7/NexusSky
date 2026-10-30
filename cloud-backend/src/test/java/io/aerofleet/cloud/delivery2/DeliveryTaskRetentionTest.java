package io.aerofleet.cloud.delivery2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配送任务保留清理集成测试（@SpringBootTest + H2 内存数据库）。
 * <p>
 * 覆盖 {@code coalesce(created_at, start_time)} 三条分支与默认关闭：
 * ① 已启动且 start_time 过期 → 删；② PENDING 且 created_at 新 → 留；
 * ③ PENDING 但 created_at 已过期（V38 起新行一律有此值）→ 删；
 * ④ retention-days&lt;=0 → 一行不动。
 * {@code created_at} 由 {@code @PrePersist} 写入，无法经实体 API 回拨——
 * 测试用 JdbcTemplate 直改真库（本仓「真库直写」测试先例）把该行改老。
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
@DisplayName("配送任务保留清理")
class DeliveryTaskRetentionTest {

    @Autowired
    private DeliveryTask2Repository repository;

    @Autowired
    private DeliveryTaskRetentionJob retentionJob;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        retentionJob.setRetentionDays(31);
    }

    @Test
    @DisplayName("三条时间基准分支：V38 前已启动过期删 / 新建留 / 仅 created_at 过期删")
    void purgeByCoalesceOfCreatedAtAndStartTime() {
        // ① V38 前形态：created_at 为 NULL、已启动且 start_time 过期 → coalesce 落到 start_time → 删
        DeliveryTask2 startedOld = newTask("t-started-old", DeliveryTask2.Status.IN_PROGRESS);
        startedOld.setStartTime(Instant.now().minus(40, ChronoUnit.DAYS));
        repository.save(startedOld);
        jdbc.update("update delivery_task2 set created_at = null where id = ?", "t-started-old");
        // ② PENDING 且 created_at 新（@PrePersist 写入）→ 留
        repository.save(newTask("t-pending-new", DeliveryTask2.Status.PENDING));
        // ③ PENDING，但 created_at 已过期（V38 后新行的典型形态）→ 删
        repository.save(newTask("t-pending-old", DeliveryTask2.Status.PENDING));
        backdateCreatedAt("t-pending-old", Instant.now().minus(40, ChronoUnit.DAYS));

        retentionJob.purgeOldEntries();

        assertThat(repository.findAll()).extracting(DeliveryTask2::getId)
                .containsExactly("t-pending-new");
    }

    @Test
    @DisplayName("retention-days<=0（默认）：保留任务不删任何行")
    void purgeDisabledWhenRetentionDaysNotPositive() {
        retentionJob.setRetentionDays(0);
        DeliveryTask2 ancient = newTask("t-ancient", DeliveryTask2.Status.PENDING);
        repository.save(ancient);
        backdateCreatedAt("t-ancient", Instant.now().minus(400, ChronoUnit.DAYS));

        retentionJob.purgeOldEntries();

        assertThat(repository.count()).isEqualTo(1);
    }

    private DeliveryTask2 newTask(String id, DeliveryTask2.Status status) {
        DeliveryTask2 t = new DeliveryTask2();
        t.setId(id);
        t.setType(DeliveryTask2.Type.REGULAR_PARCEL);
        t.setStatus(status);
        t.setSenderLat(22.5907);
        t.setSenderLon(113.9345);
        t.setReceiverLat(22.5924);
        t.setReceiverLon(113.9370);
        t.setReceiverName("receiver");
        t.setPriority(DeliveryTask2.Priority.NORMAL);
        return t;
    }

    private void backdateCreatedAt(String id, Instant createdAt) {
        jdbc.update("update delivery_task2 set created_at = ? where id = ?",
                java.sql.Timestamp.from(createdAt), id);
    }
}
