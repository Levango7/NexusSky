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

    private static final ThreadLocal<Integer> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
        // 工具类，禁止实例化
    }

    /**
     * 设置当前请求的租户 ID。
     *
     * @param tenantId 租户 ID，null 表示全局管理员
     */
    public static void setTenantId(Integer tenantId) {
        TENANT_ID.set(tenantId);
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
     * 清理当前线程的租户上下文。
     * <p>
     * 必须在请求结束时调用（通常在 filter 的 finally 块中），
     * 防止线程池复用时上下文泄漏到后续请求。
     */
    public static void clear() {
        TENANT_ID.remove();
    }
}