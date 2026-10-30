package io.aerofleet.cloud.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link ApiKeyLastUsedTracker} 刷库路径的**事务边界**回归测试（真 H2 + 真 Spring 代理）。
 *
 * <h2>为什么必须新开一个测试类，而不能加进 {@link ApiKeyLastUsedTrackerTest}</h2>
 *
 * {@code ApiKeyLastUsedTrackerTest} 是纯单元测试：{@code mock(ApiKeyRepository.class)}，
 * **没有 Spring 上下文、没有代理、没有事务基础设施**。在那样的测试里，仓库方法
 * 缺不缺 {@code @Transactional} 根本无从观测——mock 永远返回配置好的值。本类用
 * {@code @SpringBootTest} 拿真 H2，才具备观测能力。
 *
 * <h2>被钉住的缺陷</h2>
 *
 * {@code ApiKeyRepository.updateLastUsedAt} 是 {@code @Modifying} JPQL 更新，
 * 但方法上**没有** {@code @Transactional}；而唯一调用方
 * {@link ApiKeyLastUsedTracker#flush()} 上的 {@code @Transactional} **永远不生效**：
 *
 * <ol>
 *   <li>周期刷写由构造期自建的 {@code ScheduledExecutorService} 驱动
 *       （{@code this::safeFlush}），持有的是**裸对象**，绕过 Spring 代理；</li>
 *   <li>{@code @PreDestroy shutdown()} 里的 {@code flush()} 是同类自调用，同样绕过代理。</li>
 * </ol>
 *
 * 后果：生产上两条路径的每次 UPDATE 都在无事务状态执行，抛
 * {@code TransactionRequiredException} 被逐条 catch 吞成 WARN——**lastUsedAt 从未刷库**，
 * 且每周期每 Key 刷一条假告警。
 *
 * <h2>本类证明的不变式</h2>
 *
 * 刷写的事务边界必须落在**被调用方自己身上**（仓库方法），不依赖调用方代理；
 * 调度线程同款自调用路径下，刷库必须真正落盘。
 */
@SpringBootTest(properties = {
        // 沿用仓内既有约定，避免 UdpGateway 抢 14550 与真实后端/并行测试撞端口
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549"
})
@DisplayName("ApiKeyLastUsedTracker — 刷库事务边界（真 H2，走调度线程同款自调用路径）")
class ApiKeyLastUsedTransactionTest {

    @Autowired
    private ApiKeyLastUsedTracker tracker;

    @Autowired
    private ApiKeyRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private String seededKeyId;

    @AfterEach
    void cleanUp() {
        if (seededKeyId != null) {
            jdbc.update("delete from api_keys where key_id = ?", seededKeyId);
            seededKeyId = null;
        }
    }

    /** 落一条 lastUsedAt=NULL 的 Key，模拟从未被记录过使用时间的存量行。 */
    private String seedKeyWithNullLastUsedAt() {
        seededKeyId = "nsk_tx_" + Long.toHexString(ThreadLocalRandom.current().nextLong());
        ApiKeyEntity entity = new ApiKeyEntity(
                seededKeyId, "hash-" + seededKeyId, 1, null, "tx-boundary-test",
                "[\"read\"]", Instant.now(), null, null, false, "nsk_****test");
        repository.save(entity);

        // 前提自检：没这一步，若种子行本来就带值，用例会在"断言非空"处假绿。
        assertThat(repository.findByKeyId(seededKeyId).orElseThrow().getLastUsedAt())
                .as("种子行必须以 lastUsedAt=NULL 起步")
                .isNull();
        return seededKeyId;
    }

    @Test
    @DisplayName("调度线程同款自调用路径 flush → lastUsedAt 必须真正落盘")
    void schedulerPathFlushPersistsLastUsedAt() {
        String keyId = seedKeyWithNullLastUsedAt();

        // 生产路径的关键姿势：调度器握的是构造期裸对象（this::safeFlush），
        // 不是 Spring 代理。getTargetObject 精确还原这一点；直接调 tracker.flush()
        // 会走代理、掩盖"注解不生效"的缺陷。
        ApiKeyLastUsedTracker target = AopTestUtils.getTargetObject(tracker);

        Instant before = Instant.now().minusSeconds(2);
        target.record(keyId);
        target.flush();

        assertThat(target.pendingSize())
                .as("flush 后 pending 必须清空（逐条移除）")
                .isZero();
        assertThat(repository.findByKeyId(keyId).orElseThrow().getLastUsedAt())
                .as("flush 必须把 lastUsedAt 刷进库；为 null 说明 UPDATE 又在无事务状态被吞了")
                .isNotNull()
                .isCloseTo(Instant.now(), within(30, ChronoUnit.SECONDS))
                .isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("仓库定点 UPDATE 不依赖调用方事务 → 直接调用（无外层事务）也必须成功")
    void repositoryUpdateWorksWithoutCallerTransaction() {
        String keyId = seedKeyWithNullLastUsedAt();
        Instant stamped = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // 这里故意不开事务、也不经由 tracker：钉住事务边界在仓库方法自己身上。
        // 修复前此行抛 TransactionRequiredException；修复后返回 1。
        int updated = repository.updateLastUsedAt(keyId, stamped);

        assertThat(updated).as("定点 UPDATE 应命中刚种下的行").isEqualTo(1);
        assertThat(repository.findByKeyId(keyId).orElseThrow().getLastUsedAt())
                .as("lastUsedAt 应与写入值一致（H2 毫秒精度内）")
                .isCloseTo(stamped, within(1, ChronoUnit.SECONDS));
    }
}
