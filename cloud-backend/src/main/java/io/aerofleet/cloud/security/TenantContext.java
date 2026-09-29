package io.aerofleet.cloud.security;

/**
 * 租户上下文，基于 ThreadLocal 存储当前请求的租户 ID。
 * <p>
 * 由 {@link TenantFilter} 在请求开始时设置，请求结束时清理。
 * 业务代码通过 {@link #getTenantId()} 获取当前租户 ID，
 * null 表示全局管理员（不属于任何特定租户）。
 * <p>
 * 线程安全：每个线程持有独立的 ThreadLocal 副本，
 * 但必须确保在请求结束时调用 {@link #clear()} 防止线程池复用导致的上下文泄漏。
 */
public final class TenantContext {

    /**
     * 「已认证但无租户归属、且非全局管理员」的哨兵租户 ID。
     * <p>
     * 它不与任何资源的 tenant_id 相等，因此所有 {@code tenantId == null || tenantId.equals(...)}
     * 形式的可见性判定会自然得出「什么都看不到」，而不需要改每个调用点。
     * 用 {@link Integer#MIN_VALUE} 是因为它不可能出现在真实租户 ID 序列里。
     */
    public static final Integer NO_ACCESS = Integer.MIN_VALUE;

    private static final ThreadLocal<Integer> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
        // 工具类，禁止实例化
    }

    /**
     * 设置当前请求的租户 ID。
     *
     * @param tenantId 租户 ID；null=全局管理员，{@link #NO_ACCESS}=已认证但无权访问任何租户
     */
    public static void setTenantId(Integer tenantId) {
        TENANT_ID.set(tenantId);
    }

    /**
     * 按「租户 claim + 角色」解析请求应落入的租户域。三态语义在此集中定义，
     * 认证入口（TenantFilter / ApiKeyFilter / WS 握手）都必须走这里，避免各写各的。
     *
     * @param tenantIdClaim 租户 ID claim，可为 null（无归属）
     * @param roleClaim     角色名（ADMIN/OPERATOR/OBSERVER），大小写不敏感，可为 null
     * @return 应设入上下文的值：真实租户 ID；null=全局管理员；{@link #NO_ACCESS}=无租户归属的非管理员
     */
    public static Integer resolveTenantScope(Integer tenantIdClaim, String roleClaim) {
        if (tenantIdClaim != null) {
            return tenantIdClaim;
        }
        // 无租户归属时，只有显式 ADMIN 才是全局管理员；其余一律看不到任何租户数据
        return "ADMIN".equalsIgnoreCase(roleClaim == null ? "" : roleClaim.trim())
                ? null
                : NO_ACCESS;
    }

    /**
     * 获取当前请求的租户 ID。
     *
     * @return 租户 ID，null 表示全局管理员或未设置
     */
    public static Integer getTenantId() {
        return TENANT_ID.get();
    }

    /**
     * 获取当前请求的有效租户 ID（优先 ApiKeyContext，降级 TenantContext）。
     * <p>
     * 查找顺序：
     * <ol>
     *   <li>{@link ApiKeyContext#getTenantId()} — API Key 认证时设置的租户 ID</li>
     *   <li>{@link #getTenantId()} — JWT 认证时由 TenantFilter 设置的租户 ID</li>
     * </ol>
     * 返回 null 表示全局管理员（含无请求上下文的后台线程）；
     * 返回 {@link #NO_ACCESS} 表示已认证但无权访问任何租户的数据。
     *
     * @return 有效租户 ID
     */
    public static Integer getEffectiveTenantId() {
        Integer apiKeyTenantId = ApiKeyContext.getTenantId();
        if (apiKeyTenantId != null) {
            return apiKeyTenantId;
        }
        return TENANT_ID.get();
    }

    /**
     * 写入侧应落到库里的租户归属。
     * <p>
     * {@link #NO_ACCESS} 只是可见性哨兵，绝不能被持久化成资源的 tenant_id；
     * 无归属写入统一按「未归属」（null）处理，由 provisioning 后续指派。
     *
     * @return 真实租户 ID 或 null；永不返回 {@link #NO_ACCESS}
     */
    public static Integer getWritableTenantId() {
        Integer effective = getEffectiveTenantId();
        return NO_ACCESS.equals(effective) ? null : effective;
    }

    /**
     * 当前是否处于全局管理员域（无租户过滤）。
     */
    public static boolean isGlobalScope() {
        return getEffectiveTenantId() == null;
    }

    /**
     * 当前是否「已认证但无任何租户可见」。
     */
    public static boolean isNoAccess() {
        return NO_ACCESS.equals(getEffectiveTenantId());
    }

    /**
     * 清理当前线程的租户上下文。
     * <p>
     * 必须在请求结束时调用（通常在 filter 的 finally 块中），
     * 防止线程池复用时上下文泄漏到后续请求。
     */
    public static void clear() {
        TENANT_ID.remove();
    }
}