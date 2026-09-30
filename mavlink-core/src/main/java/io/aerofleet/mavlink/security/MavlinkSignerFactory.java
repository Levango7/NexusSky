package io.aerofleet.mavlink.security;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按链路取签名器的工厂：多机密钥模式下每个 sysid 的口令与 linkId 可以不同，
 * 而 {@link MavlinkSigner} 是"一把口令 + 一个 linkId"的不可变封装。
 * <p>
 * 此前 backend 侧只有一个全局注入的 {@link MavlinkSigner}，密钥库
 * （{@code mavlink.signing.key-store-path}）只能提供 per-sysid 的 <b>linkId</b>，
 * 口令仍取全局值——即多机密钥模式实际上没有生效。本类把"取哪把口令"交给
 * {@link SigningKeyManager#keyFor(int)}，并按口令缓存签名器实例，因此
 * 单机（全局口令）与多机（密钥库）走同一条代码路径，没有特判。
 * <p>
 * 缓存键是<b>口令字符串本身</b>：同口令复用同一实例；密钥库热重载换了口令，
 * 新口令自然产生新实例，旧实例只是留在缓存里不再被引用，无需失效逻辑。
 * 口令只来自本地配置/密钥库文件（不受网络输入影响），因此缓存规模上界是配置的链路数，
 * 不存在被外部撑爆的风险。
 */
public class MavlinkSignerFactory {

    private final SigningKeyManager keyManager;
    private final boolean rejectUnsigned;

    /** 口令 → 签名器。 */
    private final ConcurrentHashMap<String, MavlinkSigner> cache = new ConcurrentHashMap<>();

    /**
     * @param keyManager     sysid → 口令/linkId 的来源
     * @param rejectUnsigned 是否拒绝未签名消息（透传给每个派生签名器）
     */
    public MavlinkSignerFactory(SigningKeyManager keyManager, boolean rejectUnsigned) {
        this.keyManager = Objects.requireNonNull(keyManager, "keyManager");
        this.rejectUnsigned = rejectUnsigned;
    }

    /**
     * 取指定 sysid 当前使用的签名器。
     *
     * @param sysid 目标/来源系统 ID
     * @return 已启用的签名器；该 sysid 查不到密钥时返回 null（调用方决定降级还是拒绝）
     */
    public MavlinkSigner signerFor(int sysid) {
        SigningKeyManager.KeyEntry entry = keyManager.keyFor(sysid);
        return entry == null ? null : signerFor(entry);
    }

    /**
     * 按密钥条目取签名器。
     *
     * @param entry 口令与 linkId
     * @return 已启用的签名器；entry 为 null 或口令为空时返回 null
     */
    public MavlinkSigner signerFor(SigningKeyManager.KeyEntry entry) {
        if (entry == null || entry.key() == null || entry.key().isEmpty()) {
            return null;
        }
        return cache.computeIfAbsent(entry.key(),
                key -> new MavlinkSigner(true, key, entry.linkId(), rejectUnsigned));
    }

    /** 该 sysid 应使用的链路 ID（来自密钥库，或单机模式下由 sysid 派生）。 */
    public int linkIdFor(int sysid) {
        SigningKeyManager.KeyEntry entry = keyManager.keyFor(sysid);
        return entry == null ? 0 : entry.linkId();
    }

    /** 是否拒绝未签名消息。 */
    public boolean isRejectUnsigned() {
        return rejectUnsigned;
    }

    /** 已缓存的签名器数量（监控与测试用）。 */
    public int cachedSignerCount() {
        return cache.size();
    }
}
