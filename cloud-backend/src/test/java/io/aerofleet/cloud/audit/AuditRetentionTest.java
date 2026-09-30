package io.aerofleet.cloud.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志保留清理集成测试（@SpringBootTest + H2 内存数据库）。
 * <p>
 * 覆盖两件事：① {@link AuditRetentionJob} 按 retention-days 删除过期历史行、
 * retention-days&lt;=0 时不动数据；② 删除链前缀之后 {@link AuditService#verifyChain()}
 * 的口径——启用保留时判"截断但自洽"（ok=true + truncated=true），未启用保留时同样的
 * 形态必须继续判红（不降低默认防篡改强度）；截断链内部的内容篡改仍要被抓到。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-retention-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "aerofleet.audit.enabled=true",
        "aerofleet.audit.persist-to-db=true",
        "aerofleet.audit.retention-days=31",
        "aerofleet.device-registry.persist=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("审计日志保留清理与链截断判定")
class AuditRetentionTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AuditRetentionJob retentionJob;

    @BeforeEach
    void cleanUp() throws Exception {
        auditLogRepository.deleteAll();
        auditService.clear();
        resetChainHead();
        // 上下文单例可能被其它测试类改过，逐轮复位为 31 天
        retentionJob.setRetentionDays(31);
        auditService.setRetentionDays(31);
    }

    // ===== 保留删除 =====

    @Test
    @DisplayName("retention-days=31：40 天与 32 天前的行被删，1 小时前的留下")
    void purgeDeletesRowsOlderThanRetention() {
        saveRow("/old-40d", Instant.now().minus(40, ChronoUnit.DAYS));
        saveRow("/old-32d", Instant.now().minus(32, ChronoUnit.DAYS));
        saveRow("/recent", Instant.now().minus(1, ChronoUnit.HOURS));

        retentionJob.purgeOldEntries();

        List<AuditLogEntity> rows = auditLogRepository.findAllByOrderByIdAsc();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTarget()).isEqualTo("/recent");
    }

    @Test
    @DisplayName("retention-days<=0（默认）：保留任务不删任何行")
    void purgeDisabledWhenRetentionDaysNotPositive() {
        retentionJob.setRetentionDays(0);
        saveRow("/very-old", Instant.now().minus(400, ChronoUnit.DAYS));

        retentionJob.purgeOldEntries();

        assertThat(auditLogRepository.count()).isEqualTo(1);
    }

    // ===== 链截断判定 =====

    @Test
    @DisplayName("启用保留时删除链前缀：verify 判 ok=true 且 truncated=true，校验剩余链段")
    void verifyToleratesTruncatedPrefixWhenRetentionEnabled() {
        auditService.record("alice", "POST", "/api/v1/a", null, "10.0.0.1");
        auditService.record("bob", "POST", "/api/v1/b", null, "10.0.0.2");
        auditService.record("carol", "POST", "/api/v1/c", null, "10.0.0.3");

        auditLogRepository.deleteById(auditLogRepository.findAllByOrderByIdAsc().get(0).getId());

        AuditService.ChainVerification v = auditService.verifyChain();
        assertThat(v.ok()).isTrue();
        assertThat(v.truncated()).isTrue();
        assertThat(v.checked()).isEqualTo(2);
        assertThat(v.brokenAtId()).isNull();
    }

    @Test
    @DisplayName("未启用保留时链首不接创世哈希：仍按断链判红（默认强度不变）")
    void missingPrefixStillBrokenWhenRetentionDisabled() {
        auditService.record("alice", "POST", "/api/v1/a", null, "10.0.0.1");
        auditService.record("bob", "POST", "/api/v1/b", null, "10.0.0.2");

        auditService.setRetentionDays(0);
        auditLogRepository.deleteById(auditLogRepository.findAllByOrderByIdAsc().get(0).getId());

        AuditService.ChainVerification v = auditService.verifyChain();
        assertThat(v.ok()).isFalse();
        assertThat(v.truncated()).isFalse();
        assertThat(v.reason()).contains("链首");
    }

    @Test
    @DisplayName("截断链内部改内容仍被抓到：truncated 不是放行的借口")
    void tamperingDetectedInsideTruncatedChain() {
        auditService.record("alice", "POST", "/api/v1/a", null, "10.0.0.1");
        auditService.record("bob", "POST", "/api/v1/b", null, "10.0.0.2");
        auditService.record("carol", "POST", "/api/v1/c", null, "10.0.0.3");

        auditLogRepository.deleteById(auditLogRepository.findAllByOrderByIdAsc().get(0).getId());
        assertThat(auditService.verifyChain().ok()).isTrue(); // 前提：截断态本身是绿的

        AuditLogEntity survivor = auditLogRepository.findAllByOrderByIdAsc().get(0);
        survivor.setTarget("/api/v1/tampered");
        auditLogRepository.save(survivor);

        AuditService.ChainVerification v = auditService.verifyChain();
        assertThat(v.ok()).isFalse();
        assertThat(v.truncated()).isTrue();
        assertThat(v.brokenAtId()).isEqualTo(survivor.getId());
        assertThat(v.reason()).contains("entry_hash");
    }

    @Test
    @DisplayName("保留删除后新记录照常续接链尾（删除只切前缀，不影响写入侧）")
    void chainContinuesAfterRetentionPurge() {
        auditService.record("alice", "POST", "/api/v1/a", null, "10.0.0.1");
        String beforePurge = auditLogRepository.findAllByOrderByIdDesc().get(0).getEntryHash();

        saveRow("/ancient", Instant.now().minus(60, ChronoUnit.DAYS));
        retentionJob.purgeOldEntries();

        auditService.record("bob", "POST", "/api/v1/b", null, "10.0.0.2");
        List<AuditLogEntity> rows = auditLogRepository.findAllByOrderByIdAsc();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).getPrevHash()).isEqualTo(beforePurge);

        // 被删的是链尾之后另插的一行，现存链仍从创世起算 → 不算截断
        AuditService.ChainVerification v = auditService.verifyChain();
        assertThat(v.ok()).isTrue();
        assertThat(v.truncated()).isFalse();
    }

    // ===== 辅助方法 =====

    /** 直接落一行可控时间的审计记录（保留删除按 timestamp 判定，与哈希值无关）。 */
    private void saveRow(String target, Instant timestamp) {
        auditLogRepository.save(new AuditLogEntity(timestamp, "tester", "GET", target,
                null, "127.0.0.1", "0".repeat(64), "x".repeat(64)));
    }

    private void resetChainHead() throws Exception {
        Field field = AuditService.class.getDeclaredField("lastHash");
        field.setAccessible(true);
        field.set(auditService, null);
    }
}
