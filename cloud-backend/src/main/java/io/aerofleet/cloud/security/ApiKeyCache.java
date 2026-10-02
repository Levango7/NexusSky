package io.aerofleet.cloud.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * API Key 认证缓存：把「每请求一次 DB 查询」降为「SHA-256 + 内存查表」。
 * <p>
 * 背景：{@link ApiKeyFilter} 过去对每个带 {@code X-API-Key} 的请求执行
 * {@code findByKeyHashAndRevokedFalse}（SELECT）+ 同步 {@code save} 更新 lastUsedAt
 * （UPDATE）。遥测摄取是高频路径，这两次数据库往返就是摄取吞吐的瓶颈；本缓存消除
 * 认证侧的 SELECT，lastUsedAt 的 UPDATE 由 {@link ApiKeyLastUsedTracker} 合并。
 * <p>
 * 语义：
 * <ul>
 *   <li><b>正缓存</b>（未撤销的 Key 快照）TTL {@value #TTL_MILLIS} ms——过期时间
 *       {@code expiresAt} 不缓存判定结果，每次 lookup 用当前时钟重算，TTL 内到期的
 *       Key 会被正确拒绝，无需等缓存刷新。</li>
 *   <li><b>负缓存</b>（不存在/已撤销的 Key）同样 TTL；总量受 {@value #MAX_NEGATIVE_ENTRIES}
 *       上限保护——攻击者随机喷 Key 时不会把内存撑爆，超限后只放弃缓存不放弃认证。</li>
 *   <li><b>失效</b>：撤销/轮换由 {@link ApiKeyController} 调 {@link #invalidate(String)}，
 *       <b>本 JVM 立即生效</b>；多节点部署下其他节点的生效上界 = 一个 TTL（60s），
 *       这是明示的运维语义，见 docs/security-design.md。</li>
 * </ul>
 * <p>
 * 线程安全：{@link ConcurrentHashMap}，单条目不可变（{@link CachedKey}）。
 * 时钟可注入（测试用），生产取 {@link System#currentTimeMillis()}。
 */
@Component
public class ApiKeyCache {

    /** 缓存条目 TTL（毫秒）：正/负缓存一致；也是多节点撤销传播的上界。 */
    static final long TTL_MILLIS = 60_000;

    /** 负缓存条目上限：防随机 Key 喷射撑爆内存，超出后只放弃负缓存。 */
    static final int MAX_NEGATIVE_ENTRIES = 4096;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Supplier<Long> clockMillis;

    public ApiKeyCache() {
        this(System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟（ epoch 毫秒）。 */
    ApiKeyCache(Supplier<Long> clockMillis) {
        this.clockMillis = clockMillis;
    }

    /**
     * 查询缓存。
     *
     * @param keyHash SHA-256 hex
     * @return null = 未命中（调用方应查库后回填）；非 null = 缓存判定
     *         （{@link CachedKey#valid} 为 false 表示确定无效，无需查库）
     */
    public CachedKey lookup(String keyHash) {
        Entry entry = entries.get(keyHash);
        if (entry == null) {
            return null;
        }
        if (clockMillis.get() - entry.fetchedAtMillis >= TTL_MILLIS) {
            entries.remove(keyHash, entry);
            return null;
        }
        // 有效期在读取时用当前时钟重算，TTL 内到期的 Key 立即失效。
        boolean stillValid = entry.value.valid
                && (entry.value.expiresAt == null || entry.value.expiresAt.isAfter(Instant.now()));
        return stillValid ? entry.value : CachedKey.invalid();
    }

    /** 回填一条有效 Key 快照（未撤销）。 */
    public void putValid(String keyHash, ApiKeyEntity entity) {
        entries.put(keyHash, new Entry(CachedKey.from(entity), clockMillis.get()));
    }

    /** 回填负缓存（不存在或已撤销）。超限后静默放弃，不影响认证正确性。 */
    public void putInvalid(String keyHash) {
        if (entries.size() >= MAX_NEGATIVE_ENTRIES && !entries.containsKey(keyHash)) {
            return;
        }
        entries.put(keyHash, new Entry(CachedKey.invalid(), clockMillis.get()));
    }

    /** 撤销/轮换后使单条失效（本 JVM 立即生效）。 */
    public void invalidate(String keyHash) {
        if (keyHash != null) {
            entries.remove(keyHash);
        }
    }

    /** 清空全部缓存（测试/运维用）。 */
    public void invalidateAll() {
        entries.clear();
    }

    /** 当前条目数（含未过期的正/负条目；观测与测试用）。 */
    public int size() {
        return entries.size();
    }

    /** 清掉已过期条目（测试辅助；生产靠 lookup 惰性淘汰即可）。 */
    void evictExpired() {
        long now = clockMillis.get();
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().fetchedAtMillis >= TTL_MILLIS) {
                it.remove();
            }
        }
    }

    /** 缓存条目：快照 + 取样时间。 */
    private static final class Entry {
        final CachedKey value;
        final long fetchedAtMillis;

        Entry(CachedKey value, long fetchedAtMillis) {
            this.value = value;
            this.fetchedAtMillis = fetchedAtMillis;
        }
    }

    /**
     * 缓存里的 Key 快照（不可变）。
     * {@code valid=false} 表示确定无效（不存在/已撤销/已过期）。
     */
    static final class CachedKey {
        final boolean valid;
        final String keyId;
        final Integer tenantId;
        final Integer sysid;
        final String scopes;
        final String role;
        final Instant expiresAt;

        private CachedKey(boolean valid, String keyId, Integer tenantId, Integer sysid,
                          String scopes, String role, Instant expiresAt) {
            this.valid = valid;
            this.keyId = keyId;
            this.tenantId = tenantId;
            this.sysid = sysid;
            this.scopes = scopes;
            this.role = role;
            this.expiresAt = expiresAt;
        }

        static CachedKey invalid() {
            return new CachedKey(false, null, null, null, null, null, null);
        }

        static CachedKey from(ApiKeyEntity entity) {
            return new CachedKey(true, entity.getKeyId(), entity.getTenantId(), entity.getSysid(),
                    entity.getScopes(), entity.getRole(), entity.getExpiresAt());
        }
    }
}
