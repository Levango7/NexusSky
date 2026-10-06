package io.aerofleet.cloud.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuditService 持久化 + 哈希链集成测试（@SpringBootTest + H2 内存数据库）。
 * <p>
 * 验证 persistToDb=true 时记录落库并维护哈希链（prev_hash/entry_hash）、
 * verifyChain 对完好链/篡改行/缺失行分别给出正确结论、链尾可跨"重启"（lastHash
 * 置空）从库中恢复、DB 不可用或 persistToDb=false 时退回纯内存路径。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "aerofleet.audit.enabled=true",
        "aerofleet.audit.persist-to-db=true",
        "aerofleet.device-registry.persist=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("AuditService 持久化 + 哈希链集成测试")
class AuditPersistenceTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @BeforeEach
    void cleanUp() throws Exception {
        auditLogRepository.deleteAll();
        auditService.clear();
        setPersistToDb(true);
        setRepository(auditLogRepository);
        setCapacity(1000);
        resetChainHead();
    }

    // ===== 落库与链 =====

    @Test
    @DisplayName("persistToDb=true 时记录落库，首条 prev_hash 为创世哈希")
    void recordPersistsWithGenesisLink() {
        auditService.record("alice", "POST", "/api/v1/drones", null, "10.0.0.1");

        List<AuditLogEntity> rows = auditLogRepository.findAllByOrderByIdAsc();
        assertThat(rows).hasSize(1);
        AuditLogEntity row = rows.get(0);
        assertThat(row.getUserId()).isEqualTo("alice");
        assertThat(row.getAction()).isEqualTo("POST");
        assertThat(row.getTarget()).isEqualTo("/api/v1/drones");
        assertThat(row.getIp()).isEqualTo("10.0.0.1");
        assertThat(row.getPrevHash()).isEqualTo(AuditService.GENESIS_HASH);
        assertThat(row.getEntryHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(auditService.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("连续记录形成哈希链：每条 prev_hash 等于上一条 entry_hash")
    void consecutiveRecordsFormChain() {
        auditService.record("alice", "POST", "/api/v1/drones/1/arm", null, "10.0.0.1");
        auditService.record("bob", "DELETE", "/api/v1/geofences/3", "reason=test", "10.0.0.2");
        auditService.record("alice", "PUT", "/api/v1/autodispatch/config", null, "10.0.0.1");

        List<AuditLogEntity> rows = auditLogRepository.findAllByOrderByIdAsc();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).getPrevHash()).isEqualTo(rows.get(0).getEntryHash());
        assertThat(rows.get(2).getPrevHash()).isEqualTo(rows.get(1).getEntryHash());
        assertThat(rows.get(0).getPrevHash()).isEqualTo(AuditService.GENESIS_HASH);

        AuditService.ChainVerification v = auditService.verifyChain();
        assertThat(v.ok()).isTrue();
        assertThat(v.reason()).isNull();
        assertThat(v.checked()).isEqualTo(3);
        assertThat(v.brokenAtId()).isNull();
    }

    @Test
    @DisplayName("查询从数据库返回（最新在前），detail 不再被丢弃")
    void queryReturnsFromDbNewestFirstWithDetail() {
        auditService.record("alice", "POST", "/api/v1/a", null, "10.0.0.1");
        auditService.record("bob", "POST", "/api/v1/b", "payload-summary", "10.0.0.2");

        List<AuditLog> all = auditService.findAll();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getTarget()).isEqualTo("/api/v1/b");
        assertThat(all.get(0).getDetail()).isEqualTo("payload-summary");
        assertThat(all.get(0).getEntryHash()).isNotBlank();
        assertThat(all.get(1).getTarget()).isEqualTo("/api/v1/a");

        List<AuditLog> recent = auditService.findRecent(1);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).getTarget()).isEqualTo("/api/v1/b");
    }

    // ===== 链校验：篡改与缺失 =====

    @Test
    @DisplayName("篡改历史行内容 → verifyChain 在首个被改行处判红")
    void tamperedRowBreaksChain() {
        auditService.record("alice", "POST", "/api/v1/a", null, "10.0.0.1");
        auditService.record("bob", "POST", "/api/v1/b", null, "10.0.0.2");

        AuditLogEntity first = auditLogRepository.findAllByOrderByIdAsc().get(0);
        first.setTarget("/api/v1/tampered");
        auditLogRepository.save(first);

        AuditService.ChainVerification v = auditService.verifyChain();
        assertThat(v.ok()).isFalse();
        assertThat(v.checked()).isZero();
        assertThat(v.brokenAtId()).isEqualTo(first.getId());
        assertThat(v.reason()).contains("entry_hash");
    }

    @Test
    @DisplayName("删除中间行 → verifyChain 在缺口后继行处判红（prev_hash 不匹配）")
    void deletedRowBreaksChain() {
        auditService.record("alice", "POST", "/api/v1/a", null, "10.0.0.1");
        auditService.record("bob", "POST", "/api/v1/b", null, "10.0.0.2");
        auditService.record("carol", "POST", "/api/v1/c", null, "10.0.0.3");

        List<AuditLogEntity> rows = auditLogRepository.findAllByOrderByIdAsc();
        auditLogRepository.deleteById(rows.get(1).getId());

        AuditService.ChainVerification v = auditService.verifyChain();
        assertThat(v.ok()).isFalse();
        assertThat(v.checked()).isEqualTo(1);
        assertThat(v.brokenAtId()).isEqualTo(rows.get(2).getId());
        assertThat(v.reason()).contains("prev_hash");
    }

    // ===== 重启恢复 =====

    @Test
    @DisplayName("链尾置空（模拟重启）后再记录：从库尾恢复，prev_hash 接续上一条")
    void chainHeadRestoredFromDbAfterRestart() throws Exception {
        auditService.record("alice", "POST", "/api/v1/a", null, "10.0.0.1");
        auditService.record("bob", "POST", "/api/v1/b", null, "10.0.0.2");
        String lastHashBefore = auditLogRepository.findAllByOrderByIdAsc().get(1).getEntryHash();

        resetChainHead(); // 模拟进程重启：内存链尾丢失
        auditService.record("carol", "POST", "/api/v1/c", null, "10.0.0.3");

        List<AuditLogEntity> rows = auditLogRepository.findAllByOrderByIdAsc();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(2).getPrevHash()).isEqualTo(lastHashBefore);

        // 断言完整判决而非只看 ok()：verifyChain() 对「记录真被篡改」与「库查询抛异常」
        // 返回同一个 ok=false。只断 ok() 时两者不可区分，一次基础设施抖动会以
        // "Expecting value to be true but was false" 的形态出现，既看不出是不是
        // 安全事件，也没有异常内容可查（本条 2026-10-06 实测复现过一次，
        // 单独跑该类 9/9 绿 → 属全量同 JVM 下的间歇失效）。
        // checked() 是判别位：真断链返回首个断链行的 checked 前缀，
        // 而查询异常分支恒返回 checked=0，故 checked()==3 证明循环跑完、不是异常路径。
        AuditService.ChainVerification v = auditService.verifyChain();
        assertThat(v.checked()).isEqualTo(3);
        assertThat(v.ok()).isTrue();
        assertThat(v.reason()).isNull();
        assertThat(v.brokenAtId()).isNull();
    }

    // ===== 内存回退 =====

    @Test
    @DisplayName("persistToDb=false 时仅内存记录，链仍维护且校验通过")
    void persistToDbFalseKeepsMemoryOnly() throws Exception {
        setPersistToDb(false);
        resetChainHead();

        auditService.record("alice", "POST", "/api/v1/a", null, "10.0.0.1");
        auditService.record("bob", "POST", "/api/v1/b", null, "10.0.0.2");

        assertThat(auditLogRepository.count()).isZero();
        assertThat(auditService.findAll()).hasSize(2);
        assertThat(auditService.findAll().get(0).getTarget()).isEqualTo("/api/v1/b");
        AuditService.ChainVerification v = auditService.verifyChain();
        assertThat(v.ok()).isTrue();
        assertThat(v.checked()).isEqualTo(2);
        assertThat(v.brokenAtId()).isNull();
    }

    @Test
    @DisplayName("repository=null（DB 不可用）时退回内存路径，不抛异常")
    void repositoryNullFallsBackToMemory() throws Exception {
        setRepository(null);

        auditService.record("alice", "POST", "/api/v1/a", null, "10.0.0.1");

        assertThat(auditService.size()).isEqualTo(1);
        assertThat(auditService.findAll()).hasSize(1);
        assertThat(auditLogRepository.count()).isZero();
    }

    @Test
    @DisplayName("库查询抛异常时 verifyChain 的结论可与「真被篡改」区分（基础设施故障不得冒充安全事件）")
    void queryExceptionVerdictIsDistinguishableFromTamper() throws Exception {
        AuditLogRepository broken = org.mockito.Mockito.mock(AuditLogRepository.class);
        org.mockito.Mockito.when(broken.findAllByOrderByIdAsc())
                .thenThrow(new IllegalStateException("simulated datasource outage"));
        setRepository(broken);

        AuditService.ChainVerification v = auditService.verifyChain();

        assertThat(v.ok()).isFalse();
        // 判别位：查询异常路径恒 checked=0 / brokenAtId=null；真断链必带具体行 id 与断链原因。
        // 缺了这条，一次库抖动会以「哈希链被篡改」的形态出现在 /api/v1/audit/verify 上。
        assertThat(v.checked()).isZero();
        assertThat(v.brokenAtId()).isNull();
        assertThat(v.reason()).contains("校验失败（查询异常）").contains("simulated datasource outage");
        // 篡改路径的措辞不得出现在基础设施故障的结论里
        assertThat(v.reason()).doesNotContain("entry_hash 与记录内容不符", "prev_hash 与前一条");
    }

    @Test
    @DisplayName("内存缓冲超过容量时丢最旧，链校验从窗口首条起仍通过")
    void memoryCapacityTrimsOldest() throws Exception {
        setPersistToDb(false);
        resetChainHead();
        setCapacity(3);

        for (int i = 0; i < 5; i++) {
            auditService.record("user" + i, "POST", "/api/v1/" + i, null, "10.0.0." + i);
        }

        List<AuditLog> all = auditService.findAll();
        assertThat(all).hasSize(3);
        assertThat(auditService.size()).isEqualTo(3);
        assertThat(all.get(2).getTarget()).isEqualTo("/api/v1/2");
        AuditService.ChainVerification v = auditService.verifyChain();
        assertThat(v.ok()).isTrue();
        assertThat(v.checked()).isEqualTo(3);
    }

    // ===== 辅助方法 =====

    private void setPersistToDb(boolean value) throws Exception {
        Field field = AuditService.class.getDeclaredField("persistToDb");
        field.setAccessible(true);
        field.setBoolean(auditService, value);
    }

    private void setRepository(AuditLogRepository repo) throws Exception {
        Field field = AuditService.class.getDeclaredField("auditLogRepository");
        field.setAccessible(true);
        field.set(auditService, repo);
    }

    private void resetChainHead() throws Exception {
        Field field = AuditService.class.getDeclaredField("lastHash");
        field.setAccessible(true);
        field.set(auditService, null);
    }

    private void setCapacity(int value) throws Exception {
        Field field = AuditService.class.getDeclaredField("capacity");
        field.setAccessible(true);
        field.setInt(auditService, value);
    }
}
