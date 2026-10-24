package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiKeyCache} 单元测试。
 * <p>
 * 钉住的语义：TTL 过期重查、有效期在读取时重算（TTL 内到期的 Key 立即失效）、
 * 负缓存（不存在/已撤销不重复打库）、撤销后 invalidate 即时生效、
 * 负缓存上限保护（随机 Key 喷射撑不爆内存）。
 */
@DisplayName("ApiKeyCache 认证缓存")
class ApiKeyCacheTest {

    private static final String KEY_HASH = "aa11bb22";

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final ApiKeyCache cache = new ApiKeyCache(clock::get);

    private ApiKeyEntity entity(String keyHash, Integer tenantId, Integer sysid, Instant expiresAt) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyId("nsk_1_" + keyHash.substring(0, 4));
        entity.setKeyHash(keyHash);
        entity.setTenantId(tenantId);
        entity.setSysid(sysid);
        entity.setName("test-key");
        entity.setScopes("[ingest]");
        entity.setRole("OPERATOR");
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(expiresAt);
        entity.setRevoked(false);
        return entity;
    }

    @Test
    @DisplayName("未命中返回 null（调用方应查库）")
    void missReturnsNull() {
        assertThat(cache.lookup(KEY_HASH)).isNull();
    }

    @Test
    @DisplayName("有效条目命中返回完整快照（含 sysid）")
    void validEntryCarriesSnapshot() {
        cache.putValid(KEY_HASH, entity(KEY_HASH, 7, 42, null));

        ApiKeyCache.CachedKey hit = cache.lookup(KEY_HASH);
        assertThat(hit).isNotNull();
        assertThat(hit.valid).isTrue();
        assertThat(hit.keyId).isEqualTo("nsk_1_aa11");
        assertThat(hit.tenantId).isEqualTo(7);
        assertThat(hit.sysid).isEqualTo(42);
        assertThat(hit.role).isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("TTL 过期后条目失效，需重查库")
    void entryExpiresAfterTtl() {
        cache.putValid(KEY_HASH, entity(KEY_HASH, 7, null, null));
        assertThat(cache.lookup(KEY_HASH)).isNotNull();

        clock.addAndGet(ApiKeyCache.TTL_MILLIS);

        assertThat(cache.lookup(KEY_HASH)).isNull();
    }

    @Test
    @DisplayName("缓存的 expiresAt 在读取时重算：快照进缓存时有效、读取时已过期则判无效")
    void expiryRecomputedAtReadTime() {
        // 有效期已过的时间戳：putValid 不拦（快照只存事实），lookup 用当前时钟判无效
        cache.putValid(KEY_HASH, entity(KEY_HASH, 7, null, Instant.now().minusSeconds(1)));

        ApiKeyCache.CachedKey hit = cache.lookup(KEY_HASH);
        assertThat(hit).isNotNull();
        assertThat(hit.valid).isFalse();
    }

    @Test
    @DisplayName("负缓存：不存在/已撤销的 Key 返回确定无效，不回库")
    void negativeEntryIsDefinitive() {
        cache.putInvalid(KEY_HASH);

        ApiKeyCache.CachedKey hit = cache.lookup(KEY_HASH);
        assertThat(hit).isNotNull();
        assertThat(hit.valid).isFalse();
    }

    @Test
    @DisplayName("invalidate 后条目清除（撤销/轮换的本 JVM 即时生效）")
    void invalidateDropsEntry() {
        cache.putValid(KEY_HASH, entity(KEY_HASH, 7, null, null));
        assertThat(cache.lookup(KEY_HASH)).isNotNull();

        cache.invalidate(KEY_HASH);

        assertThat(cache.lookup(KEY_HASH)).isNull();
    }

    @Test
    @DisplayName("负缓存总量受上限保护：随机 Key 喷射撑不爆内存")
    void negativeEntriesAreCapped() {
        for (int i = 0; i < ApiKeyCache.MAX_NEGATIVE_ENTRIES + 100; i++) {
            cache.putInvalid("negative-" + i);
        }
        assertThat(cache.size()).isLessThanOrEqualTo(ApiKeyCache.MAX_NEGATIVE_ENTRIES + 10);
    }

    @Test
    @DisplayName("CachedKey.from 快照与实体一致（含 sysid、撤销状态不进来）")
    void snapshotMatchesEntity() {
        ApiKeyEntity source = entity(KEY_HASH, 9, 13, Instant.parse("2030-01-01T00:00:00Z"));
        ApiKeyCache.CachedKey snapshot = ApiKeyCache.CachedKey.from(source);

        assertThat(snapshot.valid).isTrue();
        assertThat(snapshot.keyId).isEqualTo(source.getKeyId());
        assertThat(snapshot.tenantId).isEqualTo(9);
        assertThat(snapshot.sysid).isEqualTo(13);
        assertThat(snapshot.expiresAt).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("size() 反映条目数（观测 API 此前只被空方向和松上界覆盖）")
    void sizeReflectsEntries() {
        cache.putValid(KEY_HASH, entity(KEY_HASH, 7, null, null));

        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("evictExpired：条目全过期后 size()==0（懒淘汰只影响单键 lookup，size 才是 evict 的观测面）")
    void evictExpiredClearsAllStale() {
        cache.putValid("a", entity("a-hash", 1, null, null));
        cache.putInvalid("b");
        assertThat(cache.size()).isEqualTo(2);

        clock.addAndGet(ApiKeyCache.TTL_MILLIS); // 两条同时到界（>= TTL 归过期）

        cache.evictExpired();

        assertThat(cache.size())
                .as("过期条目必须真正从表里移除：hasNext 取反/remove 删除两个变异体下循环空转或删不掉，size 是它们唯一的现形面")
                .isZero();
    }

    @Test
    @DisplayName("evictExpired：恰好 TTL 整点的条目也清掉（>= 边界含整点）")
    void evictExpiredDropsExactlyAtTtl() {
        cache.putValid("edge", entity("edge-hash", 1, null, null));
        clock.addAndGet(ApiKeyCache.TTL_MILLIS); // now - fetchedAt 恰等于 TTL

        cache.evictExpired();

        assertThat(cache.size())
                .as("整点 = 已过期；边界变异（>= → >）会恰把这条留在表里——单键 lookup 会顺手清掉它，唯有 size 能抓住")
                .isZero();
    }

    @Test
    @DisplayName("负缓存上限精确等于 MAX：满后拒绝新条目（防内存耗尽的闸门）")
    void negativeCapHoldsExactlyAtMax() {
        for (int i = 0; i < ApiKeyCache.MAX_NEGATIVE_ENTRIES; i++) {
            cache.putInvalid("cap-" + i);
        }
        assertThat(cache.size()).isEqualTo(ApiKeyCache.MAX_NEGATIVE_ENTRIES);

        cache.putInvalid("one-too-many");

        assertThat(cache.size())
                .as("第 MAX+1 个不同 hash 必须被拒绝：边界变异（>= → >）只在这个点上放行，size 会变成 MAX+1")
                .isEqualTo(ApiKeyCache.MAX_NEGATIVE_ENTRIES);
    }

    @Test
    @DisplayName("evictExpired 清掉过期条目，保留新鲜条目")
    void evictExpiredKeepsFreshEntries() {
        cache.putValid("fresh", entity("fresh-hash", 1, null, null));
        cache.putInvalid("stale");
        clock.addAndGet(ApiKeyCache.TTL_MILLIS);
        cache.putValid("newest", entity("newest-hash", 1, null, null));

        cache.evictExpired();

        assertThat(cache.lookup("stale")).isNull();
        assertThat(cache.lookup("newest")).isNotNull();
        Optional.ofNullable(cache.lookup("fresh")).ifPresent(present -> assertThat(present.valid).isTrue());
    }
}
